package app.ledgerly.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ledgerly.MainActivity
import app.ledgerly.R
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.util.MoneyFormat

/**
 * Notifications shown after a transaction is ingested. All copy is computed on-device.
 */
class PostIngestNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_PROMPTS = "capture_prompts"
        const val CHANNEL_ALERTS = "budget_alerts"
        const val CHANNEL_TRAVEL = "travel"

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_PROMPTS, context.getString(R.string.channel_prompts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = context.getString(R.string.channel_prompts_desc)
                    },
                    NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                        description = context.getString(R.string.channel_alerts_desc)
                    },
                    NotificationChannel(CHANNEL_TRAVEL, context.getString(R.string.channel_travel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = context.getString(R.string.channel_travel_desc)
                    },
                ),
            )
        }
    }

    /** "₹850 at Zomato categorised as Food. Correct?" with Yes / Change. */
    fun categorised(txn: Transaction, categoryName: String) {
        val amount = MoneyFormat.format(txn.original)
        val where = txn.merchant ?: txn.merchantRaw
        val text = context.getString(R.string.notif_categorised, amount, where ?: context.getString(R.string.unknown_merchant), categoryName)
        val builder = base(CHANNEL_PROMPTS, context.getString(R.string.notif_categorised_title), text)
            .setContentIntent(openRoute("txn/${txn.id}", txn.id.hashCode()))
            .addAction(0, context.getString(R.string.action_yes), actionIntent(NotificationActionReceiver.ACTION_CONFIRM, txn.id, notificationId(txn.id)))
            .addAction(0, context.getString(R.string.action_change), openRoute("txn/${txn.id}", txn.id.hashCode() + 1))
        show(notificationId(txn.id), builder)
    }

    /** FX fee exposer: "Your card added ₹123 (2.9%) in FX fees on €45.00." */
    fun fxFee(txn: Transaction) {
        val fee = txn.fxFee ?: return
        val pct = if (txn.account.minor != 0L) (fee.minor * 1000 / txn.account.minor) / 10.0 else 0.0
        val text = context.getString(R.string.notif_fx_fee, MoneyFormat.format(fee), pct.toString(), MoneyFormat.format(txn.original))
        show(notificationId("fx" + txn.id), base(CHANNEL_ALERTS, context.getString(R.string.notif_fx_fee_title), text).setContentIntent(openRoute("txn/${txn.id}", ("fx" + txn.id).hashCode())))
    }

    fun budgetCrossed(label: String, threshold: Int, spent: Money, limit: Money) {
        val text = context.getString(R.string.notif_budget, label, threshold, MoneyFormat.format(spent), MoneyFormat.format(limit))
        show(notificationId("budget$label$threshold"), base(CHANNEL_ALERTS, context.getString(R.string.notif_budget_title), text).setContentIntent(openRoute("budgets", 7001)))
    }

    fun tripSuggestion(suggestion: TripSuggestion) {
        val id = notificationId("trip" + suggestion.currency.code)
        val builder = base(CHANNEL_TRAVEL, context.getString(R.string.notif_trip_title), context.getString(R.string.notif_trip, suggestion.currency.code))
            .setContentIntent(openRoute("trips", 7002))
            .addAction(0, context.getString(R.string.action_start_trip), actionIntent(NotificationActionReceiver.ACTION_START_TRIP, suggestion.currency.code, id, suggestion.suggestedName))
        show(id, builder)
    }

    fun cashGap(prompt: CashGapPrompt) {
        val text = context.getString(R.string.notif_cash_gap, MoneyFormat.format(prompt.withdrawn), MoneyFormat.format(prompt.unaccounted))
        val builder = base(CHANNEL_PROMPTS, context.getString(R.string.notif_cash_gap_title), text)
            .setContentIntent(openRoute("quickadd?cash=true", 7003))
            .addAction(0, context.getString(R.string.action_log_cash), openRoute("quickadd?cash=true", 7004))
        show(notificationId("cash" + prompt.withdrawalTxnId), builder)
    }

    fun atmWithdrawal(txn: Transaction) {
        val text = context.getString(R.string.notif_atm, MoneyFormat.format(txn.original))
        show(notificationId("atm" + txn.id), base(CHANNEL_PROMPTS, context.getString(R.string.notif_cash_gap_title), text)
            .setContentIntent(openRoute("quickadd?cash=true", 7005)))
    }

    private fun base(channel: String, title: String, text: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_ledgerly)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        // Amounts are sensitive: hide content on the lock screen.
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_ledgerly)
                .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.notif_public_text)).build(),
        )

    private fun notificationId(key: String) = key.hashCode() and 0x7fffffff

    private fun openRoute(route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, arg: String, notificationId: Int, extra: String? = null): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_ARG, arg)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            if (extra != null) putExtra(NotificationActionReceiver.EXTRA_NAME, extra)
        }
        return PendingIntent.getBroadcast(context, (action + arg).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    private fun show(id: Int, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(id, builder.build())
    }
}
