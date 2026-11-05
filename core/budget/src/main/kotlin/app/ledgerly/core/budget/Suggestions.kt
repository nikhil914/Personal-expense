package app.ledgerly.core.budget

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class BudgetSuggestion(
    val categoryId: Id,
    /** Net spend per complete period, oldest first. */
    val history: List<Money>,
    val median: Money,
    val suggested: Money,
)

/** Budget suggestions from history, copy-last-period and percentage templates. */
class BudgetSuggester(
    private val aggregator: SpendAggregator,
    private val calculator: PeriodCalculator,
) {
    /**
     * For each category with spend in the last [months] complete periods (3..6) before the period containing
     * [today]: median monthly spend (missing months count as 0), rounded up nicely. Only categories seen in at
     * least [minActiveMonths] periods are suggested. Categories are taken at the level they were used.
     */
    fun suggest(transactions: List<Transaction>, today: LocalDate, months: Int = 6, minActiveMonths: Int = 2, spec: PeriodSpec = PeriodSpec.MONTHLY): List<BudgetSuggestion> {
        require(months in 3..6) { "Use 3 to 6 months of history" }
        val c = aggregator.currency
        val periods = ArrayList<BudgetPeriod>()
        var p = calculator.previous(spec, calculator.periodFor(spec, today))
        repeat(months) { periods.add(0, p); p = calculator.previous(spec, p) }
        val perPeriod = periods.map { aggregator.spendByCategory(transactions, it) }
        val cats = perPeriod.flatMap { it.keys }.filterNotNull().toSortedSet()
        return cats.mapNotNull { cat ->
            val hist = perPeriod.map { it[cat] ?: Money.zero(c) }
            if (hist.count { it.isPositive } < minActiveMonths) return@mapNotNull null
            val med = median(hist)
            if (!med.isPositive) null else BudgetSuggestion(cat, hist, med, roundNicely(med))
        }
    }

    /** A budget with these suggestions as limits. */
    fun suggestedBudget(id: Id, name: String, suggestions: List<BudgetSuggestion>, rollover: RolloverMode = RolloverMode.NONE): CategoryBudget =
        CategoryBudget(id, name, aggregator.currency, suggestions.map { BudgetLine(it.categoryId, it.suggested, rollover) })

    /** Next period's budget = same lines (limits, rollover settings) as [previous]. */
    fun copyLastPeriod(previous: CategoryBudget, newId: Id = previous.id): CategoryBudget = previous.copy(id = newId)

    /** Next period's limits = what was actually spent last period per line (rounded nicely, never below [floor]). */
    fun fromLastPeriodActuals(previous: BudgetReport, budget: CategoryBudget, floor: Money = Money.zero(budget.currency)): CategoryBudget =
        budget.copy(lines = budget.lines.map { l ->
            val spent = previous.lines.firstOrNull { it.line.id == l.id }?.spent ?: l.limit
            l.copy(limit = MoneyMath.max(roundNicely(MoneyMath.max(spent, Money.zero(budget.currency))), floor))
        })

    companion object {
        fun median(values: List<Money>): Money {
            require(values.isNotEmpty())
            val s = values.sortedBy { it.minor }
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else Money(Math.floorDiv(s[n / 2 - 1].minor + s[n / 2].minor, 2L), s[0].currency)
        }

        /**
         * Round up to a friendly step in major units: <500 → 50, <5,000 → 100, <20,000 → 500, <1,00,000 → 1,000,
         * else 5,000. ₹2,130 → ₹2,200; ₹12,040 → ₹12,500.
         */
        fun roundNicely(m: Money): Money {
            val major = m.toDecimal()
            val step = when {
                major < BigDecimal(500) -> 50
                major < BigDecimal(5_000) -> 100
                major < BigDecimal(20_000) -> 500
                major < BigDecimal(100_000) -> 1_000
                else -> 5_000
            }
            val st = BigDecimal(step)
            val rounded = major.divide(st, 0, RoundingMode.CEILING).multiply(st)
            return Money.of(rounded, m.currency)
        }
    }
}

/** A percentage-of-income template keyed by a stable category key ("housing", "groceries", ...). */
data class BudgetTemplate(val name: String, val allocations: Map<String, BigDecimal>) {
    init { require(allocations.values.fold(BigDecimal.ZERO, BigDecimal::add) <= BigDecimal(100)) { "Allocations exceed 100%" } }
}

object BudgetTemplates {
    val BALANCED = BudgetTemplate("Balanced", mapOf(
        "housing" to BigDecimal(30), "groceries" to BigDecimal(12), "transport" to BigDecimal(8), "utilities" to BigDecimal(6),
        "dining" to BigDecimal(6), "shopping" to BigDecimal(6), "entertainment" to BigDecimal(4), "health" to BigDecimal(4),
        "savings" to BigDecimal(20), "misc" to BigDecimal(4),
    ))
    val FRUGAL = BudgetTemplate("Frugal", mapOf(
        "housing" to BigDecimal(25), "groceries" to BigDecimal(10), "transport" to BigDecimal(6), "utilities" to BigDecimal(5),
        "dining" to BigDecimal(3), "shopping" to BigDecimal(3), "entertainment" to BigDecimal(2), "health" to BigDecimal(4),
        "savings" to BigDecimal(35), "misc" to BigDecimal(7),
    ))
    val STUDENT = BudgetTemplate("Student", mapOf(
        "housing" to BigDecimal(35), "groceries" to BigDecimal(15), "transport" to BigDecimal(10), "education" to BigDecimal(15),
        "dining" to BigDecimal(8), "entertainment" to BigDecimal(5), "savings" to BigDecimal(7), "misc" to BigDecimal(5),
    ))
    val ALL = listOf(BALANCED, FRUGAL, STUDENT)

    /** Apply [template] to [income]; keys that [resolve] cannot map to a category are skipped. Limits are rounded down to whole units. */
    fun apply(template: BudgetTemplate, income: Money, id: Id, resolve: (String) -> Id?, period: PeriodSpec = PeriodSpec.MONTHLY): CategoryBudget {
        val c: CurrencyCode = income.currency
        val lines = template.allocations.mapNotNull { (key, pct) ->
            val cat = resolve(key) ?: return@mapNotNull null
            val amt = income.toDecimal().multiply(pct).divide(BigDecimal(100), 0, RoundingMode.DOWN)
            BudgetLine(cat, Money.of(amt, c))
        }
        return CategoryBudget(id, template.name, c, lines, period)
    }
}
