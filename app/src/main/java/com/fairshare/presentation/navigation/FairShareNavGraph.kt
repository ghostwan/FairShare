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
import com.fairshare.presentation.receipt.ScanReceiptScreen

@Composable
fun FairShareNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Route.Events.path) {
        composable(Route.Events.path) {
            EventsScreen(onOpenEvent = { id -> nav.navigate(Route.EventDetail.build(id)) })
        }
        composable(
            Route.EventDetail.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.LongType }),
        ) {
            EventDetailScreen(
                onBack = { nav.popBackStack() },
                onAddExpense = { id -> nav.navigate(Route.AddExpense.build(id)) },
                onScanReceipt = { id -> nav.navigate(Route.ScanReceipt.build(id)) },
            )
        }
        composable(
            Route.AddExpense.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.LongType }),
        ) {
            AddExpenseScreen(onDone = { nav.popBackStack() })
        }
        composable(
            Route.ScanReceipt.path,
            arguments = listOf(navArgument(Route.ARG_EVENT_ID) { type = NavType.LongType }),
        ) {
            ScanReceiptScreen(onDone = { nav.popBackStack() })
        }
    }
}
