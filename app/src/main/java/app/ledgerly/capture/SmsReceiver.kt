package app.ledgerly.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.ledgerly.BuildConfig
import app.ledgerly.LedgerlyApp
import app.ledgerly.core.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Live SMS capture. Multipart messages are joined per sender. Personal / non-financial messages are
 * dropped in memory; only candidate bank alerts are written to the encrypted capture queue.
 * Removed from the manifest in the notifOnly flavour.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.SMS_CAPTURE_ENABLED) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        data class Joined(val sender: String, val body: StringBuilder, var timestamp: Long)
        val bySender = LinkedHashMap<String, Joined>()
        for (sms in messages) {
            if (sms == null) continue
            val sender = sms.originatingAddress ?: sms.displayOriginatingAddress ?: continue
            val j = bySender.getOrPut(sender) { Joined(sender, StringBuilder(), sms.timestampMillis) }
            j.body.append(sms.messageBody ?: sms.displayMessageBody.orEmpty())
            j.timestamp = minOf(j.timestamp, sms.timestampMillis)
        }

        val container = (context.applicationContext as LedgerlyApp).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = container.settingsStore.current()
                if (!settings.smsCaptureEnabled) return@launch
                for (j in bySender.values) {
                    val body = j.body.toString()
                    if (!SenderFilter.isLikelyFinancialSender(j.sender, settings.allowedSmsSenders)) continue
                    if (!SenderFilter.looksFinancial(body)) continue
                    container.captureQueue.enqueue(
                        channel = Channel.SMS,
                        sender = j.sender,
                        packageName = null,
                        title = null,
                        body = body,
                        receivedAt = Instant.ofEpochMilli(j.timestamp),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
