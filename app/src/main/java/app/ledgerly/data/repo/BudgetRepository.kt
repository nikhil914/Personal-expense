package app.ledgerly.data.repo

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType
import app.ledgerly.data.db.BudgetEntity
import app.ledgerly.data.db.BudgetLineEntity
import app.ledgerly.data.db.CategoryEntity
import app.ledgerly.data.db.LedgerlyDatabase
import app.ledgerly.data.db.SeedData
import app.ledgerly.data.mapper.toModel
import app.ledgerly.engine.BudgetService
import app.ledgerly.engine.FinancialPeriod
import app.ledgerly.engine.SafeToSpend
import app.ledgerly.engine.SafeToSpendInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZoneId

/** Spend arithmetic shared by budgets, dashboard and widget. Only base-currency amounts are summed. */
object SpendMath {
    fun isSpend(t: Transaction) = !t.isDeleted && t.type == TxnType.EXPENSE
    fun isRefund(t: Transaction) = !t.isDeleted && t.type == TxnType.REFUND

    /** Expenses minus refunds, in [base]. */
    fun netSpent(txns: List<Transaction>, base: CurrencyCode): Money {
        var minor = 0L
        for (t in txns) {
            if (t.base.currency != base) continue
            if (isSpend(t)) minor += t.base.minor else if (isRefund(t)) minor -= t.base.minor
        }
        return Money(minor, base)
    }

    /** Top-level category id for [categoryId] ("food.delivery" -> "food"). */
    fun topLevel(categoryId: String?, categories: Map<String, CategoryEntity>): String {
        if (categoryId == null) return "other"
        return categories[categoryId]?.parentId ?: categoryId
    }

    fun inCategoryTree(categoryId: String?, root: String, categories: Map<String, CategoryEntity>): Boolean =
        categoryId != null && (categoryId == root || categories[categoryId]?.parentId == root)

    fun spentByTopLevel(txns: List<Transaction>, base: CurrencyCode, categories: Map<String, CategoryEntity>): List<Pair<String, Money>> =
        txns.filter { isSpend(it) && it.base.currency == base }
            .groupBy { topLevel(it.categoryId, categories) }
            .map { (k, v) -> k to Money(v.sumOf { it.base.minor }, base) }
            .sortedByDescending { it.second.minor }
}

data class BudgetLineUi(val categoryId: String, val categoryName: String, val colorHex: String?, val limit: Money, val spent: Money)

data class BudgetOverview(
    val budget: BudgetEntity?,
    val period: FinancialPeriod,
    val total: Money?,
    val spent: Money,
    val lines: List<BudgetLineUi>,
    /** Spend per top-level category id in the period, largest first. */
    val byTopLevel: List<Pair<String, Money>> = emptyList(),
    /** Period transactions (base-currency view) for charts. */
    val transactions: List<Transaction> = emptyList(),
)

data class BudgetCrossing(val label: String, val threshold: Int, val spent: Money, val limit: Money)

