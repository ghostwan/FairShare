# FairShare sync Worker

Zero-knowledge transport for FairShare CRDT operations. The Worker
stores opaque AES-256-GCM ciphertext keyed by `eventId` and lets
devices push/pull the per-event op log. The plaintext never leaves
client devices; losing the D1 database leaks zero expense data.

See `app/docs/sync/DESIGN.md` §6.2 and §7 for the full design.

## Endpoints

| Method | Path                                              | Body / query                                                | Response                       |
| ------ | ------------------------------------------------- | ----------------------------------------------------------- | ------------------------------ |
| POST   | `/events/:id/ops`                                 | `{ ops: [{ opId, lamport, deviceId, nonce, ciphertext }] }` | `{ inserted }`                 |
| GET    | `/events/:id/ops`                                 | `?since=<lamport>` (default 0)                              | `{ ops, nextSince, hasMore }`  |
| PUT    | `/events/:id/devices/:deviceId/token`             | `{ fcmToken: "<token>" }`                                   | `{ ok: true }`                 |
| DELETE | `/events/:id/devices/:deviceId/token`             | —                                                           | `{ removed: <n> }`             |
| PUT    | `/events/:id/devices/:deviceId/web-push`          | `{ endpoint, p256dh, auth }`                                | `{ ok: true }`                 |
| DELETE | `/events/:id/devices/:deviceId/web-push`          | —                                                           | `{ removed: <n> }`             |
| GET    | `/web-push/key`                                   | —                                                           | `{ publicKey }` (VAPID, public)|
| GET    | `/health` or `/`                                  | —                                                           | `fairshare-sync ok`            |

Authentication: `Authorization: Bearer <hex(HMAC-SHA256(eventKey, eventId))>`.
The Worker stores `SHA-256(bearer)` on the first request and constant-time
compares on subsequent ones, so a D1 dump cannot be replayed.

`nonce` and `ciphertext` are base64-encoded payloads of AES-256-GCM keyed by
the per-event 32-byte secret (never sent to the Worker).

## FCM push fan-out

When `FCM_SERVICE_ACCOUNT` is set (see Deploy), every successful POST
`/ops` triggers a data-only push to every device registered for that
event via `PUT /devices/:deviceId/token`, except the senders in the
current batch. The Android app reacts by triggering a one-shot sync,
so paired devices see writes within seconds without polling.

When the secret is absent, fan-out is silently skipped and the app
falls back to manual / on-resume syncs.

## Web Push fan-out (VAPID)

The webapp PWA registers a Web Push subscription per event via `PUT
/events/:id/devices/:deviceId/web-push` (table
`web_push_subscriptions`, migration `0003`). When `VAPID_PRIVATE_KEY`
is set and `VAPID_PUBLIC_KEY` / `VAPID_SUBJECT` are configured in
`wrangler.toml`, every accepted op batch is fanned out to every
subscriber for the event (except the senders), using RFC 8291
`aes128gcm` encryption and an ES256 VAPID JWT. The payload is an
empty body — the webapp service worker treats the push as a
"sync now" trigger.

Generate a fresh keypair with:

```bash
node worker/scripts/generate-vapid.mjs
# prints VAPID_PUBLIC_KEY (raw base64url) and a JWK (base64url-encoded
# JSON) ready for `wrangler secret put VAPID_PRIVATE_KEY`.
```

The webapp fetches the public key at runtime through `GET
/web-push/key`, so rotating it only requires re-running
`wrangler secret put` + `wrangler deploy`.

## Local dev

```bash
cd worker
npm install
npx wrangler d1 migrations apply fairshare-sync-db --local
npx wrangler dev
```

The first command provisions a local SQLite copy of the D1 schema under
`.wrangler/state/`. The second launches the Worker on `http://localhost:8787`.

Smoke-test:

```bash
EVT=00000000-0000-4000-8000-000000000001
BEARER=$(python3 -c 'print("a"*64)')   # any 64-hex string works for the first push

# Push one (clearly-fake, base64-only) op:
curl -s -X POST -H "authorization: Bearer $BEARER" \
  -H "content-type: application/json" \
  --data '{"ops":[{"opId":"'"$EVT"'","lamport":1,"deviceId":"dev","nonce":"AAAA","ciphertext":"AAAA"}]}' \
  http://localhost:8787/events/$EVT/ops

# Pull:
curl -s -H "authorization: Bearer $BEARER" \
  "http://localhost:8787/events/$EVT/ops?since=0"
```

## Deploy

Once `wrangler login` has run (or `CLOUDFLARE_API_TOKEN` is set):

```bash
# One-time: create the D1 database, copy the printed database_id
# into wrangler.toml under [[d1_databases]].database_id.
npx wrangler d1 create fairshare-sync-db

# Apply the schema to the live D1:
npx wrangler d1 migrations apply fairshare-sync-db --remote

# Deploy:
npx wrangler deploy

# Optional: enable FCM push fan-out (avoids client-side polling).
# 1. In the Firebase console, Project Settings → Service accounts →
#    Generate new private key. Download the JSON.
# 2. Upload it as a Worker secret:
cat /path/to/service-account.json | npx wrangler secret put FCM_SERVICE_ACCOUNT

# Optional: enable Web Push fan-out for the PWA companion.
# 1. Generate a VAPID keypair:
node scripts/generate-vapid.mjs
# 2. Copy VAPID_PUBLIC_KEY + VAPID_SUBJECT into wrangler.toml [vars],
#    then upload the private JWK as a secret:
npx wrangler secret put VAPID_PRIVATE_KEY
```

The resulting URL is `https://fairshare-sync.<account>.workers.dev`
and is what the Android client should target.
