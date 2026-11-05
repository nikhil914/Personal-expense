package app.ledgerly.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ledgerly.LedgerlyApp
import app.ledgerly.widget.SafeToSpendWidget

/** Drains the capture queue through the ingestion pipeline, then refreshes the widget. */
class ProcessCaptureWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LedgerlyApp).container
        return try {
            val processed = container.ingestionPipeline.processPending()
            if (processed > 0) SafeToSpendWidget.refresh(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "process-capture"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProcessCaptureWorker>().build()
            // APPEND_OR_REPLACE: an event captured while a drain is running gets its own follow-up run.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
