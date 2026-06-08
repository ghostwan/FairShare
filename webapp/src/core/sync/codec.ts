import type { Operation } from "./operation";
import { OP_TYPE, type OpPayload, type OpType } from "./opPayload";
import type {
  CategorySnapshot,
  EventSnapshot,
  ExpenseItemSnapshot,
  ExpenseShareSnapshot,
  ExpenseSnapshot,
  ParticipantSnapshot,
} from "./snapshots";

/**
 * JSON codec mirroring `kotlinx.serialization` with
 * `classDiscriminator = "type"` and `encodeDefaults = true`. Encoding
 * preserves Kotlin's declaration order field-by-field so the produced
 * JSON looks identical to what `app/` would emit (the Worker stores
 * blobs opaquely, so byte-equality isn't enforced, but matching makes
 * cross-format diff debugging trivial).
 *
 * Parsing tolerates any field order and ignores unknown keys, matching
 * `ignoreUnknownKeys = true` on the Kotlin side.
 */

// ---------------------------------------------------------------------------
// Encoders — each builds an object whose property insertion order matches
// the Kotlin declaration order, then hands it to JSON.stringify (which in
// V8 + JSC preserves insertion order for string keys per ECMA-262).
// ---------------------------------------------------------------------------

function encodeEventSnapshot(s: EventSnapshot): unknown {
  return {
    id: s.id,
    name: s.name,
    description: s.description,
    currency: s.currency,
    createdAt: s.createdAt,
    archived: s.archived,
    giftModeEnabled: s.giftModeEnabled,
  };
}

function encodeParticipantSnapshot(s: ParticipantSnapshot): unknown {
  return { id: s.id, eventId: s.eventId, name: s.name };
}

function encodeExpenseShareSnapshot(s: ExpenseShareSnapshot): unknown {
  return {
    id: s.id,
    participantId: s.participantId,
    amountCents: s.amountCents,
    coveredBy: s.coveredBy && s.coveredBy.length > 0 ? s.coveredBy : null,
  };
}

function encodeExpenseItemSnapshot(s: ExpenseItemSnapshot): unknown {
  return {
    id: s.id,
    label: s.label,
    priceCents: s.priceCents,
    quantity: s.quantity,
    assignedTo: s.assignedTo,
  };
}

function encodeExpenseSnapshot(s: ExpenseSnapshot): unknown {
  return {
    id: s.id,
    eventId: s.eventId,
    title: s.title,
    amountCents: s.amountCents,
    payerId: s.payerId,
    date: s.date,
    shares: s.shares.map(encodeExpenseShareSnapshot),
    items: s.items.map(encodeExpenseItemSnapshot),
    isSettlement: s.isSettlement,
    categoryId: s.categoryId,
  };
}

function encodeCategorySnapshot(s: CategorySnapshot): unknown {
  return {
    id: s.id,
    eventId: s.eventId,
    name: s.name,
    emoji: s.emoji,
    color: s.color,
  };
}

export function encodeOpPayload(p: OpPayload): unknown {
  switch (p.type) {
    case OP_TYPE.EventUpsert:
      return { type: p.type, event: encodeEventSnapshot(p.event) };
    case OP_TYPE.EventDelete:
      return { type: p.type, eventId: p.eventId };
    case OP_TYPE.ParticipantUpsert:
      return {
        type: p.type,
        participant: encodeParticipantSnapshot(p.participant),
      };
    case OP_TYPE.ParticipantDelete:
      return { type: p.type, participantId: p.participantId };
    case OP_TYPE.ExpenseUpsert:
      return { type: p.type, expense: encodeExpenseSnapshot(p.expense) };
    case OP_TYPE.ExpenseDelete:
      return { type: p.type, expenseId: p.expenseId };
    case OP_TYPE.CategoryUpsert:
      return { type: p.type, category: encodeCategorySnapshot(p.category) };
    case OP_TYPE.CategoryDelete:
      return { type: p.type, categoryId: p.categoryId };
  }
}

