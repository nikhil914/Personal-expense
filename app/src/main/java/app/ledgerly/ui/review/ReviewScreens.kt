package app.ledgerly.ui.review

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
fun ReviewScreen(nav: NavHostController) {
    val c = appContainer()
    val rows by c.transactionRepository.observeNeedingReview().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    if (rows.isEmpty()) { EmptyState("All caught up", "Low-confidence parses and possible duplicates appear here."); return }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.txn.id }) { r ->
            Card(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.txn(r.txn.id)) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(r.txn.merchant ?: r.txn.merchantRaw ?: "Unknown merchant", style = MaterialTheme.typography.titleMedium)
                    MoneyText(r.txn.original)
                    SourceBadges(r.txn.sourceChannels)
                    val dup = r.duplicateOfId
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (dup != null) {
                            Text("Possible duplicate", color = MaterialTheme.colorScheme.error)
                            Button(onClick = { scope.launch { c.transactionRepository.mergeDuplicate(r.txn.id, dup) { keep, inc -> c.engines.deduper.merge(keep, inc) } } }) { Text("Merge") }
                            OutlinedButton(onClick = { scope.launch { c.transactionRepository.keepBoth(r.txn.id) } }) { Text("Keep both") }
                        } else {
                            Button(onClick = { scope.launch { c.transactionRepository.markReviewed(r.txn.id) } }) { Text("Looks right") }
                            OutlinedButton(onClick = { nav.navigate(Routes.txn(r.txn.id)) }) { Text("Fix") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IgnoredScreen(nav: NavHostController) {
    val c = appContainer()
    val events by c.captureRepository.observeIgnored().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    if (events.isEmpty()) { EmptyState("Nothing ignored", "Messages the parser skipped are kept here so nothing is lost silently."); return }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(e.sender ?: e.packageName ?: e.channel.name, style = MaterialTheme.typography.labelLarge)
                    Text(e.body.orEmpty(), maxLines = 4)
                    Text("Reason: " + (e.parseStatus?.name ?: e.error ?: "unknown"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { c.captureRepository.reprocess(e.id) } }) { Text("Reprocess") }
                        val s = e.sender ?: e.packageName
                        if (s != null) TextButton(onClick = { scope.launch { c.captureRepository.ignoreSender(s, e.channel, "user") } }) { Text("Always ignore") }
                        TextButton(onClick = { scope.launch { c.captureRepository.delete(e.id) } }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
