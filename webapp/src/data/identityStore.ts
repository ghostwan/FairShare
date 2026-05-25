import { getDb } from "./db";

/**
 * Per-device identity + persistent Lamport clock. Mirrors
 * `SyncIdentityStore` on Android: the device id is generated the first
 * time it's read and never changes thereafter; the Lamport counter is
 * monotonic and incremented atomically per emitted op.
 *
 * Concurrency: all mutations go through `Dexie.transaction` so two
 * concurrent emits on the same device produce two distinct lamport
 * values even under React StrictMode double-invocation in dev.
 */

const SINGLETON_ID = "singleton" as const;

export async function getOrCreateDeviceId(): Promise<string> {
  const db = getDb();
  return db.transaction("rw", db.identity, async () => {
    const row = await db.identity.get(SINGLETON_ID);
    if (row) return row.deviceId;
    const deviceId = crypto.randomUUID();
    await db.identity.put({ id: SINGLETON_ID, deviceId, lamport: 0 });
    return deviceId;
  });
}

export async function readLamport(): Promise<number> {
  const db = getDb();
  const row = await db.identity.get(SINGLETON_ID);
  return row?.lamport ?? 0;
}

/**
 * Advances the local clock past the remote value, then returns the
 * new lamport value to stamp on the outgoing op. Equivalent to
 * `tickLocal(merge(local, remote))` = `max(local, remote) + 1`.
 */
export async function tickForEmit(remote: number): Promise<number> {
  const db = getDb();
  return db.transaction("rw", db.identity, async () => {
    const row = await db.identity.get(SINGLETON_ID);
    const current = row?.lamport ?? 0;
    const next = Math.max(current, remote) + 1;
    const deviceId = row?.deviceId ?? crypto.randomUUID();
    await db.identity.put({ id: SINGLETON_ID, deviceId, lamport: next });
    return next;
  });
}

/** Catch-up form: bump local to at least `remote`, without emitting. */
export async function catchUpLamport(remote: number): Promise<void> {
  const db = getDb();
  await db.transaction("rw", db.identity, async () => {
    const row = await db.identity.get(SINGLETON_ID);
    const current = row?.lamport ?? 0;
    if (remote > current) {
      const deviceId = row?.deviceId ?? crypto.randomUUID();
      await db.identity.put({ id: SINGLETON_ID, deviceId, lamport: remote });
    }
  });
}
