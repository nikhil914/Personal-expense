package app.ledgerly.fx

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ledgerly.LedgerlyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Daily sync of ECB euro reference rates. This is the app's only network request; it sends no user
 * data (plain GET of a public file).
 */
class RateSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LedgerlyApp).container
        return try {
            val xml = fetch(ECB_DAILY_URL)
            val parsed = EcbRatesParser.parse(xml) ?: return Result.retry()
            container.fxRateRepository.saveEcb(parsed.date, parsed.eurRates)
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/xml")
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("ECB HTTP $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val ECB_DAILY_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"

        private val networkConstraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RateSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("ecb-rate-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RateSyncWorker>().setConstraints(networkConstraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork("ecb-rate-sync-now", ExistingWorkPolicy.KEEP, request)
        }
    }
}
