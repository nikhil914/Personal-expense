package app.ledgerly.core.budget

import java.time.DayOfWeek
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class PeriodCalculatorTest {
    @Test fun calendarMonth() {
        val p = PeriodCalculator().month(d(2026, 11, 5))
        assertEquals(BudgetPeriod(d(2026, 11, 1), d(2026, 12, 1)), p)
        assertEquals(30, p.days)
    }

    @Test fun financialMonthStartingOn25thCrossesYearBoundary() {
        val calc = PeriodCalculator(monthStartDay = 25)
        val dec = calc.month(d(2026, 12, 28))
        assertEquals(BudgetPeriod(d(2026, 12, 25), d(2027, 1, 25)), dec)
        assertEquals(dec, calc.month(d(2027, 1, 24)))
        assertEquals(YearMonth.of(2027, 1), calc.label(dec))
        assertEquals(BudgetPeriod(d(2027, 1, 25), d(2027, 2, 25)), calc.month(d(2027, 1, 25)))
        assertEquals(BudgetPeriod(d(2026, 11, 25), d(2026, 12, 25)), calc.previous(PeriodSpec.MONTHLY, dec))
        assertEquals(31, dec.days)
    }

    @Test fun startDayClampedInShortMonths() {
        val calc = PeriodCalculator(monthStartDay = 31)
        assertEquals(BudgetPeriod(d(2027, 2, 28), d(2027, 3, 31)), calc.month(d(2027, 3, 15)))
        assertEquals(BudgetPeriod(d(2027, 1, 31), d(2027, 2, 28)), calc.month(d(2027, 2, 27)))
    }

    @Test fun salaryOnWeekendShiftsStartToFriday() {
        // 25 Oct 2026 is a Sunday → month starts Fri 23 Oct
        val calc = PeriodCalculator(monthStartDay = 25, shiftStartBeforeWeekend = true)
        val p = calc.month(d(2026, 10, 23))
        assertEquals(d(2026, 10, 23), p.start)
        assertEquals(d(2026, 11, 25), p.endExclusive) // 25 Nov 2026 is a Wednesday
        assertEquals(BudgetPeriod(d(2026, 9, 25), d(2026, 10, 23)), calc.month(d(2026, 10, 22)))
    }

    @Test fun weekStartDay() {
        val sunday = PeriodCalculator(weekStart = DayOfWeek.SUNDAY).week(d(2026, 11, 5))
        assertEquals(BudgetPeriod(d(2026, 11, 1), d(2026, 11, 8)), sunday)
        val monday = PeriodCalculator().week(d(2026, 11, 5))
        assertEquals(d(2026, 11, 2), monday.start)
    }

    @Test fun customPeriodsAlignToAnchor() {
        val calc = PeriodCalculator()
        val spec = PeriodSpec.custom(d(2026, 1, 1), 14)
        assertEquals(BudgetPeriod(d(2026, 1, 15), d(2026, 1, 29)), calc.periodFor(spec, d(2026, 1, 20)))
        assertEquals(BudgetPeriod(d(2025, 12, 18), d(2026, 1, 1)), calc.periodFor(spec, d(2025, 12, 31)))
    }

    @Test fun daysLeftAndElapsed() {
        val p = BudgetPeriod(d(2026, 10, 25), d(2026, 11, 25))
        assertEquals(20, p.daysLeftIncluding(d(2026, 11, 5)))
        assertEquals(12, p.elapsedIncluding(d(2026, 11, 5)))
        assertEquals(1, p.daysLeftIncluding(d(2026, 11, 24)))
        assertEquals(0, p.daysLeftIncluding(d(2026, 11, 25)))
        assertEquals(0, p.elapsedIncluding(d(2026, 10, 1)))
    }

    @Test fun seriesOfThree() {
        val s = PeriodCalculator(25).series(PeriodSpec.MONTHLY, d(2026, 11, 30), 3)
        assertEquals(listOf(d(2026, 11, 25), d(2026, 12, 25), d(2027, 1, 25)), s.map { it.start })
    }
}
