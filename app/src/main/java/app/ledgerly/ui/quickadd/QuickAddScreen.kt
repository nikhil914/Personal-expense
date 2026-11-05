package app.ledgerly.ui.quickadd

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
import app.ledgerly.core.model.*
import app.ledgerly.data.db.AuditActor
import app.ledgerly.quickadd.PaymentHint
import app.ledgerly.quickadd.QuickAddParser
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable
fun QuickAddScreen(nav: NavHostController, cash: Boolean) {
    val c = appContainer()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var input by rememberText(if (cash) "cash " else "")
    val parsed = remember(input, s.baseCurrency) { QuickAddParser.parse(input, s.baseCurrency, LocalDate.now()) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quick add", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), placeholder = { Text("450 uber  ·  €12.50 coffee yesterday cash") })
        parsed.amount?.let { Row { Text(if (parsed.isIncome) "Income: " else "Expense: "); MoneyText(it) } }
        Text("${parsed.description.ifBlank { "—" }} · ${parsed.date}" + (parsed.payment?.let { " · " + it.name.lowercase() } ?: ""))
        Button(enabled = parsed.amount != null, onClick = {
            val amt = parsed.amount ?: return@Button
            scope.launch {
                val zone = ZoneId.systemDefault()
                val txn = Transaction(
                    id = UUID.randomUUID().toString(), accountId = null,
                    type = if (parsed.isIncome) TxnType.INCOME else TxnType.EXPENSE,
                    occurredAt = parsed.date.atTime(java.time.LocalTime.now()).atZone(zone).toInstant(),
                    original = amt, merchant = parsed.description.ifBlank { null }, tags = parsed.tags +
                        (if (parsed.payment == PaymentHint.CASH) setOf("cash") else emptySet()),
                    sourceChannels = setOf(Channel.MANUAL), isReviewed = true,
                )
                val rates = c.fxRateRepository.table(setOf(amt.currency, s.baseCurrency), parsed.date)
                val enriched = c.engines.fx.enrich(txn, amt.currency, s.baseCurrency, rates)
                val cat = c.categoriser.categorise(enriched, null)
                c.transactionRepository.insertNew(enriched.copy(categoryId = cat?.categoryId ?: enriched.categoryId), AuditActor.USER)
                nav.popBackStack()
            }
        }) { Text("Save") }
    }
}