export function encodeOperation(op: Operation): unknown {
  return {
    opId: op.opId,
    eventId: op.eventId,
    deviceId: op.deviceId,
    lamport: op.lamport,
    wallClockMs: op.wallClockMs,
    payload: encodeOpPayload(op.payload),
  };
}

export function encodeOperationsJson(ops: Operation[]): string {
  return JSON.stringify(ops.map(encodeOperation));
}

// ---------------------------------------------------------------------------
// Decoders — lenient: unknown fields are dropped, missing optional fields
// receive Kotlin defaults, types are validated where it matters.
// ---------------------------------------------------------------------------

function asString(v: unknown, field: string): string {
  if (typeof v !== "string") {
    throw new TypeError(`field ${field}: expected string, got ${typeof v}`);
  }
  return v;
}

function asNumber(v: unknown, field: string): number {
  if (typeof v !== "number" || !Number.isFinite(v)) {
    throw new TypeError(`field ${field}: expected number, got ${typeof v}`);
  }
  return v;
}

function asBool(v: unknown, field: string): boolean {
  if (typeof v !== "boolean") {
    throw new TypeError(`field ${field}: expected boolean, got ${typeof v}`);
  }
  return v;
}

function asObject(v: unknown, field: string): Record<string, unknown> {
  if (v == null || typeof v !== "object" || Array.isArray(v)) {
    throw new TypeError(`field ${field}: expected object`);
  }
  return v as Record<string, unknown>;
}

function asArray(v: unknown, field: string): unknown[] {
  if (!Array.isArray(v)) {
    throw new TypeError(`field ${field}: expected array`);
  }
  return v;
}

function decodeEventSnapshot(raw: unknown): EventSnapshot {
  const o = asObject(raw, "event");
  return {
    id: asString(o.id, "event.id"),
    name: asString(o.name, "event.name"),
    description:
      o.description == null ? null : asString(o.description, "event.description"),
    currency: o.currency == null ? "EUR" : asString(o.currency, "event.currency"),
    createdAt: asNumber(o.createdAt, "event.createdAt"),
    archived: o.archived == null ? false : asBool(o.archived, "event.archived"),
    // Default to `true` so peers (Android, or older webapp releases)
    // that omit the field don't silently disable gift mode on every
    // synced event.
    giftModeEnabled:
      o.giftModeEnabled == null
        ? true
        : asBool(o.giftModeEnabled, "event.giftModeEnabled"),
  };
}

function decodeParticipantSnapshot(raw: unknown): ParticipantSnapshot {
  const o = asObject(raw, "participant");
  return {
    id: asString(o.id, "participant.id"),
    eventId: asString(o.eventId, "participant.eventId"),
    name: asString(o.name, "participant.name"),
  };
}

function decodeExpenseShareSnapshot(raw: unknown): ExpenseShareSnapshot {
  const o = asObject(raw, "share");
  const coveredBy =
    o.coveredBy == null
      ? null
      : asArray(o.coveredBy, "share.coveredBy").map((x) =>
          asString(x, "share.coveredBy[]"),
        );
  return {
    id: asString(o.id, "share.id"),
    participantId: asString(o.participantId, "share.participantId"),
    amountCents: asNumber(o.amountCents, "share.amountCents"),
    coveredBy,
  };
}

function decodeExpenseItemSnapshot(raw: unknown): ExpenseItemSnapshot {
  const o = asObject(raw, "item");
  const assignedTo =
    o.assignedTo == null
      ? []
      : asArray(o.assignedTo, "item.assignedTo").map((x) =>
          asString(x, "item.assignedTo[]"),
        );
  return {
    id: asString(o.id, "item.id"),
    label: asString(o.label, "item.label"),
    priceCents: asNumber(o.priceCents, "item.priceCents"),
    quantity: o.quantity == null ? 1 : asNumber(o.quantity, "item.quantity"),
    assignedTo,
  };
}

