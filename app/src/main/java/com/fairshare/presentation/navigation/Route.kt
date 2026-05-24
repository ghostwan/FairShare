package com.fairshare.presentation.navigation

sealed class Route(val path: String) {
    data object Events : Route("events")
    data object Settings : Route("settings")
    data object EventDetail : Route("event/{eventId}") {
        fun build(eventId: String) = "event/$eventId"
    }
    data object AddExpense : Route("event/{eventId}/expense/new") {
        fun build(eventId: String) = "event/$eventId/expense/new"
    }
    data object EditExpense : Route("event/{eventId}/expense/{expenseId}/edit") {
        fun build(eventId: String, expenseId: String) = "event/$eventId/expense/$expenseId/edit"
    }
    data object ScanReceipt : Route("event/{eventId}/expense/scan") {
        fun build(eventId: String) = "event/$eventId/expense/scan"
    }
    data object ShareChanges : Route("event/{eventId}/share") {
        fun build(eventId: String) = "event/$eventId/share"
    }
    /**
     * Receives a `fairshare://sync` link to import. The link is
     * base64url-encoded into the path so it travels through Compose
     * Navigation safely (raw URLs contain `?` / `&` / `=`).
     */
    data object ApplyChanges : Route("apply/{deepLink}") {
        fun build(deepLink: String) = "apply/$deepLink"
    }
    /** Camera-based QR scanner used to bootstrap a join from another device. */
    data object ScanInvitation : Route("scan-invitation")

    companion object {
        const val ARG_EVENT_ID = "eventId"
        const val ARG_EXPENSE_ID = "expenseId"
        const val ARG_DEEP_LINK = "deepLink"
    }
}
