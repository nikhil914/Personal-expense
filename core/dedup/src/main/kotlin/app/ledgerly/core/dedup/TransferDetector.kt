package app.ledgerly.core.dedup

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnDirection
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

enum class TransferKind { OWN_TRANSFER, CARD_BILL_PAYMENT }

data class TransferSuggestion(
    val debitId: Id,
    val creditId: Id,
    val kind: TransferKind,
    val confidence: Double,
    val reasons: List<String>,
    /** Deterministic group id to set as transferGroupId on both legs. */
    val transferGroupId: Id,
)

/**
 * Finds pairs of own-account transactions that are one transfer: a debit in account A and a credit in account B
 * for the same amount (or FX-equivalent within [fxTolerancePct] using [rate]) within [window].
 * Also recognises credit-card bill payments ("payment received" on the card + debit from the bank).
 */
class TransferDetector(
    private val window: Duration = Duration.ofDays(2),
    private val fxTolerancePct: BigDecimal = BigDecimal("2.0"),
    private val minConfidence: Double = 0.5,
    private val rate: ((CurrencyCode, CurrencyCode, Instant) -> BigDecimal?)? = null,
    private val directions: DirectionResolver = DirectionResolver.DEFAULT,
) {
    companion object {
        val CARD_PAYMENT_RECEIVED = Regex(
            "(PAYMENT\\s*(RECEIVED|CREDITED|SUCCESSFUL|THANK)|THANK\\s*YOU\\s*FOR\\s*(YOUR\\s*)?PAYMENT|" +
                "PAYMENT\\s*(OF|FOR).*(CREDIT\\s*CARD|CARD)\\s*(A/?C|ACCOUNT|ENDING)?|BBPS|AUTOPAY\\s*PAYMENT)",
            RegexOption.IGNORE_CASE,
        )
        val CARD_PAYMENT_DEBIT = Regex(
            "(CREDIT\\s*CARD|\\bCC\\b|CARD\\s*(BILL|PAYMENT|DUES)|BILLDESK|BBPS|CRED\\b|CRED\\s*CLUB|AUTOPAY|" +
                "PAYZAPP|CARDS?\\s*PAYMENT)",
            RegexOption.IGNORE_CASE,
        )
        val TRANSFER_WORDS = Regex(
            "(SELF|OWN\\s*A/?C|TRANSFER|TRF|NEFT|IMPS|RTGS|FUND\\s*TRANSFER|SWEEP|TOP\\s*-?\\s*UP|ADD\\s*MONEY|WALLET\\s*LOAD)",
            RegexOption.IGNORE_CASE,
        )

        fun groupId(debitId: Id, creditId: Id): Id = "tg_${debitId}_$creditId"
    }

    private fun text(t: Transaction) = listOfNotNull(t.merchantRaw, t.merchant, t.note).joinToString(" ")

    /** Detect transfer pairs among [txns]. [accounts] (by id) enables card-bill detection by account type. */
    fun detect(txns: List<Transaction>, accounts: Map<Id, Account> = emptyMap()): List<TransferSuggestion> {
        val live = txns.filter { !it.isDeleted && it.transferGroupId == null && it.accountId != null && it.refundOfId == null }
        val debits = live.filter { dir(it, accounts) == TxnDirection.DEBIT }
        val credits = live.filter { dir(it, accounts) == TxnDirection.CREDIT }
        val all = ArrayList<TransferSuggestion>()
        for (d in debits) for (c in credits) score(d, c, accounts)?.let { all += it }
        // Greedy one-to-one assignment by confidence, then closeness.
        val usedD = HashSet<Id>(); val usedC = HashSet<Id>()
        return all.sortedByDescending { it.confidence }.filter {
            if (it.debitId in usedD || it.creditId in usedC) false else { usedD += it.debitId; usedC += it.creditId; true }
        }
    }

    /** Best transfer counterpart for a single new transaction among [others]. */
    fun detectFor(txn: Transaction, others: List<Transaction>, accounts: Map<Id, Account> = emptyMap()): TransferSuggestion? {
        val d = dir(txn, accounts) ?: return null
        return others.filter { !it.isDeleted && it.id != txn.id && it.transferGroupId == null && it.accountId != null }
            .mapNotNull { o ->
                when (d) {
                    TxnDirection.DEBIT -> if (dir(o, accounts) == TxnDirection.CREDIT) score(txn, o, accounts) else null
                    TxnDirection.CREDIT -> if (dir(o, accounts) == TxnDirection.DEBIT) score(o, txn, accounts) else null
                }
            }.maxByOrNull { it.confidence }
    }

    private fun dir(t: Transaction, accounts: Map<Id, Account>): TxnDirection? {
        directions.of(t)?.let { return it }
        // TRANSFER/ADJUSTMENT: fall back to the sign of the amount (negative = outflow).
        return if (t.account.isNegative) TxnDirection.DEBIT else if (t.account.isPositive) TxnDirection.CREDIT else null
    }

    private fun score(d: Transaction, c: Transaction, accounts: Map<Id, Account>): TransferSuggestion? {
        if (d.accountId == c.accountId) return null
        val dt = Duration.between(d.occurredAt, c.occurredAt).abs()
        if (dt > window) return null
        val reasons = ArrayList<String>()
        var s: Double
        val da = d.account.abs(); val ca = c.account.abs()
        if (da.currency == ca.currency) {
            if (da != ca) return null
            s = 0.5; reasons += "same amount"
        } else {
            val r = rate?.invoke(da.currency, ca.currency, d.occurredAt) ?: return null
            val expected = da.toDecimal().multiply(r)
            if (expected.signum() == 0) return null
            val diffPct = ca.toDecimal().subtract(expected).abs().multiply(BigDecimal(100))
                .divide(expected, 4, RoundingMode.HALF_EVEN)
            if (diffPct > fxTolerancePct) return null
            s = 0.4 - 0.1 * (diffPct.toDouble() / fxTolerancePct.toDouble()); reasons += "FX-equivalent amount (${diffPct.setScale(2, RoundingMode.HALF_UP)}% off market)"
        }
        s += 0.2 * (1.0 - dt.toMillis().toDouble() / window.toMillis()); reasons += "within ${dt.toHours()}h"

        val cType = accounts[c.accountId]?.type
        val dType = accounts[d.accountId]?.type
        val cardSide = cType == AccountType.CREDIT_CARD || CARD_PAYMENT_RECEIVED.containsMatchIn(text(c))
        val bankDebitForCard = CARD_PAYMENT_DEBIT.containsMatchIn(text(d))
        var kind = TransferKind.OWN_TRANSFER
        if (cardSide && dType != AccountType.CREDIT_CARD) {
            kind = TransferKind.CARD_BILL_PAYMENT
            if (cType == AccountType.CREDIT_CARD) { s += 0.1; reasons += "credit lands on a credit card" }
            if (CARD_PAYMENT_RECEIVED.containsMatchIn(text(c))) { s += 0.1; reasons += "card says payment received" }
            if (bankDebitForCard) { s += 0.1; reasons += "bank debit mentions card payment" }
        } else {
            if (TRANSFER_WORDS.containsMatchIn(text(d)) || TRANSFER_WORDS.containsMatchIn(text(c))) {
                s += 0.15; reasons += "transfer keywords"
            }
            if (accounts.containsKey(d.accountId) && accounts.containsKey(c.accountId)) { s += 0.05; reasons += "both own accounts" }
        }
        if (d.type == app.ledgerly.core.model.TxnType.TRANSFER || c.type == app.ledgerly.core.model.TxnType.TRANSFER) {
            s += 0.1; reasons += "typed as transfer"
        }
        val conf = s.coerceIn(0.0, 1.0)
        if (conf < minConfidence) return null
        return TransferSuggestion(d.id, c.id, kind, conf, reasons, groupId(d.id, c.id))
    }

}
