# FairShare

Application Android pour répartir les dépenses lors d'un voyage ou d'un événement,
avec une feature unique : **scan de ticket de caisse + assignation par article**.

## Stack

- Kotlin + Jetpack Compose (Material 3)
- Architecture Clean : `domain` / `data` / `presentation`
- Hilt (DI), Room (persistance), Coroutines / Flow
- ML Kit Text Recognition (OCR ticket de caisse)
- CameraX / `PickVisualMedia` (capture / galerie)

## Fonctionnalités

- Créer un événement (voyage, repas, coloc…) avec sa devise
- Ajouter des participants
- Saisir une dépense (qui a payé, pour qui — split équitable)
- **Scanner un ticket** : chaque article est détecté, modifiable, et tu cliques
  sur les participants concernés ➜ le coût de chaque article est réparti
  équitablement entre ceux qui l'ont consommé.
- Soldes en temps réel + plan de remboursement minimal (greedy settlement)

## Architecture

```
com.fairshare
├── domain/          # Modèles purs, interfaces repository, use cases
├── data/            # Room (entities/DAOs/DB), repository impls, OCR ML Kit
├── di/              # Modules Hilt
└── presentation/    # Compose + ViewModels + navigation + thème
    ├── events/          # Liste & création d'événements
    ├── eventdetail/     # Dépenses / Soldes / Participants
    ├── expense/         # Création d'une dépense classique
    └── receipt/         # Scan ticket + assignation par article
```

### Use cases clés

- `ComputeSharesUseCase` : répartit un montant (EQUAL / SHARES / EXACT) avec
  correction d'arrondi pour que la somme retombe juste.
- `ComputeBalancesUseCase` : calcule les soldes nets + un plan de
  remboursement minimal (greedy).
- `AssignReceiptItemsUseCase` : transforme les `ReceiptItem` (article →
  participants assignés) en `ExpenseShare` agrégés.

### OCR ticket — `MlKitReceiptParser`

Heuristique : chaque ligne contenant un montant en fin (regex
`(-?\d+[.,]\d{2})`) devient un article. Les libellés bruit (TOTAL, TVA, CB,
ESPÈCES…) sont filtrés. Tous les articles restent éditables dans l'UI.

## Build

```bash
# Génère le wrapper la première fois (nécessite Gradle local OU Android Studio)
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

Pré-requis :
- JDK 17
- Android SDK 34
- Ouvrir dans **Android Studio Koala+** pour profiter du wrapper / sync auto.
