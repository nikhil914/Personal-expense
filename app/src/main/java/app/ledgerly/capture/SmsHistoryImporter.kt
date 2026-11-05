package app.ledgerly.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.ledgerly.BuildConfig
import app.ledgerly.core.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime

/**
 * One-off "scan past messages" import. Reads the SMS inbox for the last [months] months, keeps only
 * allow-listed / bank-looking senders and financial-looking texts. Everything else is discarded in
 * memory and never persisted. Requires READ_SMS (full flavour) and explicit user consent.
 */
class SmsHistoryImporter(
    private val context: Context,
    private val captureQueue: CaptureQueue,
) {
    data class Result(val scanned: Int, val candidates: Int, val queued: Int)

    fun hasPermission(): Boolean =
        BuildConfig.SMS_CAPTURE_ENABLED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun import(months: Int, userAllowList: Set<String>): Result = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext Result(0, 0, 0)
        val since = ZonedDateTime.now().minusMonths(months.toLong()).toInstant().toEpochMilli()
        val projection = arrayOf(
            Telephony.Sms.Inbox.ADDRESS,
            Telephony.Sms.Inbox.BODY,
            Telephony.Sms.Inbox.DATE,
            Telephony.Sms.Inbox.DATE_SENT,
        )
        var scanned = 0
        var candidates = 0
        var queued = 0
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            "${Telephony.Sms.Inbox.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.Inbox.DATE} ASC",
        )?.use { cursor ->
            val iAddress = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS)
            val iBody = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY)
            val iDate = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE)
            val iDateSent = cursor.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE_SENT)
            while (cursor.moveToNext()) {
                scanned++
                val sender = cursor.getString(iAddress) ?: continue
                if (!SenderFilter.isLikelyFinancialSender(sender, userAllowList)) continue
                val body = cursor.getString(iBody) ?: continue
                if (!SenderFilter.looksFinancial(body)) continue
                candidates++
                // DATE_SENT is the service-centre timestamp (matches the live broadcast); fall back to DATE.
                val sent = cursor.getLong(iDateSent)
                val ts = if (sent > 0) sent else cursor.getLong(iDate)
                val isNew = captureQueue.enqueue(
                    channel = Channel.SMS,
                    sender = sender,
                    packageName = null,
                    title = null,
                    body = body,
                    receivedAt = Instant.ofEpochMilli(ts),
                    scheduleProcessing = false,
                )
                if (isNew) queued++
            }
        }
        if (queued > 0) ProcessCaptureWorker.enqueue(context)
        Result(scanned, candidates, queued)
    }
}
