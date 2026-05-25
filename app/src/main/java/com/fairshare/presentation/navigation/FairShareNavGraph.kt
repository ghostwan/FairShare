package com.fairshare.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fairshare.presentation.archived.ArchivedEventsScreen
import com.fairshare.presentation.eventdetail.EventDetailScreen
import com.fairshare.presentation.events.EventsScreen
import com.fairshare.presentation.expense.AddExpenseScreen
import com.fairshare.presentation.expense.EditExpenseRouter
import com.fairshare.presentation.invite.InviteScreen
import com.fairshare.presentation.join.JoinEventScreen
import com.fairshare.presentation.receipt.ScanReceiptScreen
import com.fairshare.presentation.scan.ScanInvitationScreen
import com.fairshare.presentation.settings.ScanGeminiKeyScreen
import com.fairshare.presentation.settings.SettingsScreen
import com.fairshare.presentation.eventsettings.EventSettingsScreen
import java.util.Base64

@Composable
fun FairShareNavGraph(
    /**
     * Deep link captured by [com.fairshare.MainActivity]
     * (intent-filter on `fairshare://join`). When non-null on
     * recomposition, the graph navigates to [Route.JoinEvent] and
     * notifies the host so the same link isn't re-consumed.
     */
    deepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()

    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(link.toByteArray(Charsets.UTF_8))
        nav.navigate(Route.JoinEvent.build(encoded))
        onDeepLinkConsumed()
    }

    NavHost(navController = nav, startDestination = Route.Events.path) {
        composable(Route.Events.path) {
            EventsScreen(
                onOpenEvent = { id -> nav.navigate(Route.EventDetail.build(id)) },
                onOpenSettings = { nav.navigate(Route.Settings.path) },
                onOpenArchived = { nav.navigate(Route.Archived.path) },
                onScanInvitation = { nav.navigate(Route.ScanInvitation.path) },
            )
        }
        composable(Route.Archived.path) {
            ArchivedEventsScreen(onBack = { nav.popBackStack() })
        }
        composable(Route.Settings.path) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onScanGeminiKey = { nav.navigate(Route.ScanGeminiKey.path) },
            )
        }
        composable(Route.ScanGeminiKey.path) {
            ScanGeminiKeyScreen(onDone = { nav.popBackStack() })
        }
        composable(
            Route.EventDetail.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.StringType }),
        ) {
            EventDetailScreen(
                onBack = { nav.popBackStack() },
                onAddExpense = { id -> nav.navigate(Route.AddExpense.build(id)) },
                onEditExpense = { eventId, expenseId ->
                    nav.navigate(Route.EditExpense.build(eventId, expenseId))
                },
                onScanReceipt = { id -> nav.navigate(Route.ScanReceipt.build(id)) },
                onInvite = { id -> nav.navigate(Route.Invite.build(id)) },
                onOpenEventSettings = { id -> nav.navigate(Route.EventSettings.build(id)) },
            )
        }
        composable(
            Route.EventSettings.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.StringType }),
        ) {
            EventSettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Route.AddExpense.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.StringType }),
        ) {
            AddExpenseScreen(onDone = { nav.popBackStack() })
        }
        composable(
            Route.EditExpense.path,
            arguments = listOf(
                navArgument(Route.ARG_EVENT_ID) { type = NavType.StringType },
                navArgument(Route.ARG_EXPENSE_ID) { type = NavType.StringType },
            ),
        ) {
            EditExpenseRouter(onDone = { nav.popBackStack() })
        }
        composable(
            Route.ScanReceipt.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.StringType }),
        ) {
            ScanReceiptScreen(onDone = { nav.popBackStack() })
        }
        composable(
            Route.Invite.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.StringType }),
        ) {
            InviteScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Route.JoinEvent.path,
            arguments = listOf(navArgument(Route.ARG_DEEP_LINK) { type = NavType.StringType }),
        ) {
            JoinEventScreen(
                onBack = { nav.popBackStack() },
                onJoined = { eventId ->
                    nav.navigate(Route.EventDetail.build(eventId)) {
                        popUpTo(Route.Events.path) { inclusive = false }
                    }
                },
            )
        }
        composable(Route.ScanInvitation.path) {
            ScanInvitationScreen(
                onBack = { nav.popBackStack() },
                onScanned = { url ->
                    val encoded = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(url.toByteArray(Charsets.UTF_8))
                    // Replace the scanner from the back stack so "back" returns to Events.
                    nav.navigate(Route.JoinEvent.build(encoded)) {
                        popUpTo(Route.ScanInvitation.path) { inclusive = true }
                    }
                },
            )
        }
    }
}
