import type { OpPayload } from "./opPayload";

/**
 * A single CRDT operation. Immutable: once emitted, persisted to the
 * local op log and never mutated. Convergence comes from the
 * `(lamport, deviceId)` Lamport pair — see `materializer.ts`.
 *
 * Mirrors `com.fairshare.domain.model.sync.Operation`. The on-wire JSON
 * field order is exactly: opId, eventId, deviceId, lamport, wallClockMs,
 * payload — declaration order from the Kotlin data class.
 */
export interface Operation {
  opId: string; // UUID v4, also the idempotency key
  eventId: string;
  deviceId: string;
  lamport: number;
  wallClockMs: number;
  payload: OpPayload;
}

/**
 * Lexicographic compare over `(lamport, deviceId)`. Returns a negative
 * number when `a < b`, positive when `a > b`, zero on equality. The
 * materializer picks the *maximum* per entity, so this is wired into
 * `Array.prototype.sort` callbacks accordingly.
 */
export function compareLww(a: Operation, b: Operation): number {
  if (a.lamport !== b.lamport) return a.lamport - b.lamport;
  if (a.deviceId < b.deviceId) return -1;
  if (a.deviceId > b.deviceId) return 1;
  return 0;
}
