package app.ledgerly.core.insights

import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.TxnType
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CashFlowForecasterTest {
    private val f = CashFlowForecaster(FIXED)

    private val salary = monthly("ACME Corp", inr(100000), "2026-05", 1, List(7) { 0 }, TxnType.INCOME)
    private val rent = monthly("Landlord", inr(30000), "2026-05", 3, List(7) { 0 })

    @Test
    fun `flat balance without history`() {
        val fc = f.forecast(inr(50000), emptyList(), 30)
        assertEquals(30, fc.days.size)
        assertEquals(TODAY.plusDays(1), fc.days.first().date)
        assertEquals(inr(50000), fc.endBalance)
        assertNull(fc.warning)
    }

    @Test
    fun `horizons of 60 and 90 days`() {
        assertEquals(60, f.forecast(inr(1), emptyList(), 60).days.size)
        assertEquals(90, f.forecast(inr(1), emptyList(), 90).days.size)
    }

    @Test
    fun `recurring income and expenses are projected`() {
        val fc = f.forecast(inr(50000), salary + rent, 30)
        assertEquals(inr(150000), fc.balanceOn(d("2026-12-01")))
        assertEquals(inr(120000), fc.balanceOn(d("2026-12-03")))
        assertEquals(inr(50000), fc.lowestBalance)
        val fc90 = f.forecast(inr(50000), salary + rent, 90)
        // Dec, Jan salaries (+200k), Dec, Jan rents (-60k); Feb 1 salary + Feb 3 rent in window (ends Feb 3)
        assertEquals(inr(50000 + 300000 - 90000), fc90.endBalance)
    }

    @Test
    fun `discretionary spend by weekday`() {
        // Spend 700 every Saturday for 6 weeks (different merchants -> not recurring)
        val firstSat = d("2026-09-26")
        assertEquals(DayOfWeek.SATURDAY, firstSat.dayOfWeek)
        val shops = listOf("Zara", "H&M", "Croma", "Nykaa", "Decathlon", "Ikea")
        val hist = (0 until 6).map { txn(firstSat.plusWeeks(it.toLong()), inr(700), shops[it]) }
        val fc = f.forecast(inr(10000), hist, 14)
        assertEquals(inr(700), fc.discretionaryByWeekday[DayOfWeek.SATURDAY])
        assertEquals(inr(0), fc.discretionaryByWeekday[DayOfWeek.MONDAY])
        // Two Saturdays in the 14-day window: Nov 7 and Nov 14
        assertEquals(inr(10000 - 1400), fc.endBalance)
        assertEquals(inr(700), fc.days.first { it.date == d("2026-11-07") }.outflow)
    }

    @Test
    fun `lowest point warning when balance goes negative`() {
        val rent10 = monthly("Landlord", inr(30000), "2026-05", 10, List(6) { 0 })
        val fc = f.forecast(inr(10000), rent10, 30)
        assertEquals(d("2026-11-10"), fc.lowestDay!!.date)
        assertEquals(inr(-20000), fc.lowestBalance)
        assertNotNull(fc.warning)
        assertTrue(fc.warning!!.contains("10 Nov"), fc.warning)
    }

    @Test
    fun `planned events and multiple balances`() {
        val fc = f.forecast(listOf(inr(20000), inr(5000)), emptyList(), 30, planned = listOf(PlannedEvent(d("2026-11-20"), inr(-8000), "Phone")))
        assertEquals(inr(25000), fc.startBalance)
        assertEquals(inr(17000), fc.balanceOn(d("2026-11-20")))
        assertTrue(fc.days.first { it.date == d("2026-11-20") }.events.any { it.label == "Phone" })
    }

    @Test
    fun `transfers are excluded from discretionary spend`() {
        val hist = (0 until 4).map { txn(d("2026-10-10").plusWeeks(it.toLong()), inr(5000), null, TxnType.TRANSFER) }
        val fc = f.forecast(inr(1000), hist, 30)
        assertEquals(inr(1000), fc.endBalance)
    }
}

class AffordabilityAdvisorTest {
    private val adv = AffordabilityAdvisor(FIXED)
    private val salary = monthly("ACME Corp", inr(100000), "2026-05", 1, List(7) { 0 }, TxnType.INCOME)

    @Test
    fun `yes when plenty of room`() {
        val r = adv.assess(Purchase(inr(2000), "shopping", TODAY), inr(80000), salary, mapOf("shopping" to inr(10000)))
        assertEquals(Verdict.YES, r.verdict)
        assertEquals(inr(10000), r.budgetRemainingBefore)
        assertEquals(inr(8000), r.budgetRemainingAfter)
        assertEquals(inr(78000), r.lowestBalanceAfter)
        assertEquals(inr(80000), r.lowestBalanceBefore)
        assertTrue(r.lines.first().startsWith("Yes"))
    }

