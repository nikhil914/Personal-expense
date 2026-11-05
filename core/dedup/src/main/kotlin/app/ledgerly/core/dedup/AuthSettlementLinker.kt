package app.ledgerly.core.dedup

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

data class AuthSettlementMatch(val txnId: Id, val confidence: Double, val reasons: List<MatchReason>)

/**
 * Card pre-authorisation (hotel, car rental, fuel) later settled for a different amount and/or currency within
 * [window]: the pending transaction is updated instead of creating a new one.
 *
 * @param rate optional market rate function (from, to, at) -> rate, used to sanity-check cross-currency amounts.
 */
class AuthSettlementLinker(
    private val window: Duration = Duration.ofDays(7),
    private val minMerchantSimilarity: Double = 0.80,
    /** Settlement may be at most this multiple of the auth (tips, fuel top-ups). */
    private val maxRatio: BigDecimal = BigDecimal("1.30"),
    /** ... and at least this fraction (hotel holds are often much larger than the final bill). */
    private val minRatio: BigDecimal = BigDecimal("0.10"),
    private val rate: ((CurrencyCode, CurrencyCode, Instant) -> BigDecimal?)? = null,
) {
    fun isSettlement(c: Candidate): Boolean =
        !c.isCredit && c.kind in setOf(MessageKind.CARD_SETTLEMENT, MessageKind.DEBIT)

    fun find(c: Candidate, existing: List<Transaction>): AuthSettlementMatch? {
        if (c.isCredit) return null
        return existing.asSequence()
            .filter { !it.isDeleted && it.status == TxnStatus.PENDING }
            .mapNotNull { score(c, it) }
            .maxByOrNull { it.confidence }
    }

    private fun score(c: Candidate, t: Transaction): AuthSettlementMatch? {
        val sameAccount = (c.accountId != null && c.accountId == t.accountId) ||
            (c.accountLast4 != null && c.accountLast4 == t.accountLast4)
        if (!sameAccount) return null
        if (c.occurredAt.isBefore(t.occurredAt) || Duration.between(t.occurredAt, c.occurredAt) > window) return null
        val reasons = mutableListOf(MatchReason.AUTH_SETTLEMENT, if (c.accountId != null && c.accountId == t.accountId) MatchReason.SAME_ACCOUNT else MatchReason.SAME_LAST4)
        val refEq = Similarity.normaliseReference(c.reference)?.let { it == Similarity.normaliseReference(t.reference) } == true
        val sim = maxOf(Similarity.merchant(c.merchantRaw, t.merchantRaw), Similarity.merchant(c.merchantRaw, t.merchant))
        if (!refEq && sim < minMerchantSimilarity) return null
        if (refEq) reasons += MatchReason.EXACT_REFERENCE
        if (sim >= minMerchantSimilarity) reasons += MatchReason.MERCHANT_SIMILAR
        val ratio = ratio(c, t)
        if (ratio != null && (ratio > maxRatio || ratio < minRatio)) return null
        var conf = 0.55 + 0.3 * sim + (if (refEq) 0.15 else 0.0)
        if (ratio != null && ratio.compareTo(BigDecimal.ONE) == 0) conf += 0.05
        return AuthSettlementMatch(t.id, conf.coerceAtMost(1.0), reasons)
    }

    /** settlement / auth in comparable currency, or null when not comparable. */
    private fun ratio(c: Candidate, t: Transaction): BigDecimal? {
        val auth = t.original.abs()
        val settle = sequenceOf(c.amount, c.accountAmount).filterNotNull().map { it.abs() }
            .firstOrNull { it.currency == auth.currency }
            ?: t.account.abs().let { acc -> sequenceOf(c.amount, c.accountAmount).filterNotNull().map { it.abs() }
                .firstOrNull { it.currency == acc.currency }?.let { return div(it, acc) } }
            ?: rate?.invoke(c.amount.currency, auth.currency, c.occurredAt)?.let {
                return div(Money.of(c.amount.abs().toDecimal().multiply(it), auth.currency), auth)
            }
            ?: return null
        return div(settle, auth)
    }

    private fun div(a: Money, b: Money): BigDecimal? =
        if (b.isZero) null else a.toDecimal().divide(b.toDecimal(), 4, RoundingMode.HALF_EVEN)

    /** Update the pending auth with the settled amount; keeps purchase time and user-locked fields. */
    fun apply(auth: Transaction, settlement: Candidate, locked: Set<String> = emptySet()): MergeResult {
        val changed = LinkedHashSet<String>()
        var t = auth
        val sign = if (auth.original.isNegative) -1 else 1
        fun signed(m: Money) = Money(m.abs().minor * sign, m.currency)
        if (Fields.ORIGINAL !in locked) {
            t = t.copy(original = signed(settlement.amount)); changed += Fields.ORIGINAL
        }
        if (Fields.ACCOUNT !in locked) {
            val acc = settlement.accountAmount ?: settlement.amount.takeIf { it.currency == auth.account.currency }
            if (acc != null) { t = t.copy(account = signed(acc)); changed += Fields.ACCOUNT }
            else if (settlement.amount.currency != auth.account.currency) {
                // Account amount unknown yet: keep estimate, FX re-enrichment required.
            }
            if (t.base.currency == t.account.currency) t = t.copy(base = t.account)
            else if (t.base.currency == t.original.currency) t = t.copy(base = t.original)
        }
        if (Fields.STATUS !in locked) { t = t.copy(status = TxnStatus.POSTED); changed += Fields.STATUS }
        if (t.reference == null && settlement.reference != null && Fields.REFERENCE !in locked) {
            t = t.copy(reference = settlement.reference); changed += Fields.REFERENCE
        }
        if (t.merchantRaw == null && settlement.merchantRaw != null && Fields.MERCHANT_RAW !in locked) {
            t = t.copy(merchantRaw = settlement.merchantRaw); changed += Fields.MERCHANT_RAW
        }
        t = t.copy(
            sourceEventIds = (t.sourceEventIds + settlement.sourceEventId).distinct(),
            sourceChannels = t.sourceChannels + settlement.channel,
        )
        return MergeResult(t, changed, settlement.balanceAfter, settlement.lineItems, needsFxReenrich = true)
    }

}
