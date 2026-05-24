package com.fairshare.domain.model.sync

/**
 * Pure logic for advancing a Lamport clock.
 *
 * Kept separate from any storage backend so the algorithm can be unit-tested
 * without DataStore / coroutines. The real persisted clock (see
 * `com.fairshare.data.sync.SyncIdentityStore`) calls into these helpers under a
 * mutex.
 *
 * Reference: DESIGN.md §4.1.
 */
object LamportClockLogic {

    /** Advance the local clock by one tick when emitting a new operation. */
    fun tickLocal(current: Long): Long = current + 1

    /**
     * Reconcile the local clock with a remote op's lamport value. After
     * observing a remote op, the next local tick must be greater than both the
     * previous local value and the remote value, so we set
     * `local = max(local, remote) + 1` if we are about to emit, or simply
     * `local = max(local, remote)` if we are only catching up. This helper does
     * the catch-up form; emit-after-receive composes `tickLocal(merge(...))`.
     */
    fun merge(local: Long, remote: Long): Long =
        if (remote > local) remote else local
}
