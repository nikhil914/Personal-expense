package app.ledgerly.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.ledgerly.ui.common.*
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.ledgerly.data.settings.Retention

@Composable
fun SettingsScreen(nav: NavHostController) {
    val c = appContainer()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val st = c.settingsStore
    val scope = rememberCoroutineScope()
    var pickCurrency by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { status = "Exported ${c.exporter.exportCsv(uri)} transactions" }
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { status = "Exported ${c.exporter.exportJson(uri)} transactions with history" }
    }
    @Composable fun toggle(label: String, value: Boolean, set: suspend (Boolean) -> Unit) =
        ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(value, { v -> scope.launch { set(v) } }) })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("General")
        ListItem(headlineContent = { Text("Base currency") }, supportingContent = { Text(s.baseCurrency.code) },
            trailingContent = { TextButton(onClick = { pickCurrency = true }) { Text("Change") } })
        ListItem(headlineContent = { Text("Financial month starts on day") }, supportingContent = { Text(s.monthStartDay.toString()) },
            trailingContent = { Row {
                TextButton(onClick = { scope.launch { st.setMonthStartDay((s.monthStartDay - 1).coerceAtLeast(1)) } }) { Text("−") }
                TextButton(onClick = { scope.launch { st.setMonthStartDay((s.monthStartDay + 1).coerceAtMost(28)) } }) { Text("+") }
            } })
        SectionHeader("Capture")
        toggle("Bank SMS", s.smsCaptureEnabled) { st.setSmsCapture(it) }
        toggle("Bank & UPI app notifications", s.notificationCaptureEnabled) { st.setNotificationCapture(it) }
        toggle("Gmail bank-alert notifications", s.gmailCaptureEnabled) { st.setGmailCapture(it) }
        ListItem(headlineContent = { Text("Keep original messages") }, supportingContent = { Text(s.retention.name.lowercase().replace('_', ' ')) },
            trailingContent = { TextButton(onClick = {
                val all = Retention.entries; scope.launch { st.setRetention(all[(all.indexOf(s.retention) + 1) % all.size]) }
            }) { Text("Change") } })
        SectionHeader("Smart prompts")
        toggle("\"Categorised as…, correct?\" prompts", s.postIngestPrompts) { st.setPostIngestPrompts(it) }
        toggle("Budget alerts", s.budgetAlerts) { st.setBudgetAlerts(it) }
        toggle("Show bank FX fee after foreign payments", s.fxFeeNotices) { st.setFxFeeNotices(it) }
        toggle("Suggest trip mode for foreign currency", s.tripSuggestions) { st.setTripSuggestions(it) }
        toggle("Remind me to log ATM cash", s.cashGapPrompts) { st.setCashGapPrompts(it) }
        SectionHeader("Security")
        toggle("App lock (biometric)", s.appLockEnabled) { st.setAppLock(it) }
        toggle("Privacy mode (hide amounts, block screenshots)", s.privacyMode) { st.setPrivacyMode(it) }
        toggle("Dynamic colour", s.dynamicColor) { st.setDynamicColor(it) }
        SectionHeader("Data")
        ListItem(headlineContent = { Text("Export CSV") }, trailingContent = { TextButton(onClick = { csv.launch("ledgerly.csv") }) { Text("Export") } })
        ListItem(headlineContent = { Text("Export JSON (with edit history)") }, trailingContent = { TextButton(onClick = { json.launch("ledgerly.json") }) { Text("Export") } })
        status?.let { Text(it, Modifier.padding(16.dp)) }
    }
    if (pickCurrency) CurrencyPickerDialog(s.baseCurrency, onPick = { scope.launch { st.setBaseCurrency(it) }; pickCurrency = false }, onDismiss = { pickCurrency = false })
}
