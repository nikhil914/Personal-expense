package app.ledgerly.core.budget

import app.ledgerly.core.model.Category
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** One budget-relevant amount: positive = spend, negative = refund. [categoryId] null = uncategorised. */
data class SpendEntry(val transactionId: Id, val date: LocalDate, val categoryId: Id?, val amount: Money)

/**
 * Turns transactions into per-category spend using these rules:
 * - uses the transaction's `base` amount (already converted to the reporting currency)
 * - transfers (type TRANSFER or a `transferGroupId`) and adjustments are excluded; income is not spend
 * - refunds reduce spend in their own category, or the refunded transaction's category
 * - split transactions count per split (split amounts are scaled proportionally onto `base` when they are in
 *   another currency or don't add up exactly)
 * - deleted, FAILED and VOID transactions are ignored
 */
class SpendAggregator(
    categories: List<Category>,
    val zone: ZoneId,
    val currency: CurrencyCode,
) {
    private val byId = categories.associateBy { it.id }
    private val children: Map<Id, List<Id>> = categories.filter { it.parentId != null }.groupBy({ it.parentId!! }, { it.id })

    fun category(id: Id): Category? = byId[id]
    fun parentOf(id: Id): Id? = byId[id]?.parentId

    /** [id] and all its descendants. */
    fun withDescendants(id: Id): Set<Id> {
        val out = LinkedHashSet<Id>(); val stack = ArrayDeque(listOf(id))
        while (stack.isNotEmpty()) { val c = stack.removeLast(); if (out.add(c)) stack.addAll(children[c].orEmpty()) }
        return out
    }

    /** Ancestors of [id], nearest first. */
    fun ancestors(id: Id): List<Id> {
        val out = ArrayList<Id>(); var p = parentOf(id)
        while (p != null && p !in out) { out += p; p = parentOf(p) }
        return out
    }

    fun localDate(t: Transaction): LocalDate = t.occurredAt.atZone(zone).toLocalDate()

    private fun live(t: Transaction) = !t.isDeleted && t.status != TxnStatus.FAILED && t.status != TxnStatus.VOID

    fun isSpend(t: Transaction): Boolean = live(t) && t.transferGroupId == null &&
        (t.type == TxnType.EXPENSE || t.type == TxnType.ATM_WITHDRAWAL || t.type == TxnType.REFUND)

    fun isIncome(t: Transaction): Boolean = live(t) && t.transferGroupId == null && t.type == TxnType.INCOME

    fun entries(transactions: List<Transaction>, period: BudgetPeriod? = null): List<SpendEntry> {
        val all = transactions.associateBy { it.id }
        val out = ArrayList<SpendEntry>()
        for (t in transactions) {
            if (!isSpend(t)) continue
            val date = localDate(t)
            if (period != null && date !in period) continue
            require(t.base.currency == currency) { "Transaction ${t.id} base ${t.base.currency} != $currency" }
            val sign = if (t.type == TxnType.REFUND) -1 else 1
            val total = t.base.abs()
            val fallbackCat = t.categoryId ?: t.refundOfId?.let { all[it]?.categoryId }
            val splitSource = if (t.splits.isEmpty() && t.type == TxnType.REFUND) t.refundOfId?.let { all[it]?.splits }.orEmpty() else t.splits
            if (splitSource.isEmpty()) {
                out += SpendEntry(t.id, date, fallbackCat, total * sign)
            } else {
                allocate(total, splitSource.map { it.amount }).forEachIndexed { i, amt ->
                    out += SpendEntry(t.id, date, splitSource[i].categoryId, amt * sign)
                }
            }
        }
        return out
    }

    /** Split [total] in proportion to [weights]; exact when weights are in [currency] and sum to total. */
    internal fun allocate(total: Money, weights: List<Money>): List<Money> {
        if (weights.all { it.currency == currency } && weights.sumOf { it.minor } == total.minor) return weights
        val w = weights.map { BigDecimal(it.minor).abs() }
        val sumW = w.fold(BigDecimal.ZERO, BigDecimal::add)
        if (sumW.signum() == 0) return weights.mapIndexed { i, _ -> if (i == 0) total else Money.zero(currency) }
        val parts = w.map { MoneyMath.scale(total, it, sumW) }.toMutableList()
        val diff = total.minor - parts.sumOf { it.minor }
        parts[parts.lastIndex] = Money(parts.last().minor + diff, currency)
        return parts
    }

    /** Net spend per category (direct, not rolled up). Key null = uncategorised. */
    fun spendByCategory(transactions: List<Transaction>, period: BudgetPeriod? = null): Map<Id?, Money> =
        entries(transactions, period).groupBy { it.categoryId }.mapValues { (_, v) -> MoneyMath.sum(v.map { it.amount }, currency) }

    /** Spend in [categoryId] plus (optionally) all its descendants, from a direct map. */
    fun rolledUp(direct: Map<Id?, Money>, categoryId: Id, includeChildren: Boolean = true): Money {
        val ids = if (includeChildren) withDescendants(categoryId) else setOf(categoryId)
        return MoneyMath.sum(ids.mapNotNull { direct[it] }, currency)
    }

    fun totalSpend(transactions: List<Transaction>, period: BudgetPeriod? = null): Money =
        MoneyMath.sum(entries(transactions, period).map { it.amount }, currency)

    fun income(transactions: List<Transaction>, period: BudgetPeriod? = null): Money =
        MoneyMath.sum(transactions.filter { isIncome(it) && (period == null || localDate(it) in period) }.map {
            require(it.base.currency == currency); it.base.abs()
        }, currency)

    /** Daily net spend for categories in [categoryIds] (null = all). */
    fun dailySpend(transactions: List<Transaction>, period: BudgetPeriod?, categoryIds: Set<Id>? = null): Map<LocalDate, Money> =
        entries(transactions, period).filter { categoryIds == null || it.categoryId in categoryIds }
            .groupBy { it.date }.mapValues { (_, v) -> MoneyMath.sum(v.map { it.amount }, currency) }
}
