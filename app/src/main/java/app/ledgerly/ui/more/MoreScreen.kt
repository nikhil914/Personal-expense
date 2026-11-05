package app.ledgerly.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.ledgerly.ui.common.*
import app.ledgerly.ui.navigation.Routes
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(nav: NavHostController) {
    val items = listOf(
        "Review inbox" to Routes.REVIEW, "Ignored messages" to Routes.IGNORED, "Budgets" to Routes.BUDGETS,
        "Trips" to Routes.TRIPS, "Subscriptions" to Routes.SUBSCRIPTIONS, "Trash" to Routes.TRASH, "Settings" to Routes.SETTINGS,
    )
    LazyColumn(Modifier.fillMaxSize()) {
        items(items) { (label, route) ->
            ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { nav.navigate(route) })
            HorizontalDivider()
        }
    }
}
