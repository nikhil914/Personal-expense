package app.ledgerly.core.insights

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

data class Purchase(val amount: Money, val categoryId: Id? = null, val date: LocalDate, val label: String = "Purchase")

data class SavingsGoal(
    val id: Id,
    val name: String,
    val target: Money,
    val saved: Money,
    /** Planned monthly contribution. */
    val monthlyContribution: Money,
)

enum class Verdict { YES, CAUTION, NO }

data class GoalImpact(val goal: SavingsGoal, val delayDays: Int)

data class AffordabilityResult(
    val verdict: Verdict,
    val lines: List<String>,
    val budgetLimit: Money?,
    val budgetRemainingBefore: Money?,
    val budgetRemainingAfter: Money?,
    val lowestBalanceBefore: Money,
    val lowestBalanceAfter: Money,
    val lowestDateAfter: LocalDate?,
    val goalImpacts: List<GoalImpact>,
)

data class AffordabilityConfig(
    /** Lowest projected balance below this (minor units) gives CAUTION. */
    val safetyBufferMinor: Long = 0,
    /** Purchase consuming more than this share of the remaining category budget gives CAUTION. */
    val budgetCautionShare: Double = 0.8,
    val horizonDays: Int = 30,
)

/** "Can I afford it?": budget impact, forecast lowest balance, goal delay and a verdict with explanation lines. */
class AffordabilityAdvisor(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val forecaster: CashFlowForecaster = CashFlowForecaster(clock),
    private val config: AffordabilityConfig = AffordabilityConfig(),
) {
    private val zone get() = clock.zone

    /**
     * @param budgetLimits monthly limit per category id (base currency).
     * @param categoryName optional resolver for nicer explanation lines.
     */
    fun assess(
        purchase: Purchase,
        currentBalance: Money,
        history: Collection<Transaction>,
        budgetLimits: Map<Id, Money> = emptyMap(),
        goals: List<SavingsGoal> = emptyList(),
        recurring: List<RecurringSeries>? = null,
        categoryName: (Id) -> String? = { null },
    ): AffordabilityResult {
        val today = LocalDate.now(clock)
        val amt = purchase.amount.abs()
        val lines = mutableListOf<String>()
        var verdict = Verdict.YES
        fun worsen(v: Verdict) { if (v.ordinal > verdict.ordinal) verdict = v }

        // 1. Category budget
        val limit = purchase.categoryId?.let { budgetLimits[it] }
        var before: Money? = null
        var after: Money? = null
        if (limit != null) {
            val ym = YearMonth.from(purchase.date)
            val spent = history.filter {
                it.isSpend() && it.base.currency == limit.currency && YearMonth.from(it.localDate(zone)) == ym &&
                    (it.categoryId == purchase.categoryId || it.splits.any { s -> s.categoryId == purchase.categoryId })
            }.sumOf { t ->
                if (t.splits.isNotEmpty() && t.categoryId != purchase.categoryId)
                    t.splits.filter { it.categoryId == purchase.categoryId }.sumOf { it.amount.abs().minor }
                else t.baseAbs.minor
            }
            before = Money(limit.minor - spent, limit.currency)
            after = Money(before.minor - amt.minor, limit.currency)
            val cat = categoryName(purchase.categoryId) ?: "this category"
            when {
                after.isNegative -> {
                    worsen(Verdict.CAUTION)
                    lines += "This puts $cat ${MoneyFormat.format(-after)} over its ${MoneyFormat.format(limit)} budget for ${ym.month.name.lowercase().replaceFirstChar { it.uppercase() }}."
                }
                before.isPositive && amt.minor > before.minor * config.budgetCautionShare -> {
                    worsen(Verdict.CAUTION)
                    lines += "It uses most of what's left in $cat: ${MoneyFormat.format(after)} would remain of ${MoneyFormat.format(limit)}."
                }
                else -> lines += "$cat budget: ${MoneyFormat.format(after)} left after this (of ${MoneyFormat.format(limit)})."
            }
        }

        // 2. Forecast
        val horizon = maxOf(config.horizonDays, (java.time.temporal.ChronoUnit.DAYS.between(today, purchase.date) + 7).toInt()).coerceAtMost(366)
        val series = recurring ?: RecurringDetector(clock).detect(history)
        val base = forecaster.forecast(currentBalance, history, horizon, series)
        val withP = forecaster.forecast(currentBalance, history, horizon, series, listOf(PlannedEvent(purchase.date, -amt, purchase.label)))
        val lowAfter = withP.lowestBalance
        val lowDate = withP.lowestDay?.date
        when {
            lowAfter.isNegative -> {
                worsen(Verdict.NO)
                lines += "Your balance would go negative: lowest ${MoneyFormat.format(lowAfter)} around ${lowDate?.shortLabel()}."
            }
            lowAfter.minor < config.safetyBufferMinor -> {
                worsen(Verdict.CAUTION)
                lines += "Your balance would dip to ${MoneyFormat.format(lowAfter)} around ${lowDate?.shortLabel()}, below your safety buffer."
            }
            else -> lines += "Lowest projected balance stays at ${MoneyFormat.format(lowAfter)} (${lowDate?.shortLabel()})."
        }

        // 3. Goals
        val impacts = goals.filter { it.monthlyContribution.isPositive && it.saved.minor < it.target.minor && it.monthlyContribution.currency == amt.currency }
            .map { g ->
                val daily = g.monthlyContribution.minor / 30.4375
                GoalImpact(g, ceil(amt.minor / daily).toInt())
            }
        for (gi in impacts) lines += "If taken from \"${gi.goal.name}\", that goal is delayed by about ${gi.delayDays} days."

        val header = when (verdict) {
            Verdict.YES -> "Yes, you can afford ${MoneyFormat.format(amt)}."
            Verdict.CAUTION -> "You can, but be careful with ${MoneyFormat.format(amt)}."
            Verdict.NO -> "Not right now: ${MoneyFormat.format(amt)} would overdraw you."
        }
        return AffordabilityResult(
            verdict, listOf(header) + lines, limit, before, after,
            base.lowestBalance, lowAfter, lowDate, impacts,
        )
    }
}
