# FairShare

Android app to split shared expenses (trip, dinner, flatshare) — Tricount-style —
with one flagship feature: **scan a paper receipt and assign each line item to
the people who actually consumed it**.

## Stack

- Kotlin + Jetpack Compose (Material 3)
- Clean Architecture: `domain` / `data` / `presentation`
- Hilt (DI), Room (persistence), Coroutines / Flow
- DataStore (settings, including the optional Gemini API key)
- ML Kit Text Recognition (primary OCR engine, fully offline)
- Gemini 2.5 Flash via REST (optional AI fallback when ML Kit misreads a receipt)
- OkHttp + kotlinx-serialization (Gemini transport)
- CameraX / `PickVisualMedia` (capture / gallery)

## Features

- Create an event (trip, meal, flatshare…) with its own currency, rename
  or archive it at any time
- Add participants
- Add a "classic" expense: who paid, for whom, split mode (EQUAL / SHARES / EXACT)
- **Scan a receipt**:
  - take a picture or pick from gallery
  - each line item is detected (label + price + quantity), still editable
  - tap the avatars of the people who consumed each item → the line total is
    split equally between them
  - if ML Kit gets confused (some receipts are nasty — see `app/docs/bug-receipts/`),
    a single tap on **"Retry with AI"** sends the same image to Gemini and
    re-parses the result
- Real-time balances + minimal repayment plan (greedy settlement). Tap
  **"Remboursé"** on a suggested settlement to record it as a flagged
  expense — balances rebalance and the suggestion disappears
- **Multi-device sync, end-to-end encrypted**: a Cloudflare Worker
  relays AES-256-GCM-encrypted CRDT ops between devices that share an
  invitation QR code. The key never leaves your phones; the Worker only
  ever sees ciphertext. Foreground screens poll every 10 s by default
  (toggleable in Settings)
- **Invite another device** via a QR code (`fairshare://join?...`) that
  carries the encryption key + a signed seed of the current event state
- **Web companion (PWA)** in `webapp/` — same wire format, same
  end-to-end encryption, installable on iOS/Android browsers. Parity
  includes per-category Stats, receipt assignment, settle-from-balances,
  archive, and opt-in Web Push notifications (silent push triggers a
  background sync without a visible banner when the tab is focused)

## Architecture

```
com.fairshare
├── domain/          # Pure models, repository interfaces, use cases
├── data/            # Room (entities/DAOs/DB), repository impls, OCR engines
│   ├── ocr/             # MlKitReceiptParser + GeminiReceiptParser
│   ├── sync/            # CRDT op log, AES-GCM crypto, Cloudflare Worker client
│   └── invitation/      # QR-code invitation codec (join flow only)
├── di/              # Hilt modules (incl. @MlKit / @Gemini qualifiers)
└── presentation/    # Compose + ViewModels + navigation + theme
    ├── events/          # Event list & creation
    ├── eventdetail/     # Expenses / Balances / Participants + rename
    ├── expense/         # Classic expense creation
    ├── receipt/         # Receipt scan + per-item assignment
    ├── invite/          # Show an invitation QR code (host device)
    ├── join/            # Accept a scanned invitation (joining device)
    ├── scan/            # Camera-based QR scanner
    └── settings/        # Gemini key, cloud Worker URL, auto-refresh toggle
```

### Key use cases

- `ComputeSharesUseCase`: split an amount (EQUAL / SHARES / EXACT) with
  rounding correction so the sum lands exactly on the original amount.
- `ComputeBalancesUseCase`: net balances + minimal repayment plan (greedy).
- `AssignReceiptItemsUseCase`: turns `ReceiptItem`s (article → assigned
  participants) into aggregated `ExpenseShare`s.
- `ExpandReceiptQuantitiesUseCase`: when an item has `quantity > 1` and a
  unit price, expands it into N individual lines so each can be assigned
  independently.

### Multi-device sync (E2E encrypted)

Each event owns a randomly-generated 256-bit AES key, stored locally in
its `EventEntity`. Every local mutation (expense added, participant
renamed, event archived, …) is recorded as an immutable CRDT op
(`OpPayload.*`) in a per-event log with stable UUIDs. The op is then
applied locally **and** AES-256-GCM-encrypted with the event key,
signed with HMAC, and pushed to a tiny stateless **Cloudflare Worker**
(`https://fairshare-sync.ghostwan.workers.dev`) that just stores the
opaque blob keyed by `(eventId, opId)`. Other devices that hold the
same key pull, decrypt, deduplicate by `opId`, and apply via the same
LWW-snapshot pipeline. Conflict resolution is last-writer-wins per
entity snapshot, with a Lamport-style logical clock.

