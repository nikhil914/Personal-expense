package app.ledgerly.core.audit

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An observed balance, e.g. from an SMS "Avl Bal" line, with the time it was true. */
data class BalanceSnapshot(val at: Instant, val balance: Money, val sourceEventId: Id? = null)

data class AccountReconInput(
    val accountId: Id,
    val openingBalance: Money,
    /** Transactions of this account (any order; deleted/failed/void are ignored). */
    val transactions: List<Transaction>,
    val snapshots: List<BalanceSnapshot>,
    /** Balance the opening value refers to; snapshots/txns before it are ignored. Null = beginning of time. */
    val openingAt: Instant? = null,
)

/** Expected vs observed at one snapshot. */
data class SnapshotCheck(
    val snapshot: BalanceSnapshot,
    val expected: Money,
    val observed: Money,
    /** observed - expected, cumulative. */
    val cumulativeGap: Money,
    /** New gap that appeared since the previous checkpoint. */
    val intervalGap: Money,
    /** Start of the interval (previous snapshot or opening); null = opening with no time. */
    val since: Instant?,
    /** Sum of |recorded flows| in the interval. */
    val recordedMovement: Money,
    val transactionCount: Int,
)

/** An unexplained difference in one interval; negative [amount] means money left the account unrecorded. */
data class UnexplainedGap(val accountId: Id, val amount: Money, val since: Instant?, val until: Instant, val message: String)

data class AccountReconciliation(
    val accountId: Id,
    val checks: List<SnapshotCheck>,
    val gaps: List<UnexplainedGap>,
    /** Total movement = recorded flows + unexplained amounts across verified intervals. */
    val totalMovement: Money,
    val unexplained: Money,
    /** 0..100, % of balance movement explained; 100 only if nothing is unexplained. */
    val score: Int,
    /** Recorded transactions after the last snapshot (not yet verifiable). */
    val unverifiedTransactions: Int,
)

data class ReconciliationReport(val accounts: List<AccountReconciliation>, val overallScore: Int) {
    val gaps: List<UnexplainedGap> get() = accounts.flatMap { it.gaps }
}

/**
 * Replays an account from its opening balance and compares the expected balance against observed snapshots.
 *
 * The model's [Transaction] has no explicit direction, so [signedAmount] decides the sign (default:
 * EXPENSE / ATM_WITHDRAWAL out, INCOME / REFUND in, TRANSFER / ADJUSTMENT use the stored sign).
 */
class Reconciler(
    private val zone: ZoneId,
    /** Gaps with |amount| <= tolerance (minor units) are treated as explained (rounding in bank SMS). */
    private val toleranceMinor: Long = 0,
    private val includePending: Boolean = true,
    private val signedAmount: (Transaction) -> Money = ::defaultSigned,
    locale: Locale = Locale.ENGLISH,
) {
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM", locale)

    fun reconcile(input: AccountReconInput): AccountReconciliation {
        val cur = input.openingBalance.currency
        val zero = Money.zero(cur)
        val txns = input.transactions
            .filter { !it.isDeleted && it.status != TxnStatus.FAILED && it.status != TxnStatus.VOID }
            .filter { includePending || it.status != TxnStatus.PENDING }
            .filter { input.openingAt == null || !it.occurredAt.isBefore(input.openingAt) }
            .sortedBy { it.occurredAt }
        val snaps = input.snapshots
            .filter { input.openingAt == null || !it.at.isBefore(input.openingAt) }
            .sortedBy { it.at }
        snaps.forEach { require(it.balance.currency == cur) { "Snapshot currency ${it.balance.currency} != $cur" } }

        var idx = 0
        var expected = input.openingBalance
        var prevGap = zero
        var since: Instant? = input.openingAt
        var total = zero
        var unexplained = zero
        val checks = ArrayList<SnapshotCheck>()
        val gaps = ArrayList<UnexplainedGap>()
        for (s in snaps) {
            var movement = zero
            var count = 0
            while (idx < txns.size && !txns[idx].occurredAt.isAfter(s.at)) {
                val amt = signedAmount(txns[idx])
                require(amt.currency == cur) { "Transaction ${txns[idx].id} not in account currency $cur" }
                expected += amt; movement += amt.abs(); count++; idx++
            }
            val gap = s.balance - expected
            var interval = gap - prevGap
            if (interval.abs().minor <= toleranceMinor) interval = zero
            else prevGap = gap
            checks += SnapshotCheck(s, expected, s.balance, gap, interval, since, movement, count)
            total += movement + interval.abs()
            if (!interval.isZero) {
                unexplained += interval.abs()
                gaps += UnexplainedGap(input.accountId, interval, since, s.at, gapMessage(interval, since))
            }
            since = s.at
        }
        return AccountReconciliation(
            input.accountId, checks, gaps, total, unexplained, score(total, unexplained), txns.size - idx,
        )
    }

    fun reconcileAll(inputs: List<AccountReconInput>): ReconciliationReport {
        val accounts = inputs.map(::reconcile)
        // Overall weight by movement; currencies may differ so weight by score * movement-share in decimal.
        val weights = accounts.map { it.totalMovement.toDecimal().abs() }
        val totalW = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        val overall = if (totalW.signum() == 0) 100 else {
            val explained = accounts.zip(weights).fold(BigDecimal.ZERO) { acc, (a, w) ->
                acc + w * (BigDecimal.ONE - fraction(a.unexplained, a.totalMovement))
            }
            floorPercent(explained.divide(totalW, 10, RoundingMode.HALF_EVEN), accounts.any { !it.unexplained.isZero })
        }
        return ReconciliationReport(accounts, overall)
    }

    private fun fraction(part: Money, whole: Money): BigDecimal =
        if (whole.isZero) BigDecimal.ZERO else BigDecimal(part.minor).divide(BigDecimal(whole.minor), 10, RoundingMode.HALF_EVEN)

    private fun score(total: Money, unexplained: Money): Int =
        if (total.isZero) 100 else floorPercent(BigDecimal.ONE - fraction(unexplained, total), !unexplained.isZero)

    private fun floorPercent(f: BigDecimal, hasGap: Boolean): Int {
        val p = f.multiply(BigDecimal(100)).setScale(0, RoundingMode.FLOOR).toInt().coerceIn(0, 100)
        return if (hasGap) minOf(p, 99) else p
    }

    private fun gapMessage(amount: Money, since: Instant?): String {
        val amt = MoneyFormat.human(amount.abs())
        return if (since == null) "$amt unexplained since opening balance"
        else "$amt unexplained since ${dateFmt.format(since.atZone(zone))}"
    }

    companion object {
        fun defaultSigned(t: Transaction): Money = when (t.type) {
            TxnType.EXPENSE, TxnType.ATM_WITHDRAWAL -> -t.account.abs()
            TxnType.INCOME, TxnType.REFUND -> t.account.abs()
            TxnType.TRANSFER, TxnType.ADJUSTMENT -> t.account
        }
    }
}
