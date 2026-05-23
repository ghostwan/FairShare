# Bug 01 — Les premières lignes du ticket sont fusionnées en une seule

## Ticket source

Restaurant **La Perrozienne**, 13/05/2026, Table 2 (Cv : 6).
Voir `receipt.png` pour la photo originale.

## Symptôme observé

À l'issue du scan, l'écran "Articles détectés" affiche **une seule ligne**
agrégeant le libellé et/ou le prix de plusieurs articles consécutifs.
Voir `result.png` pour la capture.

## Détail des 17 articles attendus

| #  | Libellé attendu        | Quantité | Prix unitaire (€) | Total (€) |
|----|------------------------|---------:|------------------:|----------:|
| 1  | COMPLETE CHORIZO       |        1 |             10,50 |     10,50 |
| 2  | SUPP ING 3E            |        1 |              3,00 |      3,00 |
| 3  | COMPLETE CHORIZO       |        1 |             10,50 |     10,50 |
| 4  | SUPP ING 3E            |        1 |              3,00 |      3,00 |
| 5  | COMPLETE CHORIZO       |        1 |             10,50 |     10,50 |
| 6  | COMPLETE CHORIZO       |        1 |             10,50 |     10,50 |
| 7  | LA BRETONNE            |        1 |             14,00 |     14,00 |
| 8  | LA BRETONNE            |        1 |             14,00 |     14,00 |
| 9  | 50CL CIDRE BRUT        |        1 |              7,50 |      7,50 |
| 10 | C BEURRE SUCRE         |        1 |              3,50 |      3,50 |
| 11 | C BEURRE SUCRE         |        1 |              3,50 |      3,50 |
| 12 | C CREME DE MARRON      |        1 |              5,00 |      5,00 |
| 13 | C CREME DE MARRON      |        1 |              5,00 |      5,00 |
| 14 | SUPP CHANTILLY         |        1 |              2,00 |      2,00 |
| 15 | SPECIAL SPECULOS       |        1 |              9,00 |      9,00 |
| 16 | C NUTELLA              |        1 |              5,00 |      5,00 |
| 17 | EXPRESSO               |        1 |              2,00 |      2,00 |

**Sous-total attendu : 118,50 €** (= Total TTC).

## Cause racine identifiée

Le clustering des tokens en lignes dans `MlKitReceiptParser.extractItems`
utilisait une comparaison de proche en proche (`abs(t.cy - last.last().cy)
<= rowTolerance`). Sur ce ticket, la colonne de droite (prix EUR) a un
`cy` qui dérive progressivement de ~40-50 px par rapport à la colonne
de gauche (libellés), ce qui permettait à des tokens de plusieurs lignes
visuelles distinctes de "ponter" en chaîne, collapsant 5 lignes en une.

## Fix appliqué

Remplacement du clustering "single-link" par un clustering ancré sur le
`cy` du **premier** token de la rangée en cours
(`MlKitReceiptParser.kt`, voir test de régression
`MlKitReceiptParserFixtureTest`).

## Fichiers du dossier

- `receipt.png` — photo originale du ticket
- `ocr.log` — dump brut ML Kit (tag `ReceiptOCR`, 143 tokens)
- `result.png` — capture du bug avant fix
- `expected.md` — ce fichier