    @Test
    fun `caution when category budget exceeded`() {
        val spent = listOf(txn(d("2026-11-02"), inr(9000), "Zara", category = "shopping"))
        val r = adv.assess(Purchase(inr(2000), "shopping", TODAY), inr(80000), salary + spent, mapOf("shopping" to inr(10000)), categoryName = { "Shopping" })
        assertEquals(Verdict.CAUTION, r.verdict)
        assertEquals(inr(-1000), r.budgetRemainingAfter)
        assertTrue(r.lines.any { it.contains("Shopping") && it.contains("over") }, r.lines.toString())
    }

    @Test
    fun `caution when purchase eats most of remaining budget`() {
        val r = adv.assess(Purchase(inr(9000), "shopping", TODAY), inr(80000), salary, mapOf("shopping" to inr(10000)))
        assertEquals(Verdict.CAUTION, r.verdict)
    }

    @Test
    fun `no when it would overdraw before payday`() {
        val r = adv.assess(Purchase(inr(15000), null, TODAY.plusDays(2)), inr(10000), salary)
        assertEquals(Verdict.NO, r.verdict)
        assertTrue(r.lowestBalanceAfter.isNegative)
        assertEquals(TODAY.plusDays(2), r.lowestDateAfter)
    }

    @Test
    fun `caution below safety buffer`() {
        val a = AffordabilityAdvisor(FIXED, config = AffordabilityConfig(safetyBufferMinor = inr(5000).minor))
        val r = a.assess(Purchase(inr(7000), null, TODAY), inr(10000), emptyList())
        assertEquals(Verdict.CAUTION, r.verdict)
    }

    @Test
    fun `savings goal delay in days`() {
        val goal = SavingsGoal("g1", "Goa trip", inr(50000), inr(10000), inr("3044"))
        val r = adv.assess(Purchase(inr(1000), null, TODAY), inr(80000), emptyList(), goals = listOf(goal))
        assertEquals(10, r.goalImpacts.single().delayDays)
        assertTrue(r.lines.any { it.contains("Goa trip") && it.contains("10 days") })
    }
}

class CashGapTrackerTest {
    private val accounts = mapOf("cash" to AccountType.CASH, "bank" to AccountType.BANK)
    private val wd = txn(d("2026-11-02"), inr(5000), "ATM HDFC MG Road", TxnType.ATM_WITHDRAWAL, hour = 10, account = "bank", id = "w1")
    private val spent = txn(d("2026-11-02"), inr(1800), "Vegetable vendor", hour = 15, account = "cash")

    private fun tracker(at: LocalDateTime, cfg: CashGapConfig = CashGapConfig()) = CashGapTracker(clockAt(at), accounts, cfg)

    @Test
    fun `pool drawn down by cash expenses`() {
        val pool = tracker(LocalDateTime.of(2026, 11, 3, 9, 0)).pools(listOf(wd, spent)).single()
        assertEquals(inr(1800), pool.spent)
        assertEquals(inr(3200), pool.unaccounted)
    }

    @Test
    fun `prompts follow day 1, 3, 7 schedule`() {
        val txns = listOf(wd, spent)
        var state = CashGapState()
        assertTrue(tracker(LocalDateTime.of(2026, 11, 2, 20, 0)).prompts(txns, state).isEmpty(), "same day: nothing")

        val day1 = tracker(LocalDateTime.of(2026, 11, 3, 9, 0))
        val p1 = day1.prompts(txns, state).single()
        assertEquals(1, p1.stageDay)
        assertEquals("You withdrew ₹5,000 on 2 Nov. ₹3,200 is unaccounted for. Log it?", p1.message)
        state = day1.markShown(state, p1)
        assertTrue(day1.prompts(txns, state).isEmpty())
        assertTrue(tracker(LocalDateTime.of(2026, 11, 4, 9, 0)).prompts(txns, state).isEmpty(), "day 2: nothing")

        val day3 = tracker(LocalDateTime.of(2026, 11, 5, 9, 0))
        val p3 = day3.prompts(txns, state).single()
        assertEquals(3, p3.stageDay)
        state = day3.markShown(state, p3)
        assertTrue(tracker(LocalDateTime.of(2026, 11, 8, 9, 0)).prompts(txns, state).isEmpty(), "day 6: nothing")
        val p7 = tracker(LocalDateTime.of(2026, 11, 9, 9, 0)).prompts(txns, state).single()
        assertEquals(7, p7.stageDay)
        state = tracker(LocalDateTime.of(2026, 11, 9, 9, 0)).markShown(state, p7)
        assertTrue(tracker(LocalDateTime.of(2026, 11, 20, 9, 0)).prompts(txns, state).isEmpty(), "all stages done")
    }

