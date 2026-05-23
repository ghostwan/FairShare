# FairShare Android

Application Android native en Kotlin + Jetpack Compose, inspiree de Tricount, pour repartir les depenses d'un voyage ou d'un evenement.

Fonction principale: un ticket de caisse peut etre transforme en lignes de repas, puis chaque plat ou boisson est assigne aux participants qui l'ont consomme. Le cout est alors reparti automatiquement et integre au bilan.

## Ouvrir le projet

1. Ouvrir ce dossier dans Android Studio.
2. Laisser Android Studio synchroniser Gradle.
3. Lancer la configuration `app` sur un emulateur ou un telephone Android.

## Scripts

Le script `scripts/android.sh` compile, installe ou deploie l'application.

```bash
scripts/android.sh compile debug
scripts/android.sh install debug
scripts/android.sh deploy debug
scripts/android.sh compile release
```

Pour installer ou deployer une release, Android exige un APK signe. Fournir les variables suivantes:

```bash
FAIRSHARE_KEYSTORE=/path/release.jks \
FAIRSHARE_KEYSTORE_PASSWORD=... \
FAIRSHARE_KEY_ALIAS=... \
FAIRSHARE_KEY_PASSWORD=... \
scripts/android.sh deploy release
```

## Contenu actuel

- Architecture simple par couches: `domain`, `data`, `presentation`.
- Participants d'exemple.
- Depenses classiques partagees entre tous.
- Ticket de restaurant simule.
- Assignation de chaque ligne du ticket a une ou plusieurs personnes.
- Synthese des soldes et dettes a regler.
