package app.ledgerly.capture

import android.content.Context
import app.ledgerly.core.model.Channel
import app.ledgerly.data.db.SourceEventDao
import app.ledgerly.data.db.SourceEventEntity
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Durable capture queue: raw events are written to the (SQLCipher-encrypted) source_events table with
 * processed = false and drained by [ProcessCaptureWorker]. Writing first and parsing later keeps
 * BroadcastReceiver / NotificationListener callbacks fast and survives process death.
 */
class CaptureQueue(
    private val context: Context,
    private val dao: SourceEventDao,
) {
    /** Returns true if the event was new (not a duplicate of an already captured message). */
    suspend fun enqueue(
        channel: Channel,
        sender: String?,
        packageName: String?,
        title: String?,
        body: String,
        receivedAt: Instant,
        scheduleProcessing: Boolean = true,
    ): Boolean {
        val entity = SourceEventEntity(
            id = UUID.randomUUID().toString(),
            channel = channel,
            sender = sender?.trim(),
            packageName = packageName,
            title = title,
            body = body,
            receivedAt = receivedAt,
            contentHash = contentHash(channel, sender, packageName, body, receivedAt),
        )
        val inserted = dao.insert(entity) != -1L
        if (inserted && scheduleProcessing) ProcessCaptureWorker.enqueue(context)
        return inserted
    }

    companion object {
        /**
         * Identity of a captured message. For SMS the timestamp is the service-centre time, which is the
         * same for the live broadcast (SmsMessage.timestampMillis) and the inbox (Telephony.Sms.DATE_SENT),
         * so a history import never duplicates a live capture.
         */
        fun contentHash(channel: Channel, sender: String?, packageName: String?, body: String, receivedAt: Instant): String {
            val key = listOf(channel.name, sender?.trim()?.uppercase().orEmpty(), packageName.orEmpty(), body.trim(), (receivedAt.epochSecond).toString())
                .joinToString("\u001F")
            return MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
