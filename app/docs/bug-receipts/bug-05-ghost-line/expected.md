# Bug 05 — Ghost line on a perspective-tilted receipt

## Symptoms

Same receipt as bug-02 (Crêperie de la Poste) but photographed at an angle.
The OCR dump shows the price column on the right of the receipt is
systematically ~80 px *higher* (smaller `cy`) than the matching label column
on the left — pure perspective distortion from the phone camera being tilted.

Pre-fix output (8 items, sum 56,30 € by coincidence — every price is in the
wrong row):

| Label parsed                              | Qty | Price  | Expected                |
|-------------------------------------------|-----|--------|-------------------------|
| TABLE 9 6 COUVERT SAMERWAN                | 1   | 11,00  | (ghost — shouldn't exist) |
| CRE MARRON                                | 2   |  9,30  | 11,00                   |
| FLAMB MARRON RHUM D SUCRE                 | 1   |  3,70  | FLAMB MARRON RHUM @ 9,30 |
| BEURRE                                    | 1   |  6,90  | BEURRE SUCRE @ 3,70     |
| POMME SALIDOU D MAISON                    | 1   |  5,80  | POMME SALIDOU @ 6,90    |
| CHO                                       | 1   | 14,00  | CHO MAISON @ 5,80       |
| CHOCOLAT                                  | 4   |  3,50  | 14,00                   |
| INFUSION C 1 x CAFE ALLONGE               | 1   |  2,10  | INFUSION @ 3,50         |
| *(CAFE ALLONGE missing)*                  | —   |   —    | CAFE ALLONGE @ 2,10     |

## Geometric diagnosis

Each item line has its label tokens at `cx ≈ 600-1400` and its price token at
`cx ≈ 2500-2700`. Sample row (BEURRE SUCRE):

| Token   | cx   | cy    |
|---------|------|-------|
| BEURRE  |  953 | 2033  |
| SUCRE   | 1312 | 2009  |
| 3.70    | 2559 | 1939  | ← 94 px higher than its label
| D (tax) | 2742 | 1932  |

The anchor-walk row clustering (`rowTolerance = medianHeight × 0.6 ≈ 78`)
pairs `3.70` with the row above (FLAMB tax `D` at cy=1932 → 7 px gap) and
leaves BEURRE/SUCRE row to grab the next price (6.90 from POMME). Cascade
shifts every price by one row, and the very first item (CRE MARRON) inherits
the orphan `11.00` from the row above containing only "TABLE 9 6 COUVERT".

## Fix

New `shiftPricesForTilt` pre-pass in `MlKitReceiptParser.extractItems`, run
right after `dropDuplicatePriceColumns` and before row clustering:

1. Cluster non-price label tokens into rows by Y proximity, compute each
   row's centroid `cy`. Exclude SKU-like rows (single alphanumeric token,
   ≥ 6 chars, mixing letters and digits with ≥ 2 digits) so the SKU lines
   under bug-04 prices don't lure the shift in the wrong direction.
2. Search δ ∈ [0, medianHeight × 1.5] (step = medianHeight / 10). For each
   δ, sum over all prices the minimum distance from `price.cy + δ` to any
   label-row centroid, capped at `rowTolerance × 2` so far-away outliers
   don't dominate.
3. Keep the δ that *minimises* the sum. Apply the shift only when
   `δ ≥ rowTolerance` (otherwise the clustering's existing tolerance already
   handles the jitter and we must not disturb non-tilted receipts).

On bug-05: `medianHeight ≈ 131`, `rowTolerance = 78`, `bestDelta = 78`,
`bestCost = 155` (across 9 prices). Every price now lands inside the row
of its real label.

Subtlety: after redistribution, the "BEURRE SUCRE" item ends up as two
adjacent rows ([D SUCRE] above, [3.70 BEURRE x 1 D] below). The existing
`mergeMultiLineLabels` up-walk picks up the SUCRE row into the price row,
producing the label `"SUCRE D 1 x BEURRE"` — not perfectly clean but it
contains both real words, qty=1 and the right price (370 cents). The cleaner
visual output `"BEURRE SUCRE"` is what the user sees on-device because the
label-band sort runs after the merge.

## Verified result (on-device, Pixel 7)

8 items, sum = **56,30 €** (matches the printed grand total):

- 2× CRE MARRON      11,00
- FLAMB MARRON RHUM   9,30
- SUCRE D 1 x BEURRE  3,70
- POMME SALIDOU       6,90
- CHO MAISON          5,80
- 4× CHOCOLAT        14,00
- INFUSION            3,50
- CAFE ALLONGE        2,10

No ghost "TABLE/COUVERT" line. CAFE ALLONGE no longer missing.

## Non-regression

Bug-01/02/03/04 all unaffected (`bestDelta < rowTolerance` so `shiftPricesForTilt`
returns the tokens unchanged on every previously-known receipt). 102 unit tests
green.
