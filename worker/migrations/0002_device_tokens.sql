-- FairShare FCM push fan-out. When a device pushes new ops, the Worker
-- notifies every other paired device of the same event so they can
-- pull immediately instead of waiting for the next foreground poll.
--
-- Tokens are scoped per (event_id, device_id) so a single device that
-- joined multiple events keeps one row per event, and unregistering a
-- single event leaves the others untouched. The (event_id, device_id)
-- composite primary key gives us upsert-on-refresh for free.

CREATE TABLE IF NOT EXISTS device_tokens (
    event_id    TEXT    NOT NULL,
    device_id   TEXT    NOT NULL,
    fcm_token   TEXT    NOT NULL,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (event_id, device_id)
);

-- Lookup by event_id is the hot path (fan-out reads all tokens for
-- the event being pushed to).
CREATE INDEX IF NOT EXISTS idx_device_tokens_event
    ON device_tokens (event_id);
