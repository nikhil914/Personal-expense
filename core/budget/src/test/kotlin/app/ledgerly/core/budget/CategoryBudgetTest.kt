package app.ledgerly.core.budget

import app.ledgerly.core.model.Money
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryBudgetTest {
    private val calc = PeriodCalculator()
    private val engine = CategoryBudgetEngine(aggregator(), calc)
    private val nov = calc.month(d(2026, 11, 1))

    private fun budget(vararg lines: BudgetLine) = CategoryBudget("b1", "Monthly", INR, lines.toList())

    @Test fun lineStatusSpentRemainingPercentAndAlert() {
        val r = engine.evaluate(budget(BudgetLine("dining", inr("5000"))), nov, listOf(
            spend("1500", "dining", d(2026, 11, 2)), spend("2600", "dining", d(2026, 11, 3)),
        ))
        val l = r.line("dining")
        assertEquals(inr("4100"), l.spent)
        assertEquals(inr("900"), l.remaining)
        assertEquals(82.0, l.percentUsed)
        assertEquals(80, l.alertLevel)
        assertTrue(!l.isOver)
    }

    @Test fun parentLineRollsUpChildrenWithoutDoubleCountingTotals() {
        val r = engine.evaluate(budget(BudgetLine("food", inr("10000")), BudgetLine("dining", inr("4000")), BudgetLine("transport", inr("2000"))), nov, listOf(
            spend("3000", "groceries", d(2026, 11, 2)), spend("4500", "dining", d(2026, 11, 3)), spend("500", "transport", d(2026, 11, 3)),
        ))
        assertEquals(inr("7500"), r.line("food").spent)
        assertTrue(r.line("dining").isOver)
        assertEquals(100, r.line("dining").alertLevel)
        assertEquals(inr("12000"), r.totalAvailable)
        assertEquals(inr("8000"), r.totalSpent)
    }

    private val threeMonths = listOf(
        spend("4000", "dining", d(2026, 11, 10)),
        spend("7000", "dining", d(2026, 12, 10)),
        spend("3000", "dining", d(2027, 1, 10)),
    )

    @Test fun rolloverSurplusOnlyOverThreeMonths() {
        val rs = engine.evaluateSeries(budget(BudgetLine("dining", inr("5000"), RolloverMode.SURPLUS_ONLY)), d(2026, 11, 1), 3, threeMonths)
        assertEquals(listOf(inr("5000"), inr("6000"), inr("5000")), rs.map { it.line("dining").available })
        assertEquals(listOf(inr("1000"), inr("-1000"), inr("2000")), rs.map { it.line("dining").remaining })
        assertEquals(inr("2000"), rs[2].carryOut["dining"])
    }

    @Test fun rolloverSurplusAndDeficitOverThreeMonths() {
        val rs = engine.evaluateSeries(budget(BudgetLine("dining", inr("5000"), RolloverMode.SURPLUS_AND_DEFICIT)), d(2026, 11, 1), 3, threeMonths)
        assertEquals(listOf(inr("5000"), inr("6000"), inr("4000")), rs.map { it.line("dining").available })
        assertEquals(listOf(inr("0"), inr("1000"), inr("-1000")), rs.map { it.line("dining").carryIn })
        assertEquals(inr("1000"), rs[2].line("dining").remaining)
    }

    @Test fun noRolloverResetsEachMonth() {
        val rs = engine.evaluateSeries(budget(BudgetLine("dining", inr("5000"))), d(2026, 11, 1), 3, threeMonths)
        assertTrue(rs.all { it.line("dining").available == inr("5000") })
        assertTrue(rs.all { it.carryOut["dining"]!!.isZero })
    }

    @Test fun currentReplaysRolloverUpToToday() {
        val r = engine.current(budget(BudgetLine("dining", inr("5000"), RolloverMode.SURPLUS_AND_DEFICIT)), d(2026, 11, 1), d(2027, 1, 5), threeMonths)
        assertEquals(d(2027, 1, 1), r.period.start)
        assertEquals(inr("4000"), r.line("dining").available)
        assertTrue(r.line("dining").pace != null)
    }

    @Test fun refundReducesBudgetSpend() {
        val orig = spend("3000", "shopping", d(2026, 11, 2), id = "big")
        val refund = spend("1000", "shopping", d(2026, 11, 9), app.ledgerly.core.model.TxnType.REFUND).copy(refundOfId = "big")
        val r = engine.evaluate(budget(BudgetLine("shopping", inr("4000"))), nov, listOf(orig, refund))
        assertEquals(inr("2000"), r.line("shopping").spent)
        assertEquals(50.0, r.line("shopping").percentUsed)
    }

    @Test fun linearPaceProjection() {
        val r = engine.evaluate(budget(BudgetLine("dining", inr("6000"))), nov, listOf(spend("4000", "dining", d(2026, 11, 3))), today = d(2026, 11, 10))
        val pace = r.line("dining").pace!!
        assertEquals(inr("12000"), pace.projectedLinear)
        assertEquals(inr("6000"), pace.projectedOverBy)
        assertEquals("At this pace you'll exceed Dining by ₹6,000", pace.message)
    }

    @Test fun paceOnTrackMessage() {
        val p = PaceProjector().project(nov, d(2026, 11, 15), inr("1000"), inr("5000"), "Transport")
        assertNull(p.projectedOverBy)
        assertEquals("On track: about ₹3,000 left in Transport at period end", p.message)
    }

    @Test fun dayOfWeekWeightedProjectionUsesLearnedWeights() {
        // History: only weekend spending in October
        val daily = HashMap<LocalDate, Money>()
        var x = d(2026, 10, 1)
        while (x.isBefore(d(2026, 11, 1))) {
            if (x.dayOfWeek == DayOfWeek.SATURDAY || x.dayOfWeek == DayOfWeek.SUNDAY) daily[x] = inr("1000")
            x = x.plusDays(1)
        }
        val w = DayOfWeekWeights.fromHistory(daily, d(2026, 10, 1), d(2026, 11, 1))
        assertTrue(w.of(DayOfWeek.SATURDAY) > w.of(DayOfWeek.MONDAY))
        assertTrue(!w.isUniform)
        // Today is Sunday 8 Nov; 3 of the 8 elapsed days were weekend days (heavy), so the remaining 22 days project lower than linear
        val p = PaceProjector(w).project(nov, d(2026, 11, 8), inr("4000"), inr("10000"), "Dining")
        assertEquals(inr("15000"), p.projectedLinear)
        assertEquals(p.projectedWeighted, p.projected)
        assertTrue(p.projectedWeighted < p.projectedLinear)
        assertTrue(p.projectedWeighted > inr("12000") && p.projectedWeighted < inr("12500"), p.projectedWeighted.toString())
        assertEquals("At this pace you'll exceed Dining by ${MoneyMath.human(p.projectedWeighted - inr("10000"))}", p.message)
    }

    @Test fun uniformWeightsGiveLinear() {
        val p = PaceProjector().project(nov, d(2026, 11, 8), inr("4000"), inr("10000"), "Dining")
        assertEquals(p.projectedLinear, p.projectedWeighted)
    }
}
