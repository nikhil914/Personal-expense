package app.ledgerly.core.budget

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money

enum class NeedsWantsSavings { NEEDS, WANTS, SAVINGS }

/** 50/30/20 plan: categories mapped to needs/wants/savings (children inherit their parent's bucket). */
data class FiftyThirtyTwentyPlan(
    val income: Money,
    val mapping: Map<Id, NeedsWantsSavings>,
    val needsPercent: Int = 50,
    val wantsPercent: Int = 30,
    val savingsPercent: Int = 20,
    val unmappedBucket: NeedsWantsSavings = NeedsWantsSavings.WANTS,
) {
    init { require(needsPercent + wantsPercent + savingsPercent == 100) { "Percentages must add to 100" } }

    fun targetPercent(b: NeedsWantsSavings) = when (b) {
        NeedsWantsSavings.NEEDS -> needsPercent; NeedsWantsSavings.WANTS -> wantsPercent; NeedsWantsSavings.SAVINGS -> savingsPercent
    }
}

data class BucketStatus(
    val bucket: NeedsWantsSavings,
    val targetPercent: Int,
    val target: Money,
    val actual: Money,
    val remaining: Money,
    /** actual as % of income. */
    val percentOfIncome: Double,
)

data class FiftyThirtyTwentyReport(val buckets: List<BucketStatus>, val unmappedSpend: Money) {
    fun bucket(b: NeedsWantsSavings) = buckets.first { it.bucket == b }
}

class FiftyThirtyTwentyEvaluator(private val parentOf: (Id) -> Id? = { null }) {

    fun bucketOf(plan: FiftyThirtyTwentyPlan, categoryId: Id?): NeedsWantsSavings? {
        var c = categoryId; val seen = HashSet<Id>()
        while (c != null && seen.add(c)) { plan.mapping[c]?.let { return it }; c = parentOf(c) }
        return null
    }

    /**
     * [spendByCategory] is net spend; [extraSavings] covers money moved to savings/investments that is not
     * categorised spend (e.g. transfers to an RD or SIP).
     */
    fun evaluate(plan: FiftyThirtyTwentyPlan, spendByCategory: Map<Id?, Money>, extraSavings: Money = Money.zero(plan.income.currency)): FiftyThirtyTwentyReport {
        val c = plan.income.currency
        val actual = NeedsWantsSavings.entries.associateWith { Money.zero(c) }.toMutableMap()
        var unmapped = Money.zero(c)
        for ((cat, amt) in spendByCategory) {
            val b = bucketOf(plan, cat) ?: run { unmapped += amt; plan.unmappedBucket }
            actual[b] = actual[b]!! + amt
        }
        actual[NeedsWantsSavings.SAVINGS] = actual[NeedsWantsSavings.SAVINGS]!! + extraSavings
        val buckets = NeedsWantsSavings.entries.map { b ->
            val target = plan.income.times(java.math.BigDecimal(plan.targetPercent(b)).movePointLeft(2))
            BucketStatus(b, plan.targetPercent(b), target, actual[b]!!, target - actual[b]!!, MoneyMath.percent(actual[b]!!, plan.income))
        }
        return FiftyThirtyTwentyReport(buckets, unmapped)
    }
}
