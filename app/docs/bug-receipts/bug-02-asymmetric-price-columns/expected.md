# Bug-02 — Crêperie de la Poste

OCR dump captured from the gallery import. Source: `bug.png`.

## Expected items (printed on the ticket)

| Qty | Label                | Unit   | Line total |
|-----|----------------------|--------|------------|
| 2   | CRE MARRON           | 5,50   | 11,00      |
| 1   | FLAMB MARRON RHUM    |        | 9,30       |
| 1   | BEURRE SUCRE         |        | 3,70       |
| 1   | POMME SALIDOU        |        | 6,90       |
| 1   | CHO MAISON           |        | 5,80       |
| 4   | CHOCOLAT             | 3,50   | 14,00      |
| 1   | INFUSION             |        | 3,50       |
| 1   | CAFE ALLONGE         |        | 2,10       |
| —   | **TOTAL**            |        | **56,30**  |

## OCR quirks observed

- **Asymmetric two-column prices** — the "unit price" column only prints when
  qty > 1, so it contains 2 prices vs. 9 in the totals column. The previous
  `dropDuplicatePriceColumns` rule ("always drop the right column") wiped out
  7 items out of 8 and gave a parsed total of 9,00 €.
- **Trailing tax codes** — every line ends in a single letter `D` or `C`
  (TVA category). Now stripped from the label.
- **Lost `x` separator** — for `1 x FLAMB MARRON RHUM`, OCR didn't emit the
  `x`, so qty extraction needs a "bare digit + label" fallback.
- **2,10 read as 2,40** — CAFE ALLONGE digit confusion. Cannot be fixed in
  the parser, user edits in the UI. Final parsed total: 56,60 € instead of
  56,30 €.

## Fix landed in commit (see git log)

1. `dropDuplicatePriceColumns` now partitions prices into cx-clusters,
   drops outlier singletons (e.g. "PAR COUVERT 19,75"), and:
   - keeps the LEFT-most cluster when remaining columns are symmetric
     (≥ 50 % ratio — bug-01 case)
   - keeps the BIGGEST cluster otherwise (bug-02 case)
2. Label cleanup strips trailing single-letter tax codes (`… D`, `… C`).
3. New `QTY_BARE` regex extracts the leading digit when OCR drops the `x`.

## Files

- `bug.png` — original photo (from user's other clone)
- `ocr.log` — dump captured BEFORE the fix (95 tokens)
- `result.png` — broken result on device (2 items, 9,00 €)
- `ocr-after-fix.log` — dump AFTER the fix (still 95 tokens, parser changed)
- `result-after-fix.png` — fixed result on device (8 items, 56,60 €)
