-- FairShare sync ops log. Each row is a single CRDT operation, opaque to the
-- server (ciphertext + nonce produced client-side with AES-256-GCM keyed by the
-- per-event 32-byte key). The server enforces idempotency by `op_id` and lets
-- clients tail the log via `lamport > ?` ordering.

CREATE TABLE IF NOT EXISTS ops (
    event_id     TEXT    NOT NULL,
    op_id        TEXT    NOT NULL PRIMARY KEY,
    lamport      INTEGER NOT NULL,
    device_id    TEXT    NOT NULL,
    nonce        BLOB    NOT NULL,
    ciphertext   BLOB    NOT NULL,
    received_at  INTEGER NOT NULL
);

-- Tail-style cursor: clients pull with `WHERE event_id = ? AND lamport > ?`.
-- Adding `op_id` as a tiebreaker keeps the ordering deterministic when two
-- devices end up emitting operations at the same Lamport timestamp.
CREATE INDEX IF NOT EXISTS idx_ops_event_lamport
    ON ops (event_id, lamport, op_id);

-- Per-event bearer verifier. We never persist the raw bearer; we only keep
-- `SHA-256(bearer)` as a verifier so a dump of the D1 database cannot be
-- replayed against the live Worker. The bearer itself is HMAC-SHA256 keyed by
-- the per-event 32-byte key, so possessing the eventKey is enough to mint it
-- locally — no provisioning step required.
CREATE TABLE IF NOT EXISTS event_bearers (
    event_id    TEXT    NOT NULL PRIMARY KEY,
    verifier    TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);

