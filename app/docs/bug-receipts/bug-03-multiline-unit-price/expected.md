# Bug-03 — Côte Rivière (Pont-Aven)

Multi-line item layout: each article spans **two rows**.
- Row 1: `<qty> <label>` on the left, `<line total> €` on the right.
- Row 2 (below the label, indented): `<unit price> €` standalone.

## Expected items

| Qty | Label                          | Unit  | Line total |
|-----|--------------------------------|-------|------------|
| 2   | Kir vin blanc 12cl             | 4,00  | 8,00       |
| 2   | Limonade 33cl                  | 4,00  | 8,00       |
| 1   | Bouteille cidre 75cl           | 14,00 | 14,00      |
| 2   | Rillettes de poisson maison    | 9,00  | 18,00      |
| 4   | plat du jour                   | 13,00 | 52,00      |
| 1   | plat du jour                   | 16,00 | 16,00      |
| 1   | Croque monsieur                | 12,00 | 12,00      |
| 1   | Dessert du jour                | 8,00  | 8,00       |
| —   | **Total**                      |       | **136,00** |

## OCR quirks

- **Two price columns of equal size (8 vs 8)**, but only the right column is
  on the same row as the labels. The left column is on orphan rows below the
  labels, each accompanied only by a "€" symbol token.
- The bug-02 heuristic (symmetric 8 vs 8 → keep left) would keep the orphan
  unit-price column → row clustering finds NO label/price pair → **0 items
  detected** (parsed total 0,00 €).
- "1 Dessert du jour" — OCR lost the leading "1", so the line ends up with
  qty=1 by default. Acceptable.
- TOTAL "136,00 €" is OCR-split into 3 tokens ("136,", "00", "€"), none of
  which match the price regex → naturally excluded.

## Fix landed

`MlKitReceiptParser.dropDuplicatePriceColumns` gains a new **label alignment**
stage between outlier filtering and the size-based decision:

1. Build "label rows" from non-price tokens — rows that contain ≥ 1 token of
   length ≥ 2 with at least one letter (filters out lone "€"/"*"/digit rows).
2. For each remaining price cluster, compute the fraction of its prices whose
   cy is within `rowTolerance` of a label row's cy.
3. If clusters differ sharply (best − worst ≥ 0.5) → keep the best-aligned
   cluster. Bug-03: right column scores 1.0, left scores 0.0 → keep right.
4. Otherwise (all clusters align) → fall back to size-based decision used by
   bug-01 (symmetric → leftmost) and bug-02 (asymmetric → biggest).

## Files

- `bug.png` — original photo
- `ocr.log` — dump captured BEFORE the fix (93 tokens, 0 items parsed)
- `result.png` — broken result (0 items, no total)
- `ocr-after-fix.log` — dump AFTER the fix
- `result-after-fix.png` — fixed result (8 items, 136,00 €)
