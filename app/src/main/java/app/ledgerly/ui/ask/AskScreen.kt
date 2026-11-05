package app.ledgerly.ui.ask

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
import app.ledgerly.engine.AskContext
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

private data class Msg(val fromUser: Boolean, val text: String)

@Composable
fun AskScreen(nav: NavHostController) {
    val c = appContainer()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf(Msg(false, "Ask about your spending, e.g. \"How much did I spend on Uber last month?\" Answers are computed on this phone.")) }
    var q by rememberText()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs) { m ->
                Card(colors = CardDefaults.cardColors(containerColor = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(if (m.fromUser) 0.85f else 1f)) { Text(m.text, Modifier.padding(12.dp)) }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Ask a question") }, singleLine = true)
            TextButton(enabled = q.isNotBlank(), onClick = {
                val question = q.trim(); q = ""; msgs += Msg(true, question)
                scope.launch {
                    val zone = ZoneId.systemDefault()
                    val ctx = AskContext(LocalDate.now(zone), zone, s.baseCurrency, s.monthStartDay, c.transactionRepository.allActive(), c.categoryRepository.observeModels().first())
                    msgs += Msg(false, runCatching { c.engines.ask.ask(question, ctx).text }.getOrElse { "Sorry, I couldn't answer that." })
                }
            }) { Text("Ask") }
        }
    }
}
