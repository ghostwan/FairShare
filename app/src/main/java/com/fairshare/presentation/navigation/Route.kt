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

    companion object {
        const val ARG_EVENT_ID = "eventId"
        const val ARG_EXPENSE_ID = "expenseId"
    }
}
