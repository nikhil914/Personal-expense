package app.ledgerly.core.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertEvaluatorTest {
    private val calc = PeriodCalculator()
    private val engine = CategoryBudgetEngine(aggregator(), calc)
    private val budget = CategoryBudget("b", "B", INR, listOf(BudgetLine("dining", inr("5000")), BudgetLine("transport", inr("2000"))))
    private val nov = calc.month(d(2026, 11, 1))
    private val base = listOf(spend("2000", "dining", d(2026, 11, 2)), spend("100", "transport", d(2026, 11, 2)))

    @Test fun levels() {
        val a = AlertEvaluator()
        assertNull(a.level(49.9))
        assertEquals(50, a.level(50.0))
        assertEquals(80, a.level(99.0))
        assertEquals(100, a.level(140.0))
    }

    @Test fun crossingDetectedBetweenSnapshots() {
        val before = engine.evaluate(budget, nov, base)
        val after = engine.evaluate(budget, nov, base + spend("600", "dining", d(2026, 11, 4)))
        val c = engine.crossings(before, after).single()
        assertEquals("dining", c.categoryId)
        assertEquals(50, c.threshold)
        assertEquals(40.0, c.previousPercent)
        assertEquals(52.0, c.currentPercent)
    }

    @Test fun bigJumpReportsHighestAndAllCrossed() {
        val before = engine.evaluate(budget, nov, base)
        val after = engine.evaluate(budget, nov, base + spend("2300", "dining", d(2026, 11, 4)))
        val c = engine.crossings(before, after).single()
        assertEquals(80, c.threshold)
        assertEquals(listOf(50, 80), c.allCrossed)
    }

    @Test fun noRepeatAlertAndNoAlertOnDecrease() {
        val s1 = engine.evaluate(budget, nov, base + spend("600", "dining", d(2026, 11, 4)))
        val s2 = engine.evaluate(budget, nov, base + spend("700", "dining", d(2026, 11, 4)))
        assertTrue(engine.crossings(s1, s2).isEmpty())
        assertTrue(engine.crossings(s2, s1).isEmpty())
    }

    @Test fun newPeriodOrFirstSnapshotCountsFromZero() {
        val oct = engine.evaluate(budget, calc.month(d(2026, 10, 1)), listOf(spend("4500", "dining", d(2026, 10, 5))))
        val novR = engine.evaluate(budget, nov, base + spend("600", "dining", d(2026, 11, 4)))
        assertEquals(50, engine.crossings(oct, novR).single().threshold)
        assertEquals(50, engine.crossings(null, novR).single().threshold)
    }

    @Test fun customThresholds() {
        val e = CategoryBudgetEngine(aggregator(), calc, AlertEvaluator(listOf(90, 75)))
        val before = e.evaluate(budget, nov, base)
        val after = e.evaluate(budget, nov, base + spend("1800", "dining", d(2026, 11, 4)))
        assertEquals(75, e.crossings(before, after).single().threshold)
        assertEquals(76.0, after.line("dining").percentUsed)
        assertEquals(75, after.line("dining").alertLevel)
    }
}