function decodeExpenseSnapshot(raw: unknown): ExpenseSnapshot {
  const o = asObject(raw, "expense");
  const shares =
    o.shares == null
      ? []
      : asArray(o.shares, "expense.shares").map(decodeExpenseShareSnapshot);
  const items =
    o.items == null
      ? []
      : asArray(o.items, "expense.items").map(decodeExpenseItemSnapshot);
  return {
    id: asString(o.id, "expense.id"),
    eventId: asString(o.eventId, "expense.eventId"),
    title: asString(o.title, "expense.title"),
    amountCents: asNumber(o.amountCents, "expense.amountCents"),
    payerId: asString(o.payerId, "expense.payerId"),
    date: asNumber(o.date, "expense.date"),
    shares,
    items,
    isSettlement:
      o.isSettlement == null
        ? false
        : asBool(o.isSettlement, "expense.isSettlement"),
    categoryId:
      o.categoryId == null
        ? null
        : asString(o.categoryId, "expense.categoryId"),
  };
}

function decodeCategorySnapshot(raw: unknown): CategorySnapshot {
  const o = asObject(raw, "category");
  return {
    id: asString(o.id, "category.id"),
    eventId: asString(o.eventId, "category.eventId"),
    name: asString(o.name, "category.name"),
    emoji: asString(o.emoji, "category.emoji"),
    color: asNumber(o.color, "category.color"),
  };
}

export function decodeOpPayload(raw: unknown): OpPayload {
  const o = asObject(raw, "payload");
  const type = asString(o.type, "payload.type") as OpType;
  switch (type) {
    case OP_TYPE.EventUpsert:
      return { type, event: decodeEventSnapshot(o.event) };
    case OP_TYPE.EventDelete:
      return { type, eventId: asString(o.eventId, "payload.eventId") };
    case OP_TYPE.ParticipantUpsert:
      return { type, participant: decodeParticipantSnapshot(o.participant) };
    case OP_TYPE.ParticipantDelete:
      return {
        type,
        participantId: asString(o.participantId, "payload.participantId"),
      };
    case OP_TYPE.ExpenseUpsert:
      return { type, expense: decodeExpenseSnapshot(o.expense) };
    case OP_TYPE.ExpenseDelete:
      return { type, expenseId: asString(o.expenseId, "payload.expenseId") };
    case OP_TYPE.CategoryUpsert:
      return { type, category: decodeCategorySnapshot(o.category) };
    case OP_TYPE.CategoryDelete:
      return {
        type,
        categoryId: asString(o.categoryId, "payload.categoryId"),
      };
    default:
      throw new TypeError(`unknown OpPayload type: ${type}`);
  }
}

export function decodeOperation(raw: unknown): Operation {
  const o = asObject(raw, "operation");
  return {
    opId: asString(o.opId, "op.opId"),
    eventId: asString(o.eventId, "op.eventId"),
    deviceId: asString(o.deviceId, "op.deviceId"),
    lamport: asNumber(o.lamport, "op.lamport"),
    wallClockMs: asNumber(o.wallClockMs, "op.wallClockMs"),
    payload: decodeOpPayload(o.payload),
  };
}

export function decodeOperationsJson(json: string): Operation[] {
  const raw = JSON.parse(json);
  if (!Array.isArray(raw)) {
    throw new TypeError("expected a JSON array of operations");
  }
  return raw.map(decodeOperation);
}

// ---------------------------------------------------------------------------
// Cloud envelope — the JSON object that sits inside each AES-GCM ciphertext.
// Matches `CloudOpCodec.Envelope` field order: version, wallClockMs, payload.
// ---------------------------------------------------------------------------

export const ENVELOPE_VERSION = 1;

export interface Envelope {
  version: number;
  wallClockMs: number;
  payload: OpPayload;
}

export function encodeEnvelopeJson(env: Envelope): string {
  return JSON.stringify({
    version: env.version,
    wallClockMs: env.wallClockMs,
    payload: encodeOpPayload(env.payload),
  });
}

export function decodeEnvelopeJson(json: string): Envelope {
  const o = asObject(JSON.parse(json), "envelope");
  return {
    version: o.version == null ? ENVELOPE_VERSION : asNumber(o.version, "version"),
    wallClockMs: asNumber(o.wallClockMs, "envelope.wallClockMs"),
    payload: decodeOpPayload(o.payload),
  };
}
