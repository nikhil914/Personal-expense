package app.ledgerly.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ledgerly.AppContainer
import app.ledgerly.BuildConfig
import app.ledgerly.R
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.ui.common.CurrencyPickerDialog
import app.ledgerly.ui.common.ledgerlyViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OnboardingStep { WELCOME, CURRENCY, SMS, NOTIFICATIONS, GMAIL, ALERTS, IMPORT }

data class ImportState(val running: Boolean = false, val message: String? = null)

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settingsState
    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()

    fun setCurrency(code: CurrencyCode) = viewModelScope.launch { c.settingsStore.setBaseCurrency(code) }
    fun setSms(enabled: Boolean) = viewModelScope.launch { c.settingsStore.setSmsCapture(enabled) }
    fun setNotifications(enabled: Boolean) = viewModelScope.launch { c.settingsStore.setNotificationCapture(enabled) }
    fun setGmail(enabled: Boolean) = viewModelScope.launch { c.settingsStore.setGmailCapture(enabled) }

    fun importHistory(months: Int, doneLabel: (Int, Int) -> String) = viewModelScope.launch {
        _import.value = ImportState(running = true)
        val s = c.settingsStore.current()
        val r = c.smsHistoryImporter.import(months, s.allowedSmsSenders)
        _import.value = ImportState(running = false, message = doneLabel(r.scanned, r.queued))
    }

    fun finish() = viewModelScope.launch {
        c.seeder.seedIfEmpty(c.settingsStore.current().baseCurrency)
        c.settingsStore.setOnboardingDone(true)
    }
}

fun isNotificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

@Composable
fun OnboardingScreen() {
    val vm = ledgerlyViewModel { OnboardingViewModel(it) }
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val importState by vm.import.collectAsStateWithLifecycle()
    val steps = remember {
        OnboardingStep.entries.filter { it != OnboardingStep.SMS || BuildConfig.SMS_CAPTURE_ENABLED }
            .filter { it != OnboardingStep.IMPORT || BuildConfig.SMS_CAPTURE_ENABLED }
    }
    var index by rememberSaveable { mutableStateOf(0) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    fun next() { if (index < steps.lastIndex) index++ else vm.finish() }

    var notifGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notifGranted = isNotificationAccessGranted(context)
        if (notifGranted && step == OnboardingStep.NOTIFICATIONS) vm.setNotifications(true)
    }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.setSms(result.values.all { it })
        next()
    }
    val postNotifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    var showCurrency by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
        LinearProgressIndicator(progress = { (index + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
            when (step) {
                OnboardingStep.WELCOME -> StepBody(Icons.Filled.Savings, R.string.onb_welcome_title, R.string.onb_welcome_body)
                OnboardingStep.CURRENCY -> {
                    StepBody(Icons.Filled.Savings, R.string.onb_currency_title, R.string.onb_currency_body)
                    OutlinedButton(onClick = { showCurrency = true }, modifier = Modifier.padding(top = 16.dp)) {
                        Text(settings.baseCurrency.code)
                    }
                }
                OnboardingStep.SMS -> Disclosure(Icons.Filled.Sms, R.string.onb_sms_title, R.string.onb_sms_disclosure)
                OnboardingStep.NOTIFICATIONS -> {
                    Disclosure(Icons.Filled.Notifications, R.string.onb_notif_title, R.string.onb_notif_disclosure)
                    if (notifGranted) Text(stringResource(R.string.onb_notif_granted), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                }
                OnboardingStep.GMAIL -> Disclosure(Icons.Filled.Email, R.string.onb_gmail_title, R.string.onb_gmail_disclosure)
                OnboardingStep.ALERTS -> StepBody(Icons.Filled.NotificationsActive, R.string.onb_alerts_title, R.string.onb_alerts_body)
                OnboardingStep.IMPORT -> {
                    StepBody(Icons.Filled.History, R.string.onb_import_title, R.string.onb_import_body)
                    if (settings.smsCaptureEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                            listOf(1, 3, 6, 12).forEach { m ->
                                FilterChip(
                                    selected = false,
                                    enabled = !importState.running,
                                    onClick = { vm.importHistory(m) { scanned, queued -> context.getString(R.string.onb_import_done, scanned, queued) } },
                                    label = { Text(stringResource(R.string.months_n, m)) },
                                )
                            }
                        }
                        if (importState.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        importState.message?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
                    } else {
                        Text(stringResource(R.string.onb_import_needs_sms), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }

        // Actions: an explicit "Allow" plus an equally visible "Not now" (Play prominent-disclosure requirement).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) TextButton(onClick = { index-- }) { Text(stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            when (step) {
                OnboardingStep.SMS -> {
                    OutlinedButton(onClick = { vm.setSms(false); next() }) { Text(stringResource(R.string.not_now)) }
                    Button(onClick = { smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }) {
                        Text(stringResource(R.string.agree_and_allow))
                    }
                }
                OnboardingStep.NOTIFICATIONS -> {
                    OutlinedButton(onClick = { next() }) { Text(stringResource(if (notifGranted) R.string.next else R.string.not_now)) }
                    if (!notifGranted) Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text(stringResource(R.string.agree_and_open_settings)) }
                }
                OnboardingStep.GMAIL -> {
                    OutlinedButton(onClick = { vm.setGmail(false); next() }) { Text(stringResource(R.string.not_now)) }
                    Button(onClick = { vm.setGmail(true); next() }) { Text(stringResource(R.string.agree_and_allow)) }
                }
                OnboardingStep.ALERTS -> {
                    OutlinedButton(onClick = { next() }) { Text(stringResource(R.string.not_now)) }
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else next()
                    }) { Text(stringResource(R.string.allow)) }
                }
                OnboardingStep.IMPORT -> Button(onClick = { next() }, enabled = !importState.running) { Text(stringResource(R.string.finish)) }
                else -> Button(onClick = { next() }) { Text(stringResource(if (index == steps.lastIndex) R.string.finish else R.string.next)) }
            }
        }
    }

    if (showCurrency) {
        CurrencyPickerDialog(settings.baseCurrency, onPick = { vm.setCurrency(it); showCurrency = false }, onDismiss = { showCurrency = false })
    }
}

@Composable
private fun StepBody(icon: ImageVector, title: Int, body: Int) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
}

/** Prominent disclosure: what is accessed, why, that it stays on-device, and how to turn it off. */
@Composable
private fun Disclosure(icon: ImageVector, title: Int, disclosure: Int) {
    StepBody(icon, title, R.string.onb_disclosure_intro)
    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(stringResource(disclosure), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
    Text(
        stringResource(R.string.onb_disclosure_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}
