package app.ledgerly.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ledgerly.ui.ask.AskScreen
import app.ledgerly.ui.budgets.BudgetsScreen
import app.ledgerly.ui.home.HomeScreen
import app.ledgerly.ui.insights.InsightsScreen
import app.ledgerly.ui.more.MoreScreen
import app.ledgerly.ui.quickadd.QuickAddScreen
import app.ledgerly.ui.review.IgnoredScreen
import app.ledgerly.ui.review.ReviewScreen
import app.ledgerly.ui.settings.SettingsScreen
import app.ledgerly.ui.subscriptions.SubscriptionsScreen
import app.ledgerly.ui.transactions.TransactionDetailScreen
import app.ledgerly.ui.transactions.TransactionsScreen
import app.ledgerly.ui.transactions.TrashScreen
import app.ledgerly.ui.trips.TripsScreen

@Composable
fun LedgerlyNavHost(pendingRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            nav.navigate(pendingRoute) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    Scaffold(
        // Screens own their top app bars and insets; the outer scaffold only hosts the bottom bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (TopLevel.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    TopLevel.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = { nav.navigateTopLevel(dest.route) },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.TRANSACTIONS) { TransactionsScreen(nav) }
            composable(Routes.INSIGHTS) { InsightsScreen() }
            composable(Routes.ASK) { AskScreen(nav) }
            composable(Routes.MORE) { MoreScreen(nav) }
            composable(Routes.TXN, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                TransactionDetailScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable(
                Routes.QUICK_ADD,
                arguments = listOf(navArgument("cash") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                QuickAddScreen(nav, cash = entry.arguments?.getBoolean("cash") ?: false)
            }
            composable(Routes.REVIEW) { ReviewScreen(nav) }
            composable(Routes.IGNORED) { IgnoredScreen(nav) }
            composable(Routes.BUDGETS) { BudgetsScreen(nav) }
            composable(Routes.TRIPS) { TripsScreen(nav) }
            composable(Routes.SUBSCRIPTIONS) { SubscriptionsScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.TRASH) { TrashScreen(nav) }
        }
    }
}

fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
