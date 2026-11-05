package app.ledgerly.core.budget

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import java.time.LocalDate

enum class FlexBucket { FIXED, FLEXIBLE, NON_MONTHLY }

/**
 * "Flex" budgeting: plan fixed costs, set aside non-monthly costs (as sinking funds), and everything left
 * is one flexible pot. Categories not in [mapping] (nor via an ancestor) are FLEXIBLE.
 */
data class FlexibleBudgetPlan(
    val income: Money,
    val fixedPlanned: Map<Id, Money>,
    val nonMonthly: List<SinkingFund>,
    val mapping: Map<Id, FlexBucket> = fixedPlanned.keys.associateWith { FlexBucket.FIXED },
)

data class FlexibleReport(
    val fixedPlanned: Money,
    val fixedSpent: Money,
    /** Fixed reserved = per category max(planned, spent). */
    val fixedReserved: Money,
    val nonMonthlyContribution: Money,
    val nonMonthlySpent: Money,
    val flexibleAvailable: Money,
    val flexibleSpent: Money,
    val flexibleRemaining: Money,
    val daysLeft: Int,
    val flexiblePerDay: Money,
)

class FlexibleBudgetEvaluator(
    private val sinking: SinkingFundCalculator,
    private val parentOf: (Id) -> Id? = { null },
) {
    fun bucketOf(plan: FlexibleBudgetPlan, cat: Id?): FlexBucket {
        var c = cat; val seen = HashSet<Id>()
        while (c != null && seen.add(c)) { plan.mapping[c]?.let { return it }; c = parentOf(c) }
        return FlexBucket.FLEXIBLE
    }

    fun evaluate(plan: FlexibleBudgetPlan, period: BudgetPeriod, today: LocalDate, spendByCategory: Map<Id?, Money>): FlexibleReport {
        val c = plan.income.currency
        val z = Money.zero(c)
        val fixedSpentByCat = HashMap<Id, Money>()
        var fixedSpent = z; var nonMonthlySpent = z; var flexSpent = z
        for ((cat, amt) in spendByCategory) when (bucketOf(plan, cat)) {
            FlexBucket.FIXED -> {
                fixedSpent += amt
                // attribute to the planned key (category or ancestor) for reservation maths
                var k = cat; while (k != null && k !in plan.fixedPlanned) k = parentOf(k)
                if (k != null) fixedSpentByCat[k] = (fixedSpentByCat[k] ?: z) + amt
            }
            FlexBucket.NON_MONTHLY -> nonMonthlySpent += amt // paid out of the sinking funds
            FlexBucket.FLEXIBLE -> flexSpent += amt
        }
        val unplannedFixed = fixedSpent - MoneyMath.sum(fixedSpentByCat.values, c)
        val fixedPlanned = MoneyMath.sum(plan.fixedPlanned.values, c)
        val reserved = MoneyMath.sum(plan.fixedPlanned.map { (k, p) -> MoneyMath.max(p, fixedSpentByCat[k] ?: z) }, c) + unplannedFixed
        val nonMonthly = MoneyMath.sum(plan.nonMonthly.map { sinking.status(it, today).contributionNeeded }, c)
        val flexAvail = plan.income - reserved - nonMonthly
        val remaining = flexAvail - flexSpent
        val days = period.daysLeftIncluding(today)
        val perDay = if (days == 0 || !remaining.isPositive) z else MoneyMath.divFloor(remaining, days)
        return FlexibleReport(fixedPlanned, fixedSpent, reserved, nonMonthly, nonMonthlySpent, flexAvail, flexSpent, remaining, days, perDay)
    }
}
