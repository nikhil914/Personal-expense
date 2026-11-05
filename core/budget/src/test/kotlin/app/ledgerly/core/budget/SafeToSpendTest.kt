package app.ledgerly.core.budget

import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeToSpendTest {
    // Financial month starts on the 25th; today is Thu 5 Nov 2026 → period 25 Oct – 24 Nov, 20 days left incl. today
    private val clock = clockAt(d(2026, 11, 5))
    private val period = PeriodCalculator(25).month(d(2026, 11, 5))
    private val calc = SafeToSpendCalculator(clock)

    private fun input(balance: String = "50000", spentToday: String = "0", bills: List<PlannedItem> = defaultBills) = SafeToSpendInput(
        period = period,
        balances = listOf(
            AccountBalance("hdfc", "HDFC", inr(balance)),
            AccountBalance("cc", "Amex", inr("-12000"), isSpendingAccount = false),
        ),
        expectedIncome = listOf(
            PlannedItem("Freelance", inr("5000"), d(2026, 11, 20)),
            PlannedItem("Salary", inr("90000"), d(2026, 11, 25)), // next period
            PlannedItem("Late refund", inr("700"), d(2026, 11, 1)),  // overdue income is not counted
        ),
        bills = bills,
        goalContributions = listOf(Contribution("Japan trip", inr("3000"))),
        sinkingFundContributions = listOf(Contribution("Car insurance", inr("2000"))),
        buffer = inr("5000"),
        spentToday = inr(spentToday),
        flexibleBudgets = listOf(FlexibleRemaining("Dining", inr("4000"))),
    )

    private val defaultBills = listOf(
        PlannedItem("Rent", inr("15000"), d(2026, 11, 10)),
        PlannedItem("Netflix", inr("649"), d(2026, 11, 15)),
        PlannedItem("Electricity", inr("1800"), d(2026, 11, 3), settled = true),
        PlannedItem("Phone", inr("599"), d(2026, 11, 26)), // after period end
    )

    @Test fun safeToSpendWithBillsDue() {
        val r = calc.calculate(input())
        assertEquals(20, r.daysLeft)
        // 50000 + 5000 − 15649 − 3000 − 2000 − 5000 = 29351 → /20
        assertEquals(inr("29351"), r.pool)
        assertEquals(inr("1467.55"), r.amount)
        assertEquals(SafeToSpendState.HEALTHY, r.state)
        assertEquals("₹1,467.55 safe to spend today", r.widgetHeadline)
        assertEquals(inr("-15649"), r.breakdown.first { it.kind == BreakdownKind.BILLS }.amount)
        assertTrue(r.explanation.any { it.startsWith("− Bills due by 24 Nov: ₹15,649 (Rent ₹15,000 on 10 Nov, Netflix ₹649 on 15 Nov)") }, r.explanation.toString())
        assertTrue(r.explanation.any { it == "₹1,467.55 a day for 20 days including today" })
        assertEquals(inr("200"), r.flexiblePerDay.single().remaining)
    }

    @Test fun spendingTodayReducesTodaysAmountNotTheAllowance() {
        val r = calc.calculate(input(balance = "49000", spentToday = "1000"))
        assertEquals(inr("1467.55"), r.dailyAllowance)
        assertEquals(inr("467.55"), r.amount)
        assertEquals(SafeToSpendState.TIGHT, r.state)
    }

    @Test fun overspentTodayIsOver() {
        val r = calc.calculate(input(balance = "48000", spentToday = "2000"))
        assertTrue(r.amount.isZero)
        assertEquals(inr("532.45"), r.overBy)
        assertEquals(SafeToSpendState.OVER, r.state)
        assertEquals("Over today's limit by ₹532.45", r.widgetHeadline)
    }

    @Test fun billsExceedingBalanceIsOver() {
        val r = calc.calculate(input(balance = "20000"))
        assertEquals(inr("-649"), r.pool)
        assertTrue(r.amount.isZero)
        assertEquals(inr("649"), r.overBy)
        assertEquals(SafeToSpendState.OVER, r.state)
        assertEquals("Over by ₹649 this period", r.widgetHeadline)
    }

    @Test fun overdueUnpaidBillsStillDeducted() {
        val bills = defaultBills + PlannedItem("Credit card", inr("4000"), d(2026, 11, 2))
        assertEquals(inr("25351"), calc.calculate(input(bills = bills)).pool)
    }

    @Test fun typicalDailySpendMarksTight() {
        val r = calc.calculate(input().copy(typicalDailySpend = inr("2000")))
        assertEquals(SafeToSpendState.TIGHT, r.state)
    }

    @Test fun capByFlexibleBudgets() {
        val r = SafeToSpendCalculator(clock, capByFlexibleBudgets = true).calculate(input())
        assertEquals(inr("200"), r.amount)
    }

    @Test fun lastDayOfPeriodUsesWholePool() {
        val r = SafeToSpendCalculator(clockAt(d(2026, 11, 24))).calculate(input(bills = emptyList()))
        assertEquals(1, r.daysLeft)
        assertEquals(inr("40000"), r.amount) // 50000 − 3000 − 2000 − 5000 (freelance on 20 Nov is now past)
    }

    @Test fun todayOutsidePeriodRejected() {
        assertFailsWith<IllegalArgumentException> { SafeToSpendCalculator(clockAt(d(2026, 12, 1))).calculate(input()) }
    }

    @Test fun recurringBillsExpandIntoPeriod() {
        val rent = RecurringItem("Rent", inr("15000"), Recurrence.Monthly(1))
        assertEquals(listOf(d(2026, 11, 1)), rent.occurrences(period).map { it.date })
        val gym = RecurringItem("Yoga", inr("300"), Recurrence.Weekly(DayOfWeek.SATURDAY))
        val occ = gym.occurrences(period, settledBefore = d(2026, 11, 5))
        assertEquals(listOf(d(2026, 10, 31), d(2026, 11, 7), d(2026, 11, 14), d(2026, 11, 21)), occ.map { it.date })
        assertEquals(1, occ.count { it.settled })
    }
}
