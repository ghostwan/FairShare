import { base64StdDecode, base64StdEncode, base64UrlDecode } from "@/core/crypto/base64";
import {
  computeWorkerBearer,
  deriveCloudCipherKey,
} from "@/core/crypto/bearer";
import { decryptOp, encryptOp, type EncryptedOp } from "@/core/sync/envelope";
import { decodeOperation, encodeOperation } from "@/core/sync/codec";
import { resolveAll } from "@/core/sync/materializer";
import type { Operation } from "@/core/sync/operation";
import {
  WorkerCloudTransport,
  WorkerTransportError,
} from "@/core/sync/transport";
import type {
  Category,
  Event,
  Expense,
  ExpenseItem,
  ExpenseShare,
  Participant,
} from "@/core/domain/models";
import type {
  CategorySnapshot,
  EventSnapshot,
  ExpenseSnapshot,
  ParticipantSnapshot,
} from "@/core/sync/snapshots";
import { getDb, type OpLogRow } from "@/data/db";
import {
  catchUpLamport,
  getOrCreateDeviceId,
  tickForEmit,
} from "@/data/identityStore";
import { Settings } from "@/data/settings";

/**
 * Sync engine. Owns:
 *
 *   1. The op-log append path (`emit`), which generates lamport,
 *      stamps the device id, persists the cleartext + encrypted
 *      bytes to IndexedDB, and re-materialises the affected entities
 *      into the projection tables (events / participants / expenses
 *      / categories) so the UI sees the change without round-tripping
 *      through the Worker.
 *
 *   2. The push outbox (`push`), which drains pending ops to the
 *      Worker. Push errors leave ops marked pending; the next visibility
 *      change will retry.
 *
 *   3. The pull loop (`pull`), which paginates via
 *      `(nextSince, nextSinceOp)`, decrypts, deduplicates against the
 *      local op log, advances the cursor, and re-materialises.
 *
 *   4. `bootstrapFromInvitation`, which takes the decoded
 *      `fairshare://join` bundle, persists the event secret + seed
 *      ops, computes the bearer, performs the mandatory push-first
 *      handshake (so the Worker creates the bearer verifier), and
 *      then catches up.
 *
 * The coordinator is intentionally stateless beyond the DB; React
 * components hold it via a context provider and call it imperatively.
 */

const transportCache = new Map<string, WorkerCloudTransport>();

async function transport(): Promise<WorkerCloudTransport> {
  const baseUrl = await Settings.getCloudBaseUrl();
  let t = transportCache.get(baseUrl);
  if (!t) {
    t = new WorkerCloudTransport({ baseUrl });
    transportCache.set(baseUrl, t);
  }
  return t;
}

/**
 * Persist a secret for a freshly imported event, idempotent: re-importing
 * the same invitation overwrites the cached bearer with the freshly
 * derived one (cheap; both are functions of the key + eventId).
 */
export async function rememberEventSecret(
  eventId: string,
  eventKey: Uint8Array,
): Promise<void> {
  const bearer = await computeWorkerBearer(eventKey, eventId);
  await getDb().eventSecrets.put({
    eventId,
    eventKeyB64: base64StdEncode(eventKey),
    bearer,
  });
}

async function getEventSecret(
  eventId: string,
): Promise<{ key: Uint8Array; bearer: string }> {
  const row = await getDb().eventSecrets.get(eventId);
  if (!row) {
    throw new Error(`no event secret stored for ${eventId}`);
  }
  return { key: base64StdDecode(row.eventKeyB64), bearer: row.bearer };
}

/** Emit a fresh local op, persist it, and re-materialise. */
export async function emit(
  eventId: string,
  payload: Operation["payload"],
): Promise<Operation> {
  const deviceId = await getOrCreateDeviceId();
  const lamport = await tickForEmit(0);
  const op: Operation = {
    opId: crypto.randomUUID(),
    eventId,
    deviceId,
    lamport,
    wallClockMs: Date.now(),
    payload,
  };
  await persistLocalOp(op);
  await rematerialise(eventId);
  return op;
}

async function persistLocalOp(op: Operation): Promise<void> {
  const secret = await getEventSecret(op.eventId);
  const cloudKey = await deriveCloudCipherKey(secret.key);
  const enc = await encryptOp(op, cloudKey);
  await getDb().opLog.put(toLogRow(op, enc, /*pendingPush*/ 1));
}

