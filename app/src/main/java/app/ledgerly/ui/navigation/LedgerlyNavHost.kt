package app.ledgerly.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
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
import app.ledgerly.R
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
                            label = { Text(stringResource(dest.label), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.TRANSACTIONS) { TransactionsScreen(nav) }
            composable(Routes.INSIGHTS) { TitledScreen("Insights", nav, back = false) { InsightsScreen() } }
            composable(Routes.ASK) { TitledScreen("Ask your money", nav, back = false, ime = true) { AskScreen(nav) } }
            composable(Routes.MORE) { TitledScreen("More", nav, back = false) { MoreScreen(nav) } }
            composable(Routes.TXN, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                TransactionDetailScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable(
                Routes.QUICK_ADD,
                arguments = listOf(navArgument("cash") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                TitledScreen("Add transaction", nav, ime = true) { QuickAddScreen(nav, cash = entry.arguments?.getBoolean("cash") ?: false) }
            }
            composable(Routes.REVIEW) { TitledScreen("Review inbox", nav) { ReviewScreen(nav) } }
            composable(Routes.IGNORED) { TitledScreen("Ignored messages", nav) { IgnoredScreen(nav) } }
            composable(Routes.BUDGETS) { TitledScreen("Budgets", nav) { BudgetsScreen(nav) } }
            composable(Routes.TRIPS) { TitledScreen("Trips", nav) { TripsScreen(nav) } }
            composable(Routes.SUBSCRIPTIONS) { TitledScreen("Subscriptions", nav) { SubscriptionsScreen(nav) } }
            composable(Routes.SETTINGS) { TitledScreen("Settings", nav) { SettingsScreen(nav) } }
            composable(Routes.TRASH) { TitledScreen("Trash", nav) { TrashScreen(nav) } }
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

/**
 * App bar + safe-area wrapper for screens that don't own a Scaffold. TopAppBar consumes the status-bar
 * inset, so content never draws under the status bar. Top-level screens skip the bottom inset because the
 * outer scaffold's navigation bar already handles it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitledScreen(
    title: String,
    nav: NavHostController,
    back: Boolean = true,
    ime: Boolean = false,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (back) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        contentWindowInsets = if (back) WindowInsets.navigationBars else WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).then(if (ime) Modifier.imePadding() else Modifier)) { content() }
    }
}
