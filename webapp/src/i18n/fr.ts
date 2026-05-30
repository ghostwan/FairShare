/**
 * French strings, the only locale the app currently ships. Centralised
 * so adding `en` later is just a matter of duplicating the object and
 * wrapping accesses with a hook — the keys themselves stay stable.
 */
export const fr = {
  app: {
    title: "FairShare",
    tagline: "Partage de dépenses simple",
  },
  nav: {
    events: "Événements",
    settings: "Réglages",
    back: "Retour",
  },
  events: {
    empty: "Aucun événement pour l'instant.",
    create: "Nouvel événement",
    createPrompt: "Nom de l'événement",
    descriptionOptional: "Description (optionnel)",
    join: "Rejoindre via QR-code",
    archived: "Archivés",
    archive: "Archiver",
    unarchive: "Désarchiver",
    deleteLocal: "Supprimer localement",
    confirmDeleteLocal:
      "Supprimer cet événement seulement sur cet appareil ? Les autres devices garderont leurs données.",
    syncNow: "Synchroniser maintenant",
    lastSync: "Dernière synchro",
    invite: "Inviter un appareil",
    inviteHint:
      "Affichez ce QR-code à votre ami·e pour ajouter son téléphone ou navigateur à cet événement.",
    inviteCopyLink: "Copier le lien",
    inviteCopied: "Lien copié",
  },
  tabs: {
    expenses: "Dépenses",
    balances: "Balances",
    participants: "Participants",
  },
  stats: {
    title: "Statistiques",
    total: "Total",
    uncategorized: "Sans catégorie",
    empty: "Aucune dépense à statistiquer.",
  },
  eventSettings: {
    title: "Réglages de l'événement",
    categoriesSection: "Catégories",
  },
  expenses: {
    empty: "Aucune dépense.",
    add: "Ajouter une dépense",
    edit: "Modifier la dépense",
    title: "Titre",
    amount: "Montant (€)",
    payer: "Payé par",
    date: "Date",
    category: "Catégorie",
    categoryNone: "Aucune",
    split: "Répartition",
    splitEqual: "Parts égales",
    save: "Enregistrer",
    delete: "Supprimer",
    confirmDelete: "Supprimer cette dépense ?",
    isSettlement: "Remboursement",
  },
  balances: {
    empty: "Tout est équilibré ✓",
    owes: "doit",
    to: "à",
    suggestion: "Remboursements suggérés",
    settle: "Marquer comme remboursé",
    creditor: "à recevoir",
    debtor: "à payer",
  },
  participants: {
    empty: "Aucun participant.",
    add: "Ajouter un participant",
    name: "Nom",
    rename: "Renommer",
    remove: "Retirer",
    removeConfirm: "Retirer ce participant ?",
    totalPaid: "Total payé",
  },
  categories: {
    empty: "Aucune catégorie personnalisée.",
    add: "Nouvelle catégorie",
    name: "Nom",
    emoji: "Emoji",
    color: "Couleur",
    delete: "Supprimer",
    defaultBadge: "(par défaut)",
  },
  join: {
    heading: "Rejoindre un événement",
    scan: "Scanner le QR-code",
    paste: "Coller un lien d'invitation",
    pasteHint:
      "L'invitation ressemble à https://fairshare-web-bdg.pages.dev/join?event=…",
    submit: "Rejoindre",
    invalid: "Lien d'invitation invalide.",
    success: "Bienvenue dans l'événement",
  },
  settings: {
    cloudBaseUrl: "URL du Worker de synchro",
    geminiKey: "Clé API Gemini",
    geminiKeyHint: "Optionnel — débloque le scan de tickets via IA",
    geminiModel: "Modèle Gemini",
    autoRefresh: "Synchro automatique au retour sur l'app",
    pushEnable: "Notifications push",
    pushEnableDescription:
      "Recevoir une notification quand une autre personne ajoute ou modifie une dépense, dans tous tes événements.",
    pushUnsupported:
      "Ton navigateur ne gère pas les notifications push (Safari iOS < 16.4 par exemple). Tu peux toujours rafraîchir manuellement.",
    pushPermissionDenied:
      "Tu as refusé la permission de notifications. Active-la dans les réglages du navigateur, puis ré-essaie.",
    pushUnavailable:
      "Les notifications push ne sont pas activées côté serveur pour cette instance.",
    install: "Installer sur l'écran d'accueil",
    installHint:
      "Sur iPhone/iPad, ouvrez le menu Partager dans Safari puis « Sur l'écran d'accueil ».",
    save: "Enregistrer",
    saved: "Réglages enregistrés",
    shareGeminiKey: "Partager via QR",
    scanGeminiKey: "Scanner un QR",
    geminiQrTitle: "Partager la clé Gemini",
    geminiQrHint:
      "Scannez ce QR depuis l'app FairShare sur un autre appareil pour y copier votre clé Gemini. Ne le partagez qu'avec vos appareils.",
    geminiKeyImported: "Clé Gemini importée",
    geminiKeyImportFailed: "QR invalide",
    geminiKeyMissing: "Renseignez une clé Gemini avant de la partager.",
    geminiHelpTitle: "Comment obtenir une clé Gemini ?",
    geminiHelpIntro:
      "La clé est gratuite dans la limite du quota Google AI Studio (≈ 15 requêtes/min sur Gemini 2.5 Flash, largement suffisant pour scanner des tickets).",
    geminiHelpStep1:
      "Ouvrez Google AI Studio (un compte Google suffit, aucune carte bancaire requise).",
    geminiHelpStep2:
      "Cliquez sur « Create API key » puis sélectionnez ou créez un projet Google Cloud.",
    geminiHelpStep3:
      "Copiez la clé générée (elle commence par « AIza… »).",
    geminiHelpStep4:
      "Collez-la dans le champ « Clé API Gemini » ci-dessus, puis Enregistrer.",
    geminiHelpOpenStudio: "Ouvrir Google AI Studio",
    about: "À propos",
    version: "Version",
  },
  receipt: {
    scan: "Scanner un ticket",
    needsKey:
      "Configurez votre clé Gemini dans les Réglages pour utiliser le scan.",
    snap: "Prendre une photo",
    chooseFile: "Choisir une image",
    analyzing: "Analyse en cours…",
    failed: "Le scan a échoué, essayez à nouveau ou saisissez manuellement.",
  },
  qr: {
    requestCamera: "Autoriser la caméra",
    cameraDenied:
      "Caméra refusée. Vous pouvez aussi coller le lien d'invitation manuellement.",
    point: "Pointez l'appareil vers le QR-code",
  },
  common: {
    cancel: "Annuler",
    confirm: "Confirmer",
    close: "Fermer",
    loading: "Chargement…",
    error: "Erreur",
    retry: "Réessayer",
    today: "Aujourd'hui",
    yesterday: "Hier",
  },
} as const;

export type Strings = typeof fr;

export function t(): Strings {
  return fr;
}