Pairing two devices is one tap: the host device shows an invitation QR
code (`fairshare://join?event=…&key=…&seed=…&sig=…`) containing the
event id, the AES key, an encrypted seed of the current snapshot and an
HMAC signature. The joining device scans it, accepts the join, and
both devices stay in sync going forward via the Worker. Foreground
screens poll every 10 s by default; this can be disabled in Settings
for users who prefer manual pull-to-refresh.

Push delivery (FCM for Android, Web Push / VAPID for the PWA) is
handled by the same Worker and fan-outs to every paired device on each
accepted op batch, so foreground screens get the update in seconds
without polling. See `webapp/README.md` and `worker/README.md` for the
companion clients.



`ReceiptParser` is an interface with two implementations, both exposed through
Hilt qualifiers (`@MlKit` and `@Gemini`):

**`MlKitReceiptParser`** — on-device, offline, free, the default.
The hard part isn't reading the text (ML Kit does that well); it's
**re-assembling rows from raw element bounding boxes**, because ML Kit groups
text into Block → Line → Element using its own heuristics that rarely match
the receipt's logical structure. Pipeline:

1. Collect every OCR element with its (cx, cy, height, text)
2. `splitStuckPriceTokens` — split tokens like `BRUT7,50` into `[BRUT, 7,50]`
3. `dropDuplicatePriceColumns` — receipts often print prices in two columns
   (unit + total, or label-row + orphan unit-price row). Cluster prices by
   x, score each cluster by label-row alignment, keep the one that wins
4. Anchor-based row clustering on y (tolerance = `medianHeight × 0.6`)
5. `mergeMultiLineLabels` — for receipts that spread an item across 3
   physical lines (label part 1 / label part 2 + price / SKU code), up-walk
   label-only rows into the price row and drop SKU-like rows below
6. `rowToItems` — sort labels by cy-bands then cx, extract quantity
   (`1x`, `1 x`, `1*`, `4 ITEM`, …), filter currency symbols, strip trailing
   single-letter tax codes, drop noise lines (TOTAL, TVA, CB, ESPÈCES, …)

Real-world bugs each get a dedicated regression test with a real OCR dump:
see `app/docs/bug-receipts/` and `app/src/test/resources/receipts/*.log`.
The full JVM test suite (OCR parser + crypto + invitation codec + Worker
transport) currently holds **89 green tests**.

**`GeminiReceiptParser`** — AI fallback. Sends the receipt image (base64
JPEG) with a structured prompt asking for `{ "items": [{ "label", "priceCents",
"quantity" }] }`. Used when ML Kit's output is visibly wrong and the user
taps "Retry with AI". The API key is read from `local.properties`
(`gemini.api.key=…`) at build time as a default, then overridable at runtime
through the Settings screen (stored in DataStore).

## Build

```bash
./gradlew assembleDebug
./run.sh debug         # build + install + launch on connected device
./gradlew :app:testDebugUnitTest
```

Requirements:
- JDK 17
- Android SDK 34
- A `local.properties` with at least the Android SDK path. To enable the
  Gemini fallback at build time without going through Settings, add:
  ```
  gemini.api.key=AIza...
  gemini.model=gemini-2.5-flash
  ```

## Helper scripts

- `run.sh debug` — assemble + install + launch + tail logcat
- `screenshot.sh [out.png]` — pull a device screenshot
- OCR diagnostic: `adb logcat -d -s 'ReceiptOCR:*'` dumps every OCR element
  as `text|cx|cy|height` — that's literally the format consumed by the
  regression fixtures in `app/src/test/resources/receipts/`.

---

## Side note: AI coding agent comparison

This project doubled as a real benchmark of two AI coding agents on a
non-trivial, geometry-heavy problem: making the receipt OCR pipeline robust
against four different real-world receipts (`bug-01` … `bug-04`).

- **GPT 5.5** was given the same OCR dumps and the same fixtures.
  It never managed to produce a working parser: it would patch one fixture
  and silently regress the others, kept hardcoding magic numbers tied to a
  single layout, and could not converge on a heuristic that survived all four
  receipts simultaneously.
- **Claude Opus 4.7** got all four fixtures green (23 JVM tests) with a
  single, coherent pipeline (geometric row reconstruction + label-alignment
  scoring + multi-line merge + SKU drop), and produced a regression test for
  every bug along with documented fixtures (`bug.png`, `ocr.log`,
  `expected.md`, before/after screenshots).

For the record: this README, the OCR pipeline (`MlKitReceiptParser` + tests),
the Gemini fallback integration, and the bug-receipts workflow were all
implemented by Claude Opus 4.7. The author of every commit on the `claude`
branch is the project owner (ghostwan) — agents only ran on his behalf.
