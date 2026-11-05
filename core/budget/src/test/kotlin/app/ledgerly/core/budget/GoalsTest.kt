package app.ledgerly.core.budget

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalsTest {
    private val clock = clockAt(d(2026, 11, 5))
    private val sinking = SinkingFundCalculator(clock)
    private fun insurance(saved: String) = SinkingFund.annual("ins", "Car insurance", inr("12000"), d(2027, 4, 1), inr(saved))

    @Test fun sinkingFundOnTrack() {
        val s = sinking.status(insurance("8000"))
        assertEquals(12, s.monthsTotal)
        assertEquals(inr("1000"), s.monthlyAccrual)
        assertEquals(inr("8000"), s.expectedByNow)
        assertEquals(FundState.ON_TRACK, s.state)
        assertEquals(5, s.monthsRemaining)
        assertEquals(inr("800"), s.contributionNeeded)
    }

    @Test fun sinkingFundAheadAndBehind() {
        assertEquals(FundState.AHEAD, sinking.status(insurance("9500")).state)
        val behind = sinking.status(insurance("6000"))
        assertEquals(FundState.BEHIND, behind.state)
        assertEquals(inr("-2000"), behind.difference)
        assertEquals(inr("1200"), behind.contributionNeeded)
    }

    @Test fun sinkingFundFundedAndOverdue() {
        val funded = sinking.status(insurance("12000"))
        assertEquals(FundState.FUNDED, funded.state)
        assertTrue(funded.contributionNeeded.isZero)
        assertEquals(FundState.OVERDUE, sinking.status(insurance("5000"), d(2027, 4, 2)).state)
    }

    @Test fun savingsGoalProgress() {
        val g = SavingsGoal("trip", "Japan trip", inr("120000"), inr("30000"), d(2026, 1, 1), d(2027, 1, 1))
        val p = GoalCalculator(clock).progress(g)
        assertEquals(25.0, p.percent)
        assertEquals(2, p.monthsLeft)
        assertEquals(inr("45000"), p.monthlyContributionNeeded)
        assertFalse(p.onTrack!!)
        assertEquals(inr("15000"), GoalCalculator(clock).remainingThisPeriod(g, inr("30000")))
    }

    @Test fun goalOnTrackAndWithoutDate() {
        val calc = GoalCalculator(clock)
        assertTrue(calc.progress(SavingsGoal("g", "G", inr("12000"), inr("11000"), d(2026, 1, 1), d(2027, 1, 1))).onTrack!!)
        val open = calc.progress(SavingsGoal("g", "G", inr("1000"), inr("1000"), d(2026, 1, 1)))
        assertNull(open.onTrack)
        assertTrue(open.isComplete)
    }

    @Test fun emergencyFundMonthsCovered() {
        val e = GoalCalculator(clock).emergencyFund(inr("150000"), inr("40000"))
        assertEquals(BigDecimal("3.7"), e.monthsCovered)
        assertEquals(inr("90000"), e.shortfall)
    }
}
