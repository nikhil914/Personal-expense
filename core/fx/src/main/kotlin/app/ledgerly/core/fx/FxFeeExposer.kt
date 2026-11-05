package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZoneId

/** FX cost profile of a card, used for "card X would have saved Y" comparisons. */
data class CardFxProfile(
    val name: String,
    /** Markup over market rate, in percent (3.5 = 3.5%). */
    val markupPct: BigDecimal,
    /** Optional fixed fee per foreign transaction (in the card's account currency). */
    val fixedFee: Money? = null,
)

/** A separate FX-markup debit line attributed to the foreign transaction it belongs to. */
data class MarkupAttribution(
    val markupTxnId: Id,
    val originalTxnId: Id,
    val fee: Money,
    /** Fee as % of the original transaction's account amount, scale 2. */
    val pctOfOriginal: BigDecimal,
)

data class CardAlternative(val profile: CardFxProfile, val estimatedFees: Money, val savings: Money, val message: String)

data class CardFxReport(
    val accountId: Id,
    val year: Int,
    val foreignTxnCount: Int,
    /** Total foreign spend in account currency at the market rate (excludes fees). */
    val foreignSpendAtMarket: Money,
    /** Fees embedded in the applied rate plus separately-charged markup lines. */
    val totalFees: Money,
    /** totalFees / foreignSpendAtMarket * 100, scale 2. */
    val effectivePct: BigDecimal,
    /** Alternatives that would have been cheaper, best first. */
    val alternatives: List<CardAlternative>,
)

/**
 * Exposes hidden FX costs: per-transaction messages, separate markup line attribution,
 * and a yearly per-card cost report.
 */
