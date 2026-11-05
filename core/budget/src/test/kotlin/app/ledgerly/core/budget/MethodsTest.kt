package app.ledgerly.core.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MethodsTest {
    private val agg = aggregator()
    private val nov = BudgetPeriod(d(2026, 11, 1), d(2026, 12, 1))

    @Test fun fiftyThirtyTwentyBuckets() {
        val plan = FiftyThirtyTwentyPlan(inr("100000"), mapOf(
            "rent" to NeedsWantsSavings.NEEDS, "groceries" to NeedsWantsSavings.NEEDS,
            "food" to NeedsWantsSavings.WANTS, "shopping" to NeedsWantsSavings.WANTS,
        ))
        val spend = agg.spendByCategory(listOf(
            spend("30000", "rent", d(2026, 11, 1)), spend("8000", "groceries", d(2026, 11, 2)),
            spend("6000", "dining", d(2026, 11, 3)), spend("4000", "shopping", d(2026, 11, 3)),
            spend("2000", "transport", d(2026, 11, 3)),
        ), nov)
        val r = FiftyThirtyTwentyEvaluator(agg::parentOf).evaluate(plan, spend, extraSavings = inr("15000"))
        assertEquals(inr("38000"), r.bucket(NeedsWantsSavings.NEEDS).actual)
        assertEquals(inr("50000"), r.bucket(NeedsWantsSavings.NEEDS).target)
        assertEquals(38.0, r.bucket(NeedsWantsSavings.NEEDS).percentOfIncome)
        // dining inherits WANTS from food; transport is unmapped → WANTS
        assertEquals(inr("12000"), r.bucket(NeedsWantsSavings.WANTS).actual)
        assertEquals(inr("2000"), r.unmappedSpend)
        assertEquals(inr("5000"), r.bucket(NeedsWantsSavings.SAVINGS).remaining)
    }

    @Test fun fiftyThirtyTwentyPercentagesMustAddUp() {
        assertFailsWith<IllegalArgumentException> { FiftyThirtyTwentyPlan(inr("1"), emptyMap(), 50, 30, 30) }
    }

    @Test fun flexibleBudgetBuckets() {
        val clock = clockAt(d(2026, 11, 5))
        val insurance = SinkingFund.annual("ins", "Insurance", inr("12000"), d(2027, 4, 1), saved = inr("8000"))
        val plan = FlexibleBudgetPlan(inr("100000"), mapOf("rent" to inr("30000")), listOf(insurance),
            mapping = mapOf("rent" to FlexBucket.FIXED, "insurance" to FlexBucket.NON_MONTHLY))
        val spend = agg.spendByCategory(listOf(
            spend("30000", "rent", d(2026, 11, 1)), spend("5000", "dining", d(2026, 11, 2)),
            spend("7000", "groceries", d(2026, 11, 3)), spend("3000", "insurance", d(2026, 11, 3)),
        ), nov)
        val r = FlexibleBudgetEvaluator(SinkingFundCalculator(clock), agg::parentOf).evaluate(plan, nov, d(2026, 11, 5), spend)
        assertEquals(inr("30000"), r.fixedReserved)
        assertEquals(inr("800"), r.nonMonthlyContribution)
        assertEquals(inr("3000"), r.nonMonthlySpent)
        assertEquals(inr("69200"), r.flexibleAvailable)
        assertEquals(inr("57200"), r.flexibleRemaining)
        assertEquals(26, r.daysLeft)
        assertEquals(inr("2200"), r.flexiblePerDay)
    }

    @Test fun flexibleFixedOverspendReservesActual() {
        val plan = FlexibleBudgetPlan(inr("50000"), mapOf("rent" to inr("20000")), emptyList())
        val spend = agg.spendByCategory(listOf(spend("22000", "rent", d(2026, 11, 1))), nov)
        val r = FlexibleBudgetEvaluator(SinkingFundCalculator(clockAt(d(2026, 11, 5)))).evaluate(plan, nov, d(2026, 11, 5), spend)
        assertEquals(inr("22000"), r.fixedReserved)
        assertEquals(inr("28000"), r.flexibleRemaining)
    }
}