class BudgetRepository(
    private val db: LedgerlyDatabase,
    private val budgetService: BudgetService,
) {
    private val dao = db.budgetDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOverview(base: CurrencyCode, period: FinancialPeriod, zone: ZoneId): Flow<BudgetOverview> =
        dao.observeActiveMonthly().flatMapLatest { budget ->
            val linesFlow = if (budget == null) flowOf(emptyList<BudgetLineEntity>()) else dao.observeLines(budget.id)
            combine(
                linesFlow,
                db.transactionDao().observeInRange(period.startInstant(zone), period.endInstant(zone)),
                db.categoryDao().observeAll(),
            ) { lines, rows, cats -> buildOverview(budget, lines, rows.map { it.toModel() }, cats, base, period) }
        }

    private fun buildOverview(
        budget: BudgetEntity?,
        lines: List<BudgetLineEntity>,
        txns: List<Transaction>,
        cats: List<CategoryEntity>,
        base: CurrencyCode,
        period: FinancialPeriod,
    ): BudgetOverview {
        val byId = cats.associateBy { it.id }
        val ccy = budget?.currency?.let { CurrencyCode.of(it) } ?: base
        val lineUis = lines.map { line ->
            val spent = SpendMath.netSpent(txns.filter { SpendMath.inCategoryTree(it.categoryId, line.categoryId, byId) }, ccy)
            BudgetLineUi(line.categoryId, byId[line.categoryId]?.name ?: line.categoryId, byId[line.categoryId]?.colorHex, Money(line.limitMinor + line.rolloverMinor, ccy), spent)
        }
        val total = budget?.totalMinor?.let { Money(it, ccy) }
            ?: if (lines.isNotEmpty()) Money(lineUis.sumOf { it.limit.minor }, ccy) else null
        return BudgetOverview(budget, period, total, SpendMath.netSpent(txns, ccy), lineUis, SpendMath.spentByTopLevel(txns, ccy, byId), txns)
    }

    suspend fun ensureBudget(base: CurrencyCode): BudgetEntity =
        dao.activeMonthly() ?: SeedData.monthlyBudget(base.code).also { dao.upsertBudget(it) }

    suspend fun setLine(base: CurrencyCode, categoryId: String, limit: Money) {
        val b = ensureBudget(base)
        dao.deleteLine(b.id, categoryId)
        dao.putLine(BudgetLineEntity(budgetId = b.id, categoryId = categoryId, limitMinor = limit.minor))
    }

    suspend fun removeLine(base: CurrencyCode, categoryId: String) = dao.deleteLine(ensureBudget(base).id, categoryId)

    suspend fun setTotal(base: CurrencyCode, total: Money?) {
        val b = ensureBudget(base)
        dao.upsertBudget(b.copy(totalMinor = total?.minor, currency = total?.currency?.code ?: b.currency))
    }

    /** Budget thresholds crossed by adding [txn] to the current period. */
    suspend fun crossingsFor(txn: Transaction, period: FinancialPeriod, zone: ZoneId): List<BudgetCrossing> {
        if (!SpendMath.isSpend(txn)) return emptyList()
        val budget = dao.activeMonthly() ?: return emptyList()
        val ccy = CurrencyCode.of(budget.currency)
        if (txn.base.currency != ccy) return emptyList()
        val cats = db.categoryDao().getAll().associateBy { it.id }
        val txns = db.transactionDao().getInRange(period.startInstant(zone), period.endInstant(zone)).map { it.toModel() }
        val withTxn = if (txns.any { it.id == txn.id }) txns else txns + txn
        val out = mutableListOf<BudgetCrossing>()
        val lines = dao.lines(budget.id)
        for (line in lines) {
            if (!SpendMath.inCategoryTree(txn.categoryId, line.categoryId, cats)) continue
            val after = SpendMath.netSpent(withTxn.filter { SpendMath.inCategoryTree(it.categoryId, line.categoryId, cats) }, ccy)
            val before = after - txn.base
            val limit = Money(line.limitMinor + line.rolloverMinor, ccy)
            budgetService.crossedThreshold(limit, before, after)?.let {
                out += BudgetCrossing(cats[line.categoryId]?.name ?: line.categoryId, it, after, limit)
            }
        }
        val total = budget.totalMinor ?: lines.sumOf { it.limitMinor + it.rolloverMinor }.takeIf { lines.isNotEmpty() }
        if (total != null) {
            val after = SpendMath.netSpent(withTxn, ccy)
            budgetService.crossedThreshold(Money(total, ccy), after - txn.base, after)?.let {
                out += BudgetCrossing("Monthly budget", it, after, Money(total, ccy))
            }
        }
        return out
    }

    /** Safe to spend today for the dashboard and widget. */
    suspend fun safeToSpend(base: CurrencyCode, today: LocalDate, period: FinancialPeriod, zone: ZoneId): SafeToSpend? {
        val budget = dao.activeMonthly() ?: return null
        val ccy = CurrencyCode.of(budget.currency)
        val lines = dao.lines(budget.id)
        val total = budget.totalMinor ?: lines.sumOf { it.limitMinor + it.rolloverMinor }.takeIf { lines.isNotEmpty() } ?: return null
        val txns = db.transactionDao().getInRange(period.startInstant(zone), period.endInstant(zone)).map { it.toModel() }
        val todayStart = today.atStartOfDay(zone).toInstant()
        val before = SpendMath.netSpent(txns.filter { it.occurredAt < todayStart }, ccy)
        val todaySpent = SpendMath.netSpent(txns.filter { it.occurredAt >= todayStart }, ccy)
        val committed = db.recurringDao().upcoming(today, period.end.minusDays(1))
            .filter { it.currency == ccy.code }.sumOf { it.amountMinor }
        return budgetService.safeToSpend(
            SafeToSpendInput(today, period.start, period.end, Money(total, ccy), before, todaySpent, Money(committed, ccy)),
        )
    }
}