    @Test
    fun `late first open shows latest stage and marks earlier ones`() {
        val t = tracker(LocalDateTime.of(2026, 11, 10, 9, 0))
        val p = t.prompts(listOf(wd, spent)).single()
        assertEquals(7, p.stageDay)
        assertEquals(setOf(1, 3, 7), t.markShown(CashGapState(), p).shownStages["w1"])
    }

    @Test
    fun `snooze hides then resurfaces`() {
        val txns = listOf(wd, spent)
        val t = tracker(LocalDateTime.of(2026, 11, 3, 10, 0))
        val p = t.prompts(txns).single()
        val state = t.apply(CashGapState(), p, CashGapAction.SNOOZE)
        assertTrue(tracker(LocalDateTime.of(2026, 11, 3, 22, 0)).prompts(txns, state).isEmpty())
        assertEquals(1, tracker(LocalDateTime.of(2026, 11, 4, 10, 30)).prompts(txns, state).single().stageDay)
    }

    @Test
    fun `dismiss stops prompts`() {
        val t = tracker(LocalDateTime.of(2026, 11, 3, 10, 0))
        val p = t.prompts(listOf(wd, spent)).single()
        val state = t.apply(CashGapState(), p, CashGapAction.DISMISS)
        assertTrue(tracker(LocalDateTime.of(2026, 11, 9, 10, 0)).prompts(listOf(wd, spent), state).isEmpty())
    }

    @Test
    fun `mark as misc cash explains the gap`() {
        val t = tracker(LocalDateTime.of(2026, 11, 3, 10, 0))
        val p = t.prompts(listOf(wd, spent)).single()
        val state = t.apply(CashGapState(), p, CashGapAction.MARK_MISC_CASH)
        val later = tracker(LocalDateTime.of(2026, 11, 9, 10, 0))
        assertTrue(later.prompts(listOf(wd, spent), state).isEmpty())
        assertTrue(later.pools(listOf(wd, spent), state).single().unaccounted.isZero)
    }

    @Test
    fun `stops prompting once logged expenses explain the cash`() {
        val more = txn(d("2026-11-04"), inr(3150), "Auto + snacks", account = "cash")
        assertTrue(tracker(LocalDateTime.of(2026, 11, 5, 10, 0)).prompts(listOf(wd, spent, more)).isEmpty())
    }

    @Test
    fun `cash tag counts as cash expense on any account`() {
        val tagged = txn(d("2026-11-02"), inr(1000), "Chai", hour = 16, account = "bank", tags = setOf("Cash"))
        val pool = tracker(LocalDateTime.of(2026, 11, 3, 10, 0)).pools(listOf(wd, spent, tagged)).single()
        assertEquals(inr(2200), pool.unaccounted)
    }

    @Test
    fun `expenses before the withdrawal and other currencies do not draw down`() {
        val before = txn(d("2026-11-01"), inr(500), "Tea", account = "cash")
        val foreign = txn(d("2026-11-02"), eur("10"), "Cafe", hour = 18, account = "cash")
        val pool = tracker(LocalDateTime.of(2026, 11, 3, 10, 0)).pools(listOf(wd, before, foreign)).single()
        assertEquals(inr(5000), pool.unaccounted)
    }

    @Test
    fun `FIFO across two withdrawals`() {
        val wd2 = txn(d("2026-11-03"), inr(2000), "ATM", TxnType.ATM_WITHDRAWAL, hour = 9, id = "w2")
        val big = txn(d("2026-11-03"), inr(5500), "Furniture", hour = 12, account = "cash")
        val pools = tracker(LocalDateTime.of(2026, 11, 4, 10, 0)).pools(listOf(wd, wd2, big))
        assertEquals(inr(0), pools.first { it.withdrawalId == "w1" }.unaccounted)
        assertEquals(inr(1500), pools.first { it.withdrawalId == "w2" }.unaccounted)
    }

    @Test
    fun `old withdrawals and custom schedule`() {
        val t = tracker(LocalDateTime.of(2026, 12, 15, 10, 0))
        assertTrue(t.prompts(listOf(wd, spent)).isEmpty(), "older than maxAge")
        val custom = tracker(LocalDateTime.of(2026, 11, 4, 10, 0), CashGapConfig(promptDays = listOf(2), minUnaccountedMinor = 0))
        assertEquals(2, custom.prompts(listOf(wd, spent)).single().stageDay)
        assertFalse(custom.prompts(listOf(wd, spent)).single().actions.isEmpty())
    }

    @Test
    fun `snooze duration configurable`() {
        val t = tracker(LocalDateTime.of(2026, 11, 3, 10, 0))
        val s = t.snooze(CashGapState(), "w1", Duration.ofHours(2))
        assertTrue(tracker(LocalDateTime.of(2026, 11, 3, 11, 0)).prompts(listOf(wd, spent), s).isEmpty())
        assertEquals(1, tracker(LocalDateTime.of(2026, 11, 3, 12, 30)).prompts(listOf(wd, spent), s).size)
    }
}
