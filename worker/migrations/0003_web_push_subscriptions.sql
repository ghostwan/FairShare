-- 0003_web_push_subscriptions.sql — registry of browser Push API
-- subscriptions, parallel to `device_tokens` (FCM) but with a
-- different shape: a Web Push subscription is a triple
-- (endpoint URL, P-256 public key, auth secret) and the endpoint is
-- the routing target (one per browser × origin × SW registration).
--
-- We keep this separate from `device_tokens` rather than overloading
-- the `fcm_token` column because:
--   - the three fields don't fit in one TEXT column without ad-hoc
--     serialisation
--   - the deletion path on a stale push (HTTP 410) keys on
--     `endpoint`, not on `device_id`
--   - browsers occasionally rotate the subscription (eg. after a
--     `pushsubscriptionchange` event) and we want to upsert on
--     (event_id, device_id) so the previous endpoint is replaced.
CREATE TABLE IF NOT EXISTS web_push_subscriptions (
    event_id   TEXT NOT NULL,
    device_id  TEXT NOT NULL,
    endpoint   TEXT NOT NULL,
    p256dh     TEXT NOT NULL, -- base64url, 87 chars (65 bytes uncompressed)
    auth       TEXT NOT NULL, -- base64url, 22 chars (16 bytes)
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (event_id, device_id)
);

-- Look up every subscription of an event during fan-out.
CREATE INDEX IF NOT EXISTS idx_web_push_subscriptions_event
    ON web_push_subscriptions (event_id);

-- Delete by endpoint when the push service tells us a subscription
-- has expired (HTTP 410 Gone). The endpoint is unique enough to act
-- as a lookup key on its own.
CREATE INDEX IF NOT EXISTS idx_web_push_subscriptions_endpoint
    ON web_push_subscriptions (endpoint);
