package app.ledgerly.ui.subscriptions

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
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money

@Composable
fun SubscriptionsScreen(nav: NavHostController) {
    val c = appContainer()
    val all by c.recurringRepository.observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { c.recurringRepository.detect(c.engines.insights, java.time.LocalDate.now(), java.time.ZoneId.systemDefault()) }
    if (all.isEmpty()) { EmptyState("No recurring payments yet", "Subscriptions and bills are detected automatically from your history."); return }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        items(all, key = { it.id }) { r ->
            ListItem(
                headlineContent = { Text(r.name) },
                supportingContent = { Text("${r.cadence.name.lowercase()} · next ${r.nextDue ?: "?"}" + if (r.isSubscription) " · subscription" else "") },
                leadingContent = { MoneyText(Money(r.amountMinor, CurrencyCode.of(r.currency))) },
                trailingContent = { Switch(checked = r.active, onCheckedChange = { on -> scope.launch { c.recurringRepository.setActive(r, on) } }) },
            )
        }
    }
}
