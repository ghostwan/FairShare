package com.fairshare.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fairshare.presentation.eventdetail.EventDetailScreen
import com.fairshare.presentation.events.EventsScreen
import com.fairshare.presentation.expense.AddExpenseScreen
import com.fairshare.presentation.expense.EditExpenseRouter
import com.fairshare.presentation.receipt.ScanReceiptScreen
import com.fairshare.presentation.settings.SettingsScreen
import com.fairshare.presentation.share.ShareChangesScreen

@Composable
fun FairShareNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Route.Events.path) {
        composable(Route.Events.path) {
            EventsScreen(
                onOpenEvent = { id -> nav.navigate(Route.EventDetail.build(id)) },
                onOpenSettings = { nav.navigate(Route.Settings.path) },
            )
        }
        composable(Route.Settings.path) {
            SettingsScreen(onBack = { nav.popBackStack() })
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
                onShareChanges = { id -> nav.navigate(Route.ShareChanges.build(id)) },
            )
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
            Route.ShareChanges.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.StringType }),
        ) {
            ShareChangesScreen(onBack = { nav.popBackStack() })
        }
    }
}
