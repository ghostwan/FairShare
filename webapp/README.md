# FairShare webapp

Progressive Web App companion for the [FairShare Android
app](../app). It pairs with an Android device through the existing
QR-code invitation flow and behaves like any other paired device:
end-to-end encrypted, syncs via the Cloudflare Worker, no separate
account.

The primary target is **iPhone / iPad Safari** — installable to the
home screen so non-Android friends get the same UX without us paying
Apple's developer tax.

## Stack

- React 18 + Vite 5 + TypeScript (strict)
- MUI v6 (Material 3 visual parity with the Android client)
- Dexie 4 (IndexedDB) for local persistence
- Web Crypto (HKDF + AES-GCM + HMAC-SHA256) for the sync envelope
- vite-plugin-pwa in `injectManifest` mode + a custom service worker
  (`src/sw.ts`) handling precache, Web Push notifications, and a
  SW→main bridge that triggers background syncs
- Vitest (unit + cross-format, ~64 tests) + Playwright (Chromium +
  WebKit + iOS Safari E2E)
- Deployed on Cloudflare Pages (`fairshare-web.pages.dev`)

## Wire-format compatibility

The TS code re-implements the Android sync protocol byte-for-byte:
HKDF-SHA256 salt is 32 zero bytes, info strings are
`fairshare-invitation-mac` / `fairshare-worker-auth` /
`fairshare-cloud-cipher`, AES-GCM payloads use the JDK
`ciphertext||tag` layout, JSON keeps Kotlin declaration order and emits
defaults (`encodeDefaults=true`), and `OpPayload` variants serialise
with their fully-qualified Kotlin class name as the discriminator. The
cross-format tests in `src/core/**` lock this down.

## Commands

```bash
npm install                  # one-time
npm run dev                  # http://localhost:5173
npm run build                # tsc + vite build → dist/
npm run typecheck
npm test                     # vitest run
npm run test:watch
npm run e2e                  # playwright (run e2e:install first time)
npm run deploy               # wrangler pages deploy
```

## Layout

```
src/
├── core/            # pure logic, no React, no DOM beyond Web Crypto
│   ├── crypto/      # HKDF, AES-GCM, bearer derivation
│   ├── domain/      # models + per-category stats aggregation
│   ├── invitation/  # codec for fairshare://join + https://…/join
│   └── sync/        # operations, lamport, materializer, transport
├── data/            # Dexie schemas (v2: webPushPrefs) + repositories
├── domain/          # default categories
├── sync/            # browser-side coordinator, webPush, SW bridge
├── sw.ts            # custom service worker (precache + push handler)
└── presentation/    # MUI screens + components (incl. StatsScreen)
tests/
├── e2e/             # Playwright (chromium / webkit / ios-safari)
└── unit/            # cross-cutting tests (most live alongside src)
```

## Web Push notifications

Opt-in per event from the Event settings screen. Flow:

1. Webapp fetches the Worker's public VAPID key (`GET /web-push/key`).
2. `PushManager.subscribe` produces `{ endpoint, p256dh, auth }`,
   pushed to the Worker via `PUT /events/:id/devices/:did/web-push`.
3. On every accepted op batch the Worker encrypts an empty payload per
   RFC 8291 (`aes128gcm`), signs a VAPID JWT (ES256), and POSTs it to
   each subscriber's endpoint.
4. `src/sw.ts` receives the `push` event. If a FairShare tab is
   visible it stays silent and just postMessages `fairshare/push` to
   the page, which triggers `syncNow(eventId)`. Otherwise it shows a
   minimal notification (Chrome requires a user-visible notification
   per push, so background tabs always get one).
5. `pushsubscriptionchange` re-subscribes and re-registers
   transparently. The opt-in state is persisted in Dexie
   (`webPushPrefs` table) so a reload restores the subscription.
