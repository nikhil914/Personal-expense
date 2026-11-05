package app.ledgerly.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ledgerly.capture.KnownApps
import app.ledgerly.core.model.CurrencyCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ledgerly_settings")

/** Raw-message retention. [days] = 0 discards the raw text right after parsing; null keeps it forever. */
enum class Retention(val days: Int?) { DISCARD_AFTER_PARSE(0), DAYS_7(7), DAYS_30(30), DAYS_90(90), DAYS_365(365), FOREVER(null) }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val baseCurrency: CurrencyCode = CurrencyCode.INR,
    /** Day of month (1..28) on which the financial month starts (e.g. salary day). */
    val monthStartDay: Int = 1,
    val smsCaptureEnabled: Boolean = false,
    val notificationCaptureEnabled: Boolean = false,
    val gmailCaptureEnabled: Boolean = false,
    /** Package names whose notifications may be captured. Gmail is gated separately by [gmailCaptureEnabled]. */
    val allowedPackages: Set<String> = KnownApps.defaultAllowed,
    /** Extra SMS sender ids the user allow-listed (in addition to the bank-sender heuristic). */
    val allowedSmsSenders: Set<String> = emptySet(),
    val retention: Retention = Retention.DAYS_30,
    val appLockEnabled: Boolean = false,
    /** Blocks screenshots/recents preview (FLAG_SECURE) and masks amounts until tapped. */
    val privacyMode: Boolean = false,
    val postIngestPrompts: Boolean = true,
    val budgetAlerts: Boolean = true,
    val fxFeeNotices: Boolean = true,
    val tripSuggestions: Boolean = true,
    val cashGapPrompts: Boolean = true,
    val dynamicColor: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val baseCurrency = stringPreferencesKey("base_currency")
        val monthStartDay = intPreferencesKey("month_start_day")
        val sms = booleanPreferencesKey("capture_sms")
        val notif = booleanPreferencesKey("capture_notifications")
        val gmail = booleanPreferencesKey("capture_gmail")
        val packages = stringSetPreferencesKey("allowed_packages")
        val senders = stringSetPreferencesKey("allowed_sms_senders")
        val retention = stringPreferencesKey("retention")
        val appLock = booleanPreferencesKey("app_lock")
        val privacy = booleanPreferencesKey("privacy_mode")
        val prompts = booleanPreferencesKey("post_ingest_prompts")
        val budgetAlerts = booleanPreferencesKey("budget_alerts")
        val fxNotices = booleanPreferencesKey("fx_fee_notices")
        val tripSuggestions = booleanPreferencesKey("trip_suggestions")
        val cashGap = booleanPreferencesKey("cash_gap_prompts")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            onboardingDone = p[Keys.onboardingDone] ?: d.onboardingDone,
            baseCurrency = p[Keys.baseCurrency]?.let { runCatching { CurrencyCode.of(it) }.getOrNull() } ?: d.baseCurrency,
            monthStartDay = (p[Keys.monthStartDay] ?: d.monthStartDay).coerceIn(1, 28),
            smsCaptureEnabled = p[Keys.sms] ?: d.smsCaptureEnabled,
            notificationCaptureEnabled = p[Keys.notif] ?: d.notificationCaptureEnabled,
            gmailCaptureEnabled = p[Keys.gmail] ?: d.gmailCaptureEnabled,
            allowedPackages = p[Keys.packages] ?: d.allowedPackages,
            allowedSmsSenders = p[Keys.senders] ?: d.allowedSmsSenders,
            retention = p[Keys.retention]?.let { name -> Retention.entries.firstOrNull { it.name == name } } ?: d.retention,
            appLockEnabled = p[Keys.appLock] ?: d.appLockEnabled,
            privacyMode = p[Keys.privacy] ?: d.privacyMode,
            postIngestPrompts = p[Keys.prompts] ?: d.postIngestPrompts,
            budgetAlerts = p[Keys.budgetAlerts] ?: d.budgetAlerts,
            fxFeeNotices = p[Keys.fxNotices] ?: d.fxFeeNotices,
            tripSuggestions = p[Keys.tripSuggestions] ?: d.tripSuggestions,
            cashGapPrompts = p[Keys.cashGap] ?: d.cashGapPrompts,
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone(done: Boolean) = context.settingsDataStore.edit { it[Keys.onboardingDone] = done }
    suspend fun setBaseCurrency(code: CurrencyCode) = context.settingsDataStore.edit { it[Keys.baseCurrency] = code.code }
    suspend fun setMonthStartDay(day: Int) = context.settingsDataStore.edit { it[Keys.monthStartDay] = day.coerceIn(1, 28) }
    suspend fun setSmsCapture(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.sms] = enabled }
    suspend fun setNotificationCapture(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.notif] = enabled }
    suspend fun setGmailCapture(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.gmail] = enabled }
    suspend fun setRetention(retention: Retention) = context.settingsDataStore.edit { it[Keys.retention] = retention.name }
    suspend fun setAppLock(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.appLock] = enabled }
    suspend fun setPrivacyMode(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.privacy] = enabled }
    suspend fun setPostIngestPrompts(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.prompts] = enabled }
    suspend fun setBudgetAlerts(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.budgetAlerts] = enabled }
    suspend fun setFxFeeNotices(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.fxNotices] = enabled }
    suspend fun setTripSuggestions(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.tripSuggestions] = enabled }
    suspend fun setCashGapPrompts(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.cashGap] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.dynamicColor] = enabled }

    suspend fun setPackageAllowed(packageName: String, allowed: Boolean) = context.settingsDataStore.edit { p ->
        val currentSet = p[Keys.packages] ?: KnownApps.defaultAllowed
        p[Keys.packages] = if (allowed) currentSet + packageName else currentSet - packageName
    }

    suspend fun setSmsSenderAllowed(sender: String, allowed: Boolean) = context.settingsDataStore.edit { p ->
        val currentSet = p[Keys.senders] ?: emptySet()
        val normalized = sender.trim().uppercase()
        p[Keys.senders] = if (allowed) currentSet + normalized else currentSet - normalized
    }
}
