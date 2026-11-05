package app.ledgerly.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.ledgerly.LedgerlyApp
import app.ledgerly.core.model.CurrencyCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Handles action buttons on post-ingest notifications. Not exported. */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIRM = "app.ledgerly.action.CONFIRM_CATEGORY"
        const val ACTION_START_TRIP = "app.ledgerly.action.START_TRIP"
        const val EXTRA_ARG = "arg"
        const val EXTRA_NAME = "name"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val arg = intent.getStringExtra(EXTRA_ARG) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val container = (context.applicationContext as LedgerlyApp).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_CONFIRM -> container.transactionRepository.markReviewed(arg)
                    ACTION_START_TRIP -> {
                        val name = intent.getStringExtra(EXTRA_NAME) ?: "$arg trip"
                        val tripId = container.tripRepository.start(name, CurrencyCode.of(arg), LocalDate.now(), autoDetected = true)
                        container.ingestionPipeline.assignRecentToTrip(tripId, CurrencyCode.of(arg))
                    }
                }
                NotificationManagerCompat.from(context).cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }
}
