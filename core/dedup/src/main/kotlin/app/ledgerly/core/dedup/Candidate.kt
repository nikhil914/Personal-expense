package app.ledgerly.core.dedup

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.ParsedTransaction
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnType
import java.time.Instant

/** Itemised line from an email receipt (not yet representable on the model Transaction; returned by Merger). */
data class LineItem(val description: String, val amount: Money?, val quantity: Int = 1)

/** A freshly parsed transaction, with its account resolved, to be matched against existing transactions. */
data class Candidate(
    val sourceEventId: Id,
    val channel: Channel,
    val kind: MessageKind,
    val direction: TxnDirection?,
    /** Amount as charged (original currency). */
    val amount: Money,
    /** Amount in account currency, when the message shows both. */
    val accountAmount: Money? = null,
    val accountId: Id? = null,
    val accountLast4: String? = null,
    val institution: String? = null,
    val merchantRaw: String? = null,
    val reference: String? = null,
    val balanceAfter: Money? = null,
    val occurredAt: Instant,
    /** Raw message text (title + body) for exact-duplicate detection. */
    val rawText: String? = null,
    /** Notification source app, used to recognise notification updates. */
    val packageName: String? = null,
    val lineItems: List<LineItem> = emptyList(),
    val confidence: Double = 1.0,
) {
    val isCredit: Boolean get() = direction == TxnDirection.CREDIT ||
        kind in setOf(MessageKind.CREDIT, MessageKind.REFUND, MessageKind.REVERSAL, MessageKind.UPI_RECEIVED, MessageKind.SALARY)

    /** Suggested model type for a brand new transaction from this candidate. */
    val suggestedType: TxnType get() = when (kind) {
        MessageKind.REFUND, MessageKind.REVERSAL -> TxnType.REFUND
        MessageKind.ATM_WITHDRAWAL -> TxnType.ATM_WITHDRAWAL
        else -> if (isCredit) TxnType.INCOME else TxnType.EXPENSE
    }

    companion object {
        /** Build from parser output; null if the parse has no amount. */
        fun from(
            parsed: ParsedTransaction,
            accountId: Id?,
            rawText: String? = null,
            packageName: String? = null,
            lineItems: List<LineItem> = emptyList(),
        ): Candidate? {
            val amt = parsed.amount ?: return null
            return Candidate(
                sourceEventId = parsed.sourceEventId, channel = parsed.channel, kind = parsed.kind,
                direction = parsed.direction, amount = amt, accountAmount = parsed.accountAmount,
                accountId = accountId, accountLast4 = parsed.accountLast4, institution = parsed.institution,
                merchantRaw = parsed.merchantRaw, reference = parsed.reference, balanceAfter = parsed.balanceAfter,
                occurredAt = parsed.occurredAt, rawText = rawText, packageName = packageName,
                lineItems = lineItems, confidence = parsed.confidence,
            )
        }
    }
}
