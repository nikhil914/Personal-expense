package app.ledgerly.core.budget

import app.ledgerly.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SuggestionsTest {
    private val agg = aggregator()
    private val calc = PeriodCalculator()
    private val suggester = BudgetSuggester(agg, calc)

    private val history = listOf(
        spend("2130", "dining", d(2026, 8, 10)), spend("2380", "dining", d(2026, 9, 10)), spend("1950", "dining", d(2026, 10, 10)),
        spend("8000", "groceries", d(2026, 9, 3)), spend("9000", "groceries", d(2026, 10, 3)),
        spend("25000", "shopping", d(2026, 9, 20)), // one-off
        spend("99999", "dining", d(2026, 11, 2)),   // current month ignored
    )

    @Test fun medianOfLastThreeMonthsRoundedNicely() {
        val s = suggester.suggest(history, d(2026, 11, 5), months = 3).associateBy { it.categoryId }
        assertEquals(inr("2130"), s["dining"]!!.median)
        assertEquals(inr("2200"), s["dining"]!!.suggested)
        assertEquals(listOf(inr("2130"), inr("2380"), inr("1950")), s["dining"]!!.history)
        assertEquals(inr("8000"), s["groceries"]!!.median) // Aug counts as 0
        assertEquals(null, s["shopping"])
    }

    @Test fun sixMonthMedianEvenCount() {
        val s = suggester.suggest(history, d(2026, 11, 5), months = 6).associateBy { it.categoryId }
        // dining: [0,0,0,2130,2380,1950] → median (0+1950)/2 = 975
        assertEquals(inr("975"), s["dining"]!!.median)
        assertEquals(inr("1000"), s["dining"]!!.suggested)
        assertFailsWith<IllegalArgumentException> { suggester.suggest(history, d(2026, 11, 5), months = 2) }
    }

    @Test fun roundNicelySteps() {
        assertEquals(inr("450"), BudgetSuggester.roundNicely(inr("430")))
        assertEquals(inr("2200"), BudgetSuggester.roundNicely(inr("2130")))
        assertEquals(inr("12500"), BudgetSuggester.roundNicely(inr("12040")))
        assertEquals(inr("46000"), BudgetSuggester.roundNicely(inr("45000.01")))
        assertEquals(inr("155000"), BudgetSuggester.roundNicely(inr("150000.01")))
        assertEquals(Money.of("2200", "JPY"), BudgetSuggester.roundNicely(Money.of("2101", "JPY")))
    }

    @Test fun copyLastMonthAndFromActuals() {
        val budget = suggester.suggestedBudget("b", "Suggested", suggester.suggest(history, d(2026, 11, 5), 3), RolloverMode.SURPLUS_ONLY)
        assertEquals(2, budget.lines.size)
        val copy = suggester.copyLastPeriod(budget, "b2")
        assertEquals(budget.lines, copy.lines)
        val engine = CategoryBudgetEngine(agg, calc)
        val oct = engine.evaluate(budget, calc.month(d(2026, 10, 1)), history)
        val fromActuals = suggester.fromLastPeriodActuals(oct, budget)
        assertEquals(inr("2000"), fromActuals.lines.first { it.categoryId == "dining" }.limit)
        assertEquals(inr("9000"), fromActuals.lines.first { it.categoryId == "groceries" }.limit)
    }

    @Test fun templatesApplyPercentages() {
        val keys = mapOf("housing" to "rent", "groceries" to "groceries", "dining" to "dining", "transport" to "transport")
        val b = BudgetTemplates.apply(BudgetTemplates.BALANCED, inr("100000"), "t", { keys[it] })
        assertEquals(4, b.lines.size)
        assertEquals(inr("30000"), b.lines.first { it.categoryId == "rent" }.limit)
        assertEquals(inr("12000"), b.lines.first { it.categoryId == "groceries" }.limit)
    }
}
