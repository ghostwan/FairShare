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
- vite-plugin-pwa (Workbox) for installability + offline shell
- Vitest (unit + cross-format) + Playwright (Chromium + WebKit E2E)
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
│   ├── invitation/  # codec for fairshare://join + https://…/join
│   └── sync/        # operations, lamport, materializer, transport
├── data/            # Dexie schemas + repositories
├── domain/          # models, default categories
└── presentation/    # MUI screens + components
tests/
├── e2e/             # Playwright
└── unit/            # cross-cutting tests (most live alongside src)
```
