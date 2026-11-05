package app.ledgerly.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ledgerly.LedgerlyApp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Applies the raw-message retention setting: purges raw text of old captures, deletes non-financial ones. */
class RetentionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LedgerlyApp).container
        val settings = container.settingsStore.current()
        val dao = container.database.sourceEventDao()
        val now = Instant.now()
        // Non-financial captures are useful for the Ignored inbox for one week at most.
        dao.deleteNonFinancialBefore(now.minus(Duration.ofDays(7)))
        settings.retention.days?.let { days ->
            dao.purgeBodiesBefore(now.minus(Duration.ofDays(days.toLong())), now)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("retention", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
