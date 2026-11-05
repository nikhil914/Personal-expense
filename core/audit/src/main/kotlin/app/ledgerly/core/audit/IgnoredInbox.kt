package app.ledgerly.core.audit

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.ParsedTransaction
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnType
import java.time.Clock
import java.time.Instant

enum class IgnoreReason { NOT_FINANCIAL, OTP, PROMO, PARSE_FAILED, LOW_CONFIDENCE, INFO_ONLY, DUPLICATE, SENDER_BLOCKED }

/** A message the parser saw but did not turn into a transaction. */
data class IgnoredMessage(
    val id: Id,
    val source: SourceEvent,
    val reason: IgnoreReason,
    val detail: String? = null,
    val parsed: ParsedTransaction? = null,
    val seenAt: Instant,
)

/**
 * "Ignored inbox": lets the user see what was skipped and why, convert an item into a transaction,
 * or always ignore a sender. Sender ids are normalised so "VM-HDFCBK" and "AD-HDFCBK" match.
 */
class IgnoredInbox(private val clock: Clock) {
    private val items = LinkedHashMap<Id, IgnoredMessage>()
    private val blockedSenders = LinkedHashSet<String>()

    /**
     * Add a skipped message. If its sender is on the always-ignore list it is not stored (returns null):
     * the user explicitly asked not to see those again.
     */
    @Synchronized
    fun add(source: SourceEvent, reason: IgnoreReason, detail: String? = null, parsed: ParsedTransaction? = null): IgnoredMessage? {
        if (isSenderIgnored(source.sender ?: source.packageName)) return null
        val m = IgnoredMessage(source.id, source, reason, detail, parsed, clock.instant())
        items[m.id] = m
        return m
    }

    @Synchronized fun list(reason: IgnoreReason? = null): List<IgnoredMessage> =
        items.values.filter { reason == null || it.reason == reason }.sortedByDescending { it.source.receivedAt }

    @Synchronized fun get(id: Id): IgnoredMessage? = items[id]

    @Synchronized fun dismiss(id: Id): Boolean = items.remove(id) != null

    /** Add sender to the always-ignore list and drop its queued items. Returns how many were dropped. */
    @Synchronized
    fun alwaysIgnoreSender(sender: String): Int {
        val key = normaliseSender(sender)
        blockedSenders += key
        val drop = items.values.filter { normaliseSender(it.source.sender ?: it.source.packageName ?: "") == key }.map { it.id }
        drop.forEach { items.remove(it) }
        return drop.size
    }

    @Synchronized fun unblockSender(sender: String): Boolean = blockedSenders.remove(normaliseSender(sender))

    @Synchronized fun blockedSenders(): Set<String> = blockedSenders.toSet()

    @Synchronized fun isSenderIgnored(sender: String?): Boolean = sender != null && normaliseSender(sender) in blockedSenders

    /**
     * Turn an ignored message into a transaction (recorded through [ledger] as a USER create). Amount/type
     * default from the parser output when present; [amount] is required if the parser found none.
     */
    fun convertToTransaction(
        messageId: Id,
        ledger: TransactionLedger,
        transactionId: Id,
        amount: Money? = null,
        type: TxnType? = null,
        accountId: Id? = null,
        categoryId: Id? = null,
        merchant: String? = null,
    ): Transaction {
        val m = synchronized(this) { items[messageId] } ?: throw NoSuchElementException("No ignored message $messageId")
        val p = m.parsed
        val amt = amount ?: p?.accountAmount ?: p?.amount
            ?: throw IllegalArgumentException("Amount required: the parser found none in this message")
        val t = type ?: when {
            p?.kind == MessageKind.REFUND || p?.kind == MessageKind.REVERSAL -> TxnType.REFUND
            p?.kind == MessageKind.ATM_WITHDRAWAL -> TxnType.ATM_WITHDRAWAL
            p?.direction == TxnDirection.CREDIT -> TxnType.INCOME
            else -> TxnType.EXPENSE
        }
        val txn = Transaction(
            id = transactionId,
            accountId = accountId,
            type = t,
            occurredAt = p?.occurredAt ?: m.source.receivedAt,
            original = p?.amount ?: amt,
            account = amt,
            base = amt,
            merchant = merchant,
            merchantRaw = p?.merchantRaw,
            categoryId = categoryId,
            reference = p?.reference,
            accountLast4 = p?.accountLast4,
            sourceEventIds = listOf(m.source.id),
            sourceChannels = setOf(m.source.channel),
            isReviewed = true,
        )
        val created = ledger.create(txn, Actor.USER)
        synchronized(this) { items.remove(messageId) }
        return created
    }

    companion object {
        private val DLT_PREFIX = Regex("^[A-Z]{2}-")
        private val DLT_SUFFIX = Regex("-[A-Z]$")

        /** Uppercase, strip Indian DLT operator/circle prefix ("VM-") and trailing type suffix ("-S"). */
        fun normaliseSender(sender: String): String =
            sender.trim().uppercase().replace(DLT_PREFIX, "").replace(DLT_SUFFIX, "")
    }
}