function toLogRow(
  op: Operation,
  enc: EncryptedOp,
  pendingPush: 0 | 1,
): OpLogRow {
  return {
    opId: op.opId,
    eventId: op.eventId,
    deviceId: op.deviceId,
    lamport: op.lamport,
    wallClockMs: op.wallClockMs,
    payloadJson: JSON.stringify(encodeOperation(op)),
    nonceB64: base64StdEncode(enc.nonce),
    ciphertextB64: base64StdEncode(enc.ciphertext),
    pendingPush,
  };
}

function rowToOp(row: OpLogRow): Operation {
  return decodeOperation(JSON.parse(row.payloadJson));
}

function rowToEncrypted(row: OpLogRow): EncryptedOp {
  return {
    opId: row.opId,
    lamport: row.lamport,
    deviceId: row.deviceId,
    nonce: base64StdDecode(row.nonceB64),
    ciphertext: base64StdDecode(row.ciphertextB64),
  };
}

/** Drain pending push for the given event. Best-effort, surfaces errors. */
export async function push(eventId: string): Promise<{ pushed: number }> {
  const db = getDb();
  const pending = await db.opLog
    .where("[eventId+lamport]")
    .between([eventId, -Infinity], [eventId, Infinity])
    .filter((r) => r.pendingPush === 1)
    .toArray();
  if (pending.length === 0) return { pushed: 0 };
  const secret = await getEventSecret(eventId);
  const t = await transport();
  const enc = pending.map(rowToEncrypted);
  // Chunk to stay under the Worker's 1000-op cap with margin.
  const CHUNK = 500;
  let pushed = 0;
  for (let i = 0; i < enc.length; i += CHUNK) {
    const slice = enc.slice(i, i + CHUNK);
    await t.push(eventId, secret.bearer, slice);
    pushed += slice.length;
    await db.opLog.bulkUpdate(
      slice.map((op) => ({ key: op.opId, changes: { pendingPush: 0 as const } })),
    );
  }
  return { pushed };
}

/**
 * Pull new ops from the Worker, decrypt, persist, advance cursor,
 * materialise. Returns the number of newly-stored ops.
 */
export async function pull(eventId: string): Promise<{ pulled: number }> {
  const db = getDb();
  const secret = await getEventSecret(eventId);
  const cloudKey = await deriveCloudCipherKey(secret.key);
  const t = await transport();
  let cursor = (await db.opCursor.get(eventId)) ?? {
    eventId,
    nextSince: 0,
    nextSinceOp: "",
  };
  let pulled = 0;
  while (true) {
    const res = await t.pull(
      eventId,
      secret.bearer,
      cursor.nextSince,
      cursor.nextSinceOp,
    );
    if (res.ops.length === 0) {
      cursor = {
        eventId,
        nextSince: res.nextSince,
        nextSinceOp: res.nextSinceOp,
      };
      await db.opCursor.put(cursor);
      if (!res.hasMore) break;
      continue;
    }
    const toInsert: OpLogRow[] = [];
    for (const enc of res.ops) {
      const existing = await db.opLog.get(enc.opId);
      if (existing) continue;
      try {
        const op = await decryptOp(enc, eventId, cloudKey);
        toInsert.push(toLogRow(op, enc, /*pendingPush*/ 0));
        await catchUpLamport(op.lamport);
      } catch (e) {
        console.warn(`pull: dropping un-decryptable op ${enc.opId}`, e);
      }
    }
    if (toInsert.length > 0) {
      await db.opLog.bulkAdd(toInsert);
      pulled += toInsert.length;
    }
    cursor = {
      eventId,
      nextSince: res.nextSince,
      nextSinceOp: res.nextSinceOp,
    };
    await db.opCursor.put(cursor);
    if (!res.hasMore) break;
  }
  if (pulled > 0) await rematerialise(eventId);
  return { pulled };
}

/** Push pending then pull — the standard "sync now" entry point. */
export async function syncNow(eventId: string): Promise<{
  pushed: number;
  pulled: number;
  error?: WorkerTransportError;
}> {
  let pushed = 0;
  let pulled = 0;
  try {
    pushed = (await push(eventId)).pushed;
    pulled = (await pull(eventId)).pulled;
  } catch (e) {
    if (e instanceof WorkerTransportError) {
      return { pushed, pulled, error: e };
    }
    throw e;
  }
  return { pushed, pulled };
}

/**
 * One-shot import of an invitation bundle. Stores the secret, persists
 * the seed ops as already-pushed (the inviter has them), materialises,
 * then does the mandatory push-empty handshake so the Worker registers
 * our bearer, followed by a catch-up pull.
 */
