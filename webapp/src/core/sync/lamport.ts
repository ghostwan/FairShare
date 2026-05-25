/**
 * Pure Lamport-clock logic. Mirrors
 * `com.fairshare.domain.model.sync.LamportClockLogic`.
 *
 * Reference: DESIGN.md §4.1.
 */

/** Advance the local clock by one tick when emitting a new op. */
export function tickLocal(current: number): number {
  return current + 1;
}

/**
 * Catch-up form: `local = max(local, remote)`. The "emit after receive"
 * pattern composes `tickLocal(merge(local, remote))`, which is `max + 1`.
 */
export function merge(local: number, remote: number): number {
  return remote > local ? remote : local;
}
