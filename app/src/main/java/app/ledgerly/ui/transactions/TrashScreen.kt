package app.ledgerly.ui.transactions

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
import app.ledgerly.data.db.AuditActor

@Composable
fun TrashScreen(nav: NavHostController) {
    val c = appContainer()
    val rows by c.transactionRepository.observeTrash().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    if (rows.isEmpty()) { EmptyState("Trash is empty", "Deleted entries stay here for 30 days."); return }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        items(rows, key = { it.txn.id }) { r ->
            ListItem(
                headlineContent = { Text(r.txn.merchant ?: r.txn.merchantRaw ?: "Unknown") },
                supportingContent = { MoneyText(r.txn.base) },
                trailingContent = { TextButton(onClick = { scope.launch { c.transactionRepository.restore(r.txn.id, AuditActor.USER) } }) { Text("Restore") } },
            )
        }
    }
}
