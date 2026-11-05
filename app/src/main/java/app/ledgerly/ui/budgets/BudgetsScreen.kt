package app.ledgerly.ui.budgets

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
import app.ledgerly.core.model.Money
import app.ledgerly.engine.FinancialPeriod
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun BudgetsScreen(nav: NavHostController) {
    val c = appContainer()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val period = remember(s.monthStartDay) { FinancialPeriod.current(LocalDate.now(zone), s.monthStartDay) }
    val overview by remember(s.baseCurrency, period) { c.budgetRepository.observeOverview(s.baseCurrency, period, zone) }.collectAsStateWithLifecycle(null)
    val cats by c.categoryRepository.observeEntities().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var pickCategory by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    val o = overview ?: return
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("This period", style = MaterialTheme.typography.titleLarge)
            Row { Text("Spent "); MoneyText(o.spent); o.total?.let { Text(" of "); MoneyText(it) } }
            Button(onClick = { pickCategory = true }) { Text("Add category budget") }
        }
        items(o.lines, key = { it.categoryId }) { l ->
            val pct = if (l.limit.minor > 0) (l.spent.minor.toFloat() / l.limit.minor).coerceIn(0f, 1.5f) else 0f
            Card(Modifier.fillMaxWidth().clickable { editing = l.categoryId }) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(l.categoryName); Row { MoneyText(l.spent); Text(" / "); MoneyText(l.limit) } }
                    LinearProgressIndicator(progress = { pct.coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth(),
                        color = if (pct >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (pickCategory) CategoryPickerDialog(categories = cats, onPick = { editing = it.id; pickCategory = false }, onDismiss = { pickCategory = false })
    editing?.let { catId ->
        var amount by rememberText()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Monthly limit (${s.baseCurrency})") },
            text = { OutlinedTextField(amount, { amount = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                amount.toBigDecimalOrNull()?.let { v -> scope.launch { c.budgetRepository.setLine(s.baseCurrency, catId, Money.of(v, s.baseCurrency)) } }
                editing = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { scope.launch { c.budgetRepository.removeLine(s.baseCurrency, catId) }; editing = null }) { Text("Remove") } },
        )
    }
}