export async function bootstrapFromInvitation(
  eventId: string,
  eventKey: Uint8Array,
  seedOps: Operation[],
): Promise<{ pulled: number }> {
  await rememberEventSecret(eventId, eventKey);
  const secret = await getEventSecret(eventId);
  const cloudKey = await deriveCloudCipherKey(secret.key);
  const db = getDb();
  const rows: OpLogRow[] = [];
  for (const op of seedOps) {
    const existing = await db.opLog.get(op.opId);
    if (existing) continue;
    const enc = await encryptOp(op, cloudKey);
    rows.push(toLogRow(op, enc, /*pendingPush*/ 0));
    await catchUpLamport(op.lamport);
  }
  if (rows.length > 0) await db.opLog.bulkAdd(rows);
  await rematerialise(eventId);

  // Mandatory push-first to register the bearer verifier server-side.
  const t = await transport();
  try {
    await t.push(eventId, secret.bearer, []);
  } catch (e) {
    console.warn("bootstrap: bearer registration ping failed", e);
  }
  try {
    return await pull(eventId);
  } catch (e) {
    console.warn("bootstrap: initial pull failed", e);
    return { pulled: 0 };
  }
}

/**
 * Re-runs the materialiser over the full op log scoped to `eventId`
 * and overwrites the projection tables. Simple and bullet-proof; a
 * future optimisation could track touched entities and run only on
 * those, but at the per-event scale we're targeting (a few hundred
 * ops max) the brute-force approach is well under a frame.
 */
async function rematerialise(eventId: string): Promise<void> {
  const db = getDb();
  const ops = await db.opLog.where("eventId").equals(eventId).toArray();
  const state = resolveAll(ops.map(rowToOp));

  await db.transaction(
    "rw",
    db.events,
    db.participants,
    db.expenses,
    db.categories,
    async () => {
      const evt = state.events.get(eventId);
      if (evt) {
        await db.events.put(eventSnapshotToModel(evt));
      } else {
        await db.events.delete(eventId);
      }

      const existingP = await db.participants.where("eventId").equals(eventId).toArray();
      await db.participants.bulkDelete(existingP.map((p) => p.id));
      const ps: Participant[] = [];
      for (const p of state.participants.values()) {
        if (p.eventId === eventId) ps.push(participantSnapshotToModel(p));
      }
      if (ps.length > 0) await db.participants.bulkPut(ps);

      const existingX = await db.expenses.where("eventId").equals(eventId).toArray();
      await db.expenses.bulkDelete(existingX.map((e) => e.id));
      const xs: Expense[] = [];
      for (const e of state.expenses.values()) {
        if (e.eventId === eventId) xs.push(expenseSnapshotToModel(e));
      }
      if (xs.length > 0) await db.expenses.bulkPut(xs);

      const existingC = await db.categories.where("eventId").equals(eventId).toArray();
      await db.categories.bulkDelete(existingC.map((c) => c.id));
      const cs: Category[] = [];
      for (const c of state.categories.values()) {
        if (c.eventId === eventId) cs.push(categorySnapshotToModel(c));
      }
      if (cs.length > 0) await db.categories.bulkPut(cs);
    },
  );
}

function eventSnapshotToModel(s: EventSnapshot): Event {
  return {
    id: s.id,
    name: s.name,
    description: s.description,
    currency: s.currency,
    createdAt: s.createdAt,
    archived: s.archived,
  };
}

function participantSnapshotToModel(s: ParticipantSnapshot): Participant {
  return { id: s.id, eventId: s.eventId, name: s.name };
}

function expenseSnapshotToModel(s: ExpenseSnapshot): Expense {
  const shares: ExpenseShare[] = s.shares.map((x) => ({
    participantId: x.participantId,
    amountCents: x.amountCents,
  }));
  const items: ExpenseItem[] = s.items.map((x) => ({
    id: x.id,
    label: x.label,
    priceCents: x.priceCents,
    quantity: x.quantity,
    assignedTo: [...x.assignedTo],
  }));
  return {
    id: s.id,
    eventId: s.eventId,
    title: s.title,
    amountCents: s.amountCents,
    payerId: s.payerId,
    date: s.date,
    shares,
    items,
    isSettlement: s.isSettlement,
    categoryId: s.categoryId,
  };
}

function categorySnapshotToModel(s: CategorySnapshot): Category {
  return {
    id: s.id,
    eventId: s.eventId,
    name: s.name,
    emoji: s.emoji,
    color: s.color,
    isDefault: false,
  };
}

// Unused helpers re-exported so the few callers that need to massage
// raw key bytes (e.g. the QR generator) don't have to rebuild them.
export { base64UrlDecode };
