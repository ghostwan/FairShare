# bug-04 — Multi-line labels with inline SKU codes

**Source** : ticket « À L'Aise Breizh Dinan », 15/05/2026, 4 articles, total 89,00 €.

## Symptômes

Avec la version du parser pré-fix, on obtient **total 64,00 € au lieu de 89,00 €**
et **3 items au lieu de 4**, dont 2 avec un label corrompu :

| Label parsé        | Prix    | Réalité attendue                            |
|--------------------|---------|---------------------------------------------|
| METEO MARINE 41/45 | 12,00 € | CHAUSSETTES POIZ. METEO MARINE 41/45 — 12 € |
| TU                 | 23,00 € | FOULARD JANNY MARINE TU — 23 €              |
| WHITEL €           | 29,00 € | T-SHIRT NATURE OFF WHITE L — 29 €           |
| *(manquant)*       | —       | CASQUETTE GONO MARINE TU — 25 €             |

(voir `result-before-fix.png` ; le fallback Gemini sort le bon résultat,
voir `result-after-ai.png`.)

## Diagnostic

Chaque article du ticket s'étale sur **3 lignes visuelles** :

```
       CHAUSSETTES POIZ.                              ← label part 1 (label only)
1      METEO MARINE 41/45             12.00 €         ← label part 2 + PRIX (col droite)
       CHAUSSE22POIZ 12.00 €                          ← code SKU + prix dupliqué (col gauche)
```

Géométrie du dump OCR (extrait, voir `ocr.log`) :

```
cx ≈ 1107  cy ≈ 1644   CHAUSSETTES POIZ
cx ≈ 951   cy ≈ 1731   METEO MARINE 41/45
cx ≈ 2143  cy ≈ 1778   12.00 €               ← colonne « réelle » (droite)
cx ≈ 1144  cy ≈ 1831   CHAUSSE22PO1Z
cx ≈ 1627  cy ≈ 1849   12.00 €               ← duplicata inline (gauche)
```

- **Deux colonnes de prix** : gauche (cx ≈ 1400-1630, taille 4, collée aux SKU)
  et droite (cx ≈ 2120-2210, taille 6, incluant Total/Cartes). Asymétrique
  → l'heuristique `dropDuplicatePriceColumns` garde déjà correctement la
  colonne de droite (bug-02 fix).
- **Mais** : le row-clustering produit **3 rows distinctes par article**
  parce que les gaps verticaux intra-article (~ 87-100 px) dépassent la
  tolérance `medianHeight × 0,6` (~ 48 px).
- Le prix de droite tombe sur la ligne du milieu (cy ≈ 1778 ≈ anchor 1731),
  donc seul « METEO MARINE 41/45 » est associé. La ligne « CHAUSSETTES POIZ »
  au-dessus est orpheline, la ligne SKU en-dessous aussi.
- Pour CASQUETTE GONO / MARINE TU / CASHE19001, le prix 25,00 (cy=2817)
  tombe juste hors tolérance de MARINETU (anchor 2773, gap 44) selon la
  valeur exacte de `medianHeight` → item **perdu**.

## Fix

Quatre changements ciblés dans `MlKitReceiptParser` :

1. **Post-pass `mergeMultiLineLabels`** entre le row-clustering et
   `rowToItems` :
   - Pour chaque row contenant un prix, remonter les rows label-only
     immédiatement au-dessus (gap < `medianHeight × 1,3`, soit ~ 102 px sur
     ce ticket) et fusionner leurs tokens dans la row du prix. La row du
     dessus doit contenir un vrai label (≥ 1 token de longueur ≥ 2 avec
     au moins une lettre) — sinon on s'arrête, ce qui évite d'absorber
     des rows orphelines avec un seul « € » misread (bug-03).
   - Redescendre dans la même limite et **drop** les rows qui ressemblent
     à un code SKU (après strip des symboles monétaires : un seul token
     alphanumérique, longueur ≥ 6, mélange lettres + chiffres, ≥ 2 chiffres).
   - Discrimine bien intra-article (67-100 px) vs inter-article (157 px)
     sur ce ticket sans casser bug-01 / 02 / 03 (items sur 1 row).

2. **Tri des labels par bandes de cy** dans `rowToItems` : on bucketise
   les cy en bandes de `maxHeight × 0,6` puis on trie par `(band, cx)`.
   Préserve l'ordre top-to-bottom pour les labels multi-lignes
   réassemblés, sans scrambler les rows mono-ligne dont les tokens ont
   ~5 px de jitter en cy (ex. tax-code « D » à cy 827 vs label à cy 831
   sur bug-02 — un tri (cy, cx) strict mettait « D » en tête et cassait
   QTY_LEADING).

3. **Filtre des symboles monétaires** isolés (`€`, `$`, `£`, `EUR`,
   `USD`) hors du label → fini les « WHITEL € » et « MARINETU € ».

4. **Word boundary sur le pattern NOISE `\bn[°o]\s*\d`** (marker
   « n° 1 » de ticket) : sans le `\b`, il matchait par erreur « NO 1 »
   à l'intérieur de « GO**NO 1** MARINETU » (CASQUETTE GONO 1 MARINETU)
   et faisait disparaître l'item à 25 €.

## Résultat après fix

4 items, total 89,00 €, labels propres (voir `result.png`) :

| Label                                       | Prix    | Qty |
|---------------------------------------------|---------|-----|
| CHAUSSETTES POIZ 1 1 METEO MARINE 41/45     | 12,00 € | 1   |
| FOULARD JANNY MARINE TU                     | 23,00 € | 1   |
| T-S!IRT NATURE OFF 1 WHITEL                 | 29,00 € | 1   |
| CASQUETTE GONO 1 MARINETU                   | 25,00 € | 1   |

Les « 1 » qui restent dans certains labels viennent des markers de
quantité de la colonne gauche (qty=1 imprimée à côté du label). Pas
critique fonctionnellement : la quantité reste à 1 et le prix unitaire
× quantité = prix ligne. L'utilisateur peut nettoyer dans l'écran
d'édition. Les misreads OCR (« T-S!IRT » → « T-SHIRT », « WHITEL » →
« WHITE L ») ne sont pas le job du parser non plus.
