package app.ledgerly.core.budget

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import java.time.LocalDate

enum class BudgetMethod { CATEGORY, ENVELOPE, FIFTY_THIRTY_TWENTY, FLEXIBLE }

enum class RolloverMode { NONE, SURPLUS_ONLY, SURPLUS_AND_DEFICIT }

/** One limit. With [includeChildren] a parent category line rolls up all descendant spend. */
data class BudgetLine(
    val categoryId: Id,
    val limit: Money,
    val rollover: RolloverMode = RolloverMode.NONE,
    val includeChildren: Boolean = true,
    val id: Id = categoryId,
)

data class CategoryBudget(
    val id: Id,
    val name: String,
    val currency: CurrencyCode,
    val lines: List<BudgetLine>,
    val period: PeriodSpec = PeriodSpec.MONTHLY,
    val method: BudgetMethod = BudgetMethod.CATEGORY,
) {
    init {
        require(lines.map { it.id }.toSet().size == lines.size) { "Duplicate line ids" }
        lines.forEach { require(it.limit.currency == currency) { "Line ${it.id} currency mismatch" } }
    }
}

data class LineStatus(
    val line: BudgetLine,
    val limit: Money,
    val carryIn: Money,
    /** limit + carryIn. */
    val available: Money,
    val spent: Money,
    val remaining: Money,
    val percentUsed: Double,
    val alertLevel: Int?,
    val pace: PaceProjection?,
) {
    val isOver: Boolean get() = remaining.isNegative
}

data class BudgetReport(
    val budgetId: Id,
    val period: BudgetPeriod,
    val lines: List<LineStatus>,
    /** Totals over top-level lines only (a child line under a rolled-up parent isn't double counted). */
    val totalAvailable: Money,
    val totalSpent: Money,
    val totalRemaining: Money,
    /** Carry into the next period per line id, per each line's rollover mode. */
    val carryOut: Map<Id, Money>,
) {
    fun line(id: Id): LineStatus = lines.first { it.line.id == id }
}

/** Evaluates category budgets with rollover, alerts and pace projection. */
class CategoryBudgetEngine(
    private val aggregator: SpendAggregator,
    private val calculator: PeriodCalculator,
    private val alerts: AlertEvaluator = AlertEvaluator(),
    private val pace: PaceProjector = PaceProjector(),
    private val categoryName: (Id) -> String = { aggregator.category(it)?.name ?: it },
) {
    /**
     * Evaluate one period. [today] enables pace projection (only when inside the period).
     * [carryIn] is per line id (from the previous report's [BudgetReport.carryOut]).
     */
    fun evaluate(
        budget: CategoryBudget,
        period: BudgetPeriod,
        transactions: List<Transaction>,
        carryIn: Map<Id, Money> = emptyMap(),
        today: LocalDate? = null,
    ): BudgetReport {
        val c = budget.currency
        val zero = Money.zero(c)
        val direct = aggregator.spendByCategory(transactions, period)
        val statuses = budget.lines.map { line ->
            val carry = carryIn[line.id] ?: zero
            val available = line.limit + carry
            val spent = aggregator.rolledUp(direct, line.categoryId, line.includeChildren)
            val remaining = available - spent
            val pct = MoneyMath.percent(spent, available)
            val p = if (today != null && today in period) pace.project(period, today, spent, available, categoryName(line.categoryId)) else null
            LineStatus(line, line.limit, carry, available, spent, remaining, pct, alerts.level(pct), p)
        }
        val rolledParents = budget.lines.filter { it.includeChildren }.map { it.categoryId }.toSet()
        val top = statuses.filter { s -> aggregator.ancestors(s.line.categoryId).none { it in rolledParents } }
        val carryOut = statuses.associate { s ->
            s.line.id to when (s.line.rollover) {
                RolloverMode.NONE -> zero
                RolloverMode.SURPLUS_ONLY -> MoneyMath.max(s.remaining, zero)
                RolloverMode.SURPLUS_AND_DEFICIT -> s.remaining
            }
        }
        return BudgetReport(
            budget.id, period, statuses,
            MoneyMath.sum(top.map { it.available }, c), MoneyMath.sum(top.map { it.spent }, c),
            MoneyMath.sum(top.map { it.remaining }, c), carryOut,
        )
    }

    /** Evaluate [count] consecutive periods from the one containing [from], carrying rollover forward. */
    fun evaluateSeries(
        budget: CategoryBudget,
        from: LocalDate,
        count: Int,
        transactions: List<Transaction>,
        initialCarry: Map<Id, Money> = emptyMap(),
        today: LocalDate? = null,
    ): List<BudgetReport> {
        var carry = initialCarry
        return calculator.series(budget.period, from, count).map { p ->
            evaluate(budget, p, transactions, carry, today).also { carry = it.carryOut }
        }
    }

    /** Report for the period containing [today], with rollover replayed since the period containing [since]. */
    fun current(budget: CategoryBudget, since: LocalDate, today: LocalDate, transactions: List<Transaction>): BudgetReport {
        var p = calculator.periodFor(budget.period, since)
        var carry: Map<Id, Money> = emptyMap()
        while (true) {
            val r = evaluate(budget, p, transactions, carry, today)
            if (today in p || p.start.isAfter(today)) return r
            carry = r.carryOut
            p = calculator.next(budget.period, p)
        }
    }

    fun crossings(previous: BudgetReport?, current: BudgetReport): List<AlertCrossing> = alerts.crossings(previous, current)
}
