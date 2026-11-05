package app.ledgerly.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ledgerly.LedgerlyApp
import app.ledgerly.MainActivity
import app.ledgerly.R
import app.ledgerly.engine.FinancialPeriod
import app.ledgerly.util.MoneyFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** "Safe to spend today" home-screen widget with a quick-add button. */
class SafeToSpendWidget : GlanceAppWidget() {

    data class State(val amount: String?, val subtitle: String, val privacy: Boolean, val negative: Boolean)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadState(context)
        provideContent {
            GlanceTheme {
                Content(context, state)
            }
        }
    }

    private suspend fun loadState(context: Context): State {
        val container = (context.applicationContext as LedgerlyApp).container
        val settings = container.settingsStore.current()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val period = FinancialPeriod.current(today, settings.monthStartDay)
        val sts = runCatching { container.budgetRepository.safeToSpend(settings.baseCurrency, today, period, zone) }.getOrNull()
        return if (sts == null) {
            State(null, context.getString(R.string.widget_set_budget), settings.privacyMode, false)
        } else {
            State(
                MoneyFormat.formatWhole(sts.today),
                context.getString(R.string.widget_days_left, sts.daysLeft, MoneyFormat.formatWhole(sts.remainingInPeriod)),
                settings.privacyMode,
                sts.today.isNegative,
            )
        }
    }

    @Composable
    private fun Content(context: Context, state: State) {
        val openApp = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val quickAdd = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, "quickadd")
        }
        Column(
            modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(16.dp).padding(12.dp)
                .clickable(actionStartActivity(openApp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.widget_safe_to_spend_label),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        state.amount == null -> "—"
                        state.privacy -> "••••"
                        else -> state.amount
                    },
                    style = TextStyle(
                        color = if (state.negative) ColorProvider(Color(0xFFD32F2F)) else GlanceTheme.colors.onSurface,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = "+",
                    style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.background(GlanceTheme.colors.primary).cornerRadius(12.dp)
                        .padding(horizontal = 12.dp, vertical = 2.dp).clickable(actionStartActivity(quickAdd)),
                )
            }
            Text(text = state.subtitle, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
    }

    companion object {
        /** Called after ingestion, edits and by [WidgetRefreshWorker]. */
        suspend fun refresh(context: Context) {
            runCatching { SafeToSpendWidget().updateAll(context) }
        }
    }
}

class SafeToSpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SafeToSpendWidget()
}

/** Periodic refresh so "today" rolls over even without new transactions. */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        SafeToSpendWidget.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(3, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("widget-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
