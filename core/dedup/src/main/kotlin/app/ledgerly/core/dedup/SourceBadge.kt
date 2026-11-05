package app.ledgerly.core.dedup

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.Transaction

/** Badge text for the channels a transaction was captured from, e.g. "SMS + Notification + Email". */
object SourceBadge {
    private val ORDER = listOf(Channel.SMS, Channel.NOTIFICATION, Channel.EMAIL, Channel.BANK_API, Channel.OCR, Channel.IMPORT, Channel.MANUAL)

    fun label(c: Channel): String = when (c) {
        Channel.SMS -> "SMS"
        Channel.NOTIFICATION -> "Notification"
        Channel.EMAIL -> "Email"
        Channel.OCR -> "Receipt scan"
        Channel.MANUAL -> "Manual"
        Channel.IMPORT -> "Import"
        Channel.BANK_API -> "Bank"
    }

    fun text(channels: Collection<Channel>): String =
        channels.toSet().sortedBy { ORDER.indexOf(it) }.joinToString(" + ") { label(it) }

    fun text(t: Transaction): String = text(t.sourceChannels)

    /** True when more than one channel contributed (show the "merged" badge). */
    fun isMerged(t: Transaction): Boolean = t.sourceChannels.size > 1

    /** "Merged from SMS + Notification + Email", or null when single-source. */
    fun mergedFrom(t: Transaction): String? = if (isMerged(t)) "Merged from ${text(t)}" else null
}
