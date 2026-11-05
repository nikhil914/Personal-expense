package app.ledgerly.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.ledgerly.LedgerlyApp
import app.ledgerly.core.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Captures transaction alerts from allow-listed bank / UPI / card apps, and (with separate consent)
 * bank alert emails via Gmail notifications. Ongoing and group-summary notifications are ignored and
 * re-posts are de-bounced by notification key + text hash.
 */
class TxnNotificationListenerService : NotificationListenerService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val debouncer = NotificationDebouncer()

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onListenerDisconnected() {
        scope.cancel()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val notification = sbn.notification ?: return
        if (sbn.isOngoing) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        val container = (applicationContext as LedgerlyApp).container
        val settings = container.settingsState.value
        if (!settings.notificationCaptureEnabled) return
        val isGmail = pkg == KnownApps.GMAIL
        if (isGmail && !settings.gmailCaptureEnabled) return
        if (!isGmail && pkg !in settings.allowedPackages) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val body = listOfNotNull(bigText ?: text).joinToString("\n").trim()
        if (body.isEmpty()) return
        if (!SenderFilter.looksFinancial("${title.orEmpty()} $body")) return
        if (!debouncer.shouldProcess(sbn.key ?: "$pkg:${sbn.id}", "${title.orEmpty()}\n$body")) return

        val postedAt = Instant.ofEpochMilli(if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis())
        scope.launch {
            container.captureQueue.enqueue(
                channel = if (isGmail) Channel.EMAIL else Channel.NOTIFICATION,
                // For Gmail the title is the email sender (e.g. "HDFC Bank InstaAlerts").
                sender = if (isGmail) title else null,
                packageName = pkg,
                title = title,
                body = body,
                receivedAt = postedAt,
            )
        }
    }
}
