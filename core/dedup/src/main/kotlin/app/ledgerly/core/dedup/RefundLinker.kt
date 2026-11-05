package app.ledgerly.core.dedup

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnType
import java.time.Duration

data class RefundLink(
    val refundId: Id,
    val originalId: Id,
    val confidence: Double,
    val partial: Boolean,
    /** Refundable amount left on the original after this refund (original account currency). */
    val remainingAfter: Money,
    val reasons: List<String>,
)

/**
 * Links a credit/refund to the past debit it reverses: merchant similar, within [window] before, amount <= what is
 * still refundable on the original (original minus refunds already linked to it). Supports partial refunds.
 */
class RefundLinker(
    private val window: Duration = Duration.ofDays(90),
    private val minMerchantSimilarity: Double = 0.80,
    private val directions: DirectionResolver = DirectionResolver.DEFAULT,
) {
    companion object {
        val REFUND_WORDS = Regex("(REFUND|REVERSAL|REVERSED|CHARGEBACK|CASHBACK|RETURN|CANCELL?ED|CREDITED BACK)", RegexOption.IGNORE_CASE)
    }

    fun isRefundLike(t: Transaction): Boolean =
        t.type == TxnType.REFUND ||
            (directions.of(t) == TxnDirection.CREDIT && listOfNotNull(t.merchantRaw, t.merchant, t.note).any { REFUND_WORDS.containsMatchIn(it) })

    fun link(refund: Transaction, history: List<Transaction>): RefundLink? {
        if (refund.isDeleted || !isRefundLike(refund)) return null
        val amt = refund.account.abs()
        return history.asSequence()
            .filter { !it.isDeleted && it.id != refund.id && directions.of(it) == TxnDirection.DEBIT && it.refundOfId == null }
            .filter { it.occurredAt <= refund.occurredAt && Duration.between(it.occurredAt, refund.occurredAt) <= window }
            .filter { it.account.currency == amt.currency || it.original.currency == refund.original.currency }
            .mapNotNull { o -> score(refund, o, history) }
            .maxWithOrNull(compareBy<Pair<RefundLink, Long>>({ it.first.confidence }, { -it.second }))
            ?.first
    }

    private fun score(refund: Transaction, o: Transaction, history: List<Transaction>): Pair<RefundLink, Long>? {
        val reasons = ArrayList<String>()
        val sim = maxOf(
            Similarity.merchant(refund.merchantRaw ?: refund.merchant, o.merchantRaw ?: o.merchant),
            Similarity.merchant(refund.merchant, o.merchant),
        )
        val sameRef = Similarity.normaliseReference(refund.reference)?.let { it == Similarity.normaliseReference(o.reference) } == true
        if (sim < minMerchantSimilarity && !sameRef) return null
        if (sameRef) reasons += "same reference"
        if (sim >= minMerchantSimilarity) reasons += "merchant match (${"%.2f".format(sim)})"

        // Compare in the original currency when both share it, else in account currency.
        val useOriginal = refund.original.currency == o.original.currency
        val ref = if (useOriginal) refund.original.abs() else refund.account.abs()
        val orig = if (useOriginal) o.original.abs() else o.account.abs()
        if (ref.currency != orig.currency) return null
        val already = history.filter { !it.isDeleted && it.refundOfId == o.id && it.id != refund.id }
            .map { if (useOriginal) it.original.abs() else it.account.abs() }
            .filter { it.currency == orig.currency }
            .fold(Money.zero(orig.currency)) { a, m -> a + m }
        val refundable = orig - already
        if (ref > refundable) return null
        val partial = ref < refundable
        reasons += if (partial) "partial refund" else "full amount"
        if (!o.accountId.isNullOrEmpty() && o.accountId == refund.accountId) reasons += "same account"
        val days = Duration.between(o.occurredAt, refund.occurredAt).toDays()
        var conf = 0.45 + 0.35 * sim + (if (sameRef) 0.2 else 0.0)
        if (!partial) conf += 0.1
        if (o.accountId != null && o.accountId == refund.accountId) conf += 0.05
        conf -= 0.1 * (days.toDouble() / window.toDays())
        return RefundLink(refund.id, o.id, conf.coerceIn(0.0, 1.0), partial, refundable - ref, reasons) to days
    }
}