class FxFeeExposer(
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Max delay between a foreign transaction and its separate markup debit. */
    private val markupWindow: Duration = Duration.ofDays(3),
    /** Ignore fees below this % of market value (rounding noise). */
    private val minReportablePct: BigDecimal = BigDecimal("0.10"),
) {
    companion object {
        val MARKUP_PATTERN = Regex(
            "(FOREIGN\\s*(CURRENCY|CUR|CCY|TXN|TRANSACTION)?\\s*(MARK\\s*-?\\s*UP|FEE|CHARGE)|" +
                "FX\\s*(MARK\\s*-?\\s*UP|FEE|CHARGE)|CROSS\\s*-?\\s*(CURRENCY|BORDER)\\s*(MARK\\s*-?\\s*UP|FEE|CHARGE)?|" +
                "DCC\\s*(MARK\\s*-?\\s*UP|FEE)|INTL?\\.?\\s*(TXN|TRANSACTION)?\\s*(FEE|MARK\\s*-?\\s*UP)|" +
                "INTERNATIONAL\\s*(TXN|TRANSACTION)?\\s*(FEE|MARK\\s*-?\\s*UP)|CURRENCY\\s*CONVERSION\\s*(FEE|CHARGE))",
            RegexOption.IGNORE_CASE,
        )

        fun isForeign(t: Transaction) = t.original.currency != t.account.currency
    }

    /**
     * Message like "Your bank charged ₹147 (3.5%) above the market rate", or null when there is no
     * meaningful fee. [extraFee] adds a separately-charged markup line in the account currency.
     */
    fun message(txn: Transaction, extraFee: Money? = null): String? {
        val embedded = txn.fxFee ?: Money.zero(txn.account.currency)
        val fee = if (extraFee != null && extraFee.currency == embedded.currency) embedded + extraFee.abs() else embedded
        val market = marketValue(txn, embedded) ?: return null
        return message(fee, market)
    }

    /** Message for an arbitrary fee vs market value (both in the same currency). */
    fun message(fee: Money, marketValue: Money): String? {
        if (marketValue.isZero) return null
        val pct = fee.toDecimal().multiply(BigDecimal(100)).divide(marketValue.toDecimal().abs(), 4, RoundingMode.HALF_EVEN)
        if (pct.abs() < minReportablePct) return null
        return if (fee.isPositive) {
            "Your bank charged ${FxFormat.short(fee)} (${FxFormat.pct(pct)}) above the market rate"
        } else {
            "You got ${FxFormat.short(fee.abs())} (${FxFormat.pct(pct.abs())}) better than the market rate"
        }
    }

    /** Market value of a foreign transaction in account currency (account - embedded fee, or original*marketRate). */
    fun marketValue(txn: Transaction, embeddedFee: Money? = txn.fxFee): Money? = when {
        txn.marketRate != null -> Converter.applyRate(txn.original.abs(), txn.marketRate!!, txn.account.currency)
        embeddedFee != null -> txn.account.abs() - embeddedFee
        else -> null
    }

    fun isMarkupLine(t: Transaction): Boolean =
        listOfNotNull(t.merchantRaw, t.merchant, t.note).any { MARKUP_PATTERN.containsMatchIn(it) }

    /**
     * Attributes separate markup debits (e.g. "FOREIGN CURRENCY MARKUP" ₹147 two days later) to the foreign
     * transaction on the same card (accountId or last4) within [markupWindow] before it. Prefers the candidate
     * whose implied markup % is most plausible (0.5%..6%), then the nearest in time. Each original gets at most one.
     */
    fun attributeMarkupCharges(transactions: List<Transaction>): List<MarkupAttribution> {
        val live = transactions.filter { !it.isDeleted }
        val lines = live.filter { isMarkupLine(it) }.sortedBy { it.occurredAt }
        val foreign = live.filter { isForeign(it) && !isMarkupLine(it) }
        val used = HashSet<Id>()
        val out = ArrayList<MarkupAttribution>()
        for (line in lines) {
            val candidates = foreign.filter { f ->
                f.id !in used && sameCard(f, line) && f.account.currency == line.account.currency &&
                    !f.occurredAt.isAfter(line.occurredAt) &&
                    Duration.between(f.occurredAt, line.occurredAt) <= markupWindow
            }
            val best = candidates.minWithOrNull(
                compareBy<Transaction>({ plausibility(line.account.abs(), it.account.abs()) })
                    .thenBy { Duration.between(it.occurredAt, line.occurredAt) }
            ) ?: continue
            used += best.id
            val fee = line.account.abs()
            out += MarkupAttribution(line.id, best.id, fee, pctOf(fee, best.account.abs()))
        }
        return out
    }

    private fun plausibility(fee: Money, base: Money): Int {
        if (base.isZero) return 3
        val pct = fee.toDecimal().multiply(BigDecimal(100)).divide(base.toDecimal(), 4, RoundingMode.HALF_EVEN)
        return when {
            pct >= BigDecimal("0.5") && pct <= BigDecimal("6") -> 0
            pct < BigDecimal("0.5") -> 1
            else -> 2
        }
    }

    private fun sameCard(a: Transaction, b: Transaction): Boolean =
        (a.accountId != null && a.accountId == b.accountId) ||
            (a.accountLast4 != null && a.accountLast4 == b.accountLast4)

    private fun pctOf(fee: Money, base: Money): BigDecimal =
        if (base.isZero) BigDecimal.ZERO
        else fee.toDecimal().multiply(BigDecimal(100)).divide(base.toDecimal(), 2, RoundingMode.HALF_EVEN)

    /**
     * Yearly report per card (accountId). Foreign spend = market value of foreign transactions; fees =
     * embedded fxFee + attributed markup lines. [alternatives] are compared to show potential savings.
     */
    fun yearlyReport(
        year: Int,
        transactions: List<Transaction>,
        alternatives: List<CardFxProfile> = emptyList(),
    ): List<CardFxReport> {
        val inYear = transactions.filter { !it.isDeleted && it.occurredAt.atZone(zone).year == year }
        val attributions = attributeMarkupCharges(inYear).associateBy { it.originalTxnId }
        val foreign = inYear.filter { isForeign(it) && !isMarkupLine(it) && it.accountId != null }
        return foreign.groupBy { it.accountId!! }.map { (acct, txns) ->
            val cur = txns.first().account.currency
            val sameCur = txns.filter { it.account.currency == cur }
            var spend = Money.zero(cur)
            var fees = Money.zero(cur)
            for (t in sameCur) {
                val embedded = t.fxFee?.takeIf { it.currency == cur } ?: Money.zero(cur)
                spend += marketValue(t, embedded) ?: (t.account.abs() - embedded)
                fees += embedded
                attributions[t.id]?.let { fees += it.fee }
            }
            val eff = pctOf(fees, spend)
            val alts = alternatives.mapNotNull { p ->
                val pctCost = spend.times(p.markupPct.divide(BigDecimal(100)))
                val fixed = p.fixedFee?.takeIf { it.currency == cur }?.times(sameCur.size) ?: Money.zero(cur)
                val est = pctCost + fixed
                val saved = fees - est
                if (!saved.isPositive) null
                else CardAlternative(p, est, saved, "${p.name} would have saved ${FxFormat.short(saved)} in $year")
            }.sortedByDescending { it.savings.minor }
            CardFxReport(acct, year, sameCur.size, spend, fees, eff, alts)
        }.sortedBy { it.accountId }
    }
}
