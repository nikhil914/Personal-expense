package app.ledgerly.ui.trips

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
import java.time.LocalDate

@Composable
fun TripsScreen(nav: NavHostController) {
    val c = appContainer()
    val settings by c.settingsState.collectAsStateWithLifecycle()
    val trips by c.tripRepository.observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { showNew = true }) { Text("Start a trip") }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(trips, key = { it.id }) { t ->
                val summary by remember(t.id, settings.baseCurrency) { c.tripRepository.observeSummary(t, settings.baseCurrency) }.collectAsStateWithLifecycle(null)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        Text("${t.currency} · ${t.startDate} – ${t.endDate ?: "ongoing"}")
                        summary?.let { s ->
                            s.totalBase?.let { Row { Text("Total: "); MoneyText(it) } }
                            s.fxFeesBase?.let { Row { Text("FX fees paid: "); MoneyText(it) } }
                            Text("${s.count} transactions")
                        }
                        if (t.endDate == null) TextButton(onClick = { scope.launch { c.tripRepository.end(t.id, LocalDate.now()) } }) { Text("End trip") }
                    }
                }
            }
        }
    }
    if (showNew) {
        var name by rememberText()
        var ccy by rememberText("EUR")
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New trip") },
            text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Name") }); OutlinedTextField(ccy, { ccy = it.uppercase().take(3) }, label = { Text("Currency") }) } },
            confirmButton = { TextButton(enabled = name.isNotBlank() && ccy.length == 3, onClick = {
                scope.launch { c.tripRepository.start(name, CurrencyCode.of(ccy), LocalDate.now(), null) }; showNew = false
            }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }
}
