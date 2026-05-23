package com.fairshare.presentation.navigation

sealed class Route(val path: String) {
    data object Events : Route("events")
    data object EventDetail : Route("event/{eventId}") {
        fun build(eventId: Long) = "event/$eventId"
    }
    data object AddExpense : Route("event/{eventId}/expense/new") {
        fun build(eventId: Long) = "event/$eventId/expense/new"
    }
    data object ScanReceipt : Route("event/{eventId}/expense/scan") {
        fun build(eventId: Long) = "event/$eventId/expense/scan"
    }

    companion object {
        const val ARG_EVENT_ID = "eventId"
    }
}
