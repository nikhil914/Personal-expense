package app.ledgerly.core.budget

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class AccountBalance(val accountId: Id, val name: String, val balance: Money, val isSpendingAccount: Boolean = true)

/** Expected income or a bill on a date. [settled] items (already received/paid) are ignored. */
data class PlannedItem(val name: String, val amount: Money, val date: LocalDate, val settled: Boolean = false)

/** Money still to be set aside this period for a goal or sinking fund. */
data class Contribution(val name: String, val amount: Money)

data class FlexibleRemaining(val name: String, val remaining: Money)

/** Recurring bill/income schedule, expanded into [PlannedItem]s for a period. */
sealed class Recurrence {
    abstract fun dates(period: BudgetPeriod): List<LocalDate>
    /** Day of month, clamped to month length. */
    data class Monthly(val dayOfMonth: Int) : Recurrence() {
        override fun dates(period: BudgetPeriod): List<LocalDate> {
            val out = ArrayList<LocalDate>()
            var ym = java.time.YearMonth.from(period.start)
            while (!ym.atDay(1).isAfter(period.lastDay)) {
                val d = ym.atDay(minOf(dayOfMonth, ym.lengthOfMonth()))
                if (d in period) out += d
                ym = ym.plusMonths(1)
            }
            return out
        }
    }
    data class Weekly(val day: DayOfWeek) : Recurrence() {
        override fun dates(period: BudgetPeriod): List<LocalDate> =
            generateSequence(period.start.with(TemporalAdjusters.nextOrSame(day))) { it.plusWeeks(1) }.takeWhile { it in period }.toList()
    }
}

data class RecurringItem(val name: String, val amount: Money, val recurrence: Recurrence) {
    fun occurrences(period: BudgetPeriod, settledBefore: LocalDate? = null): List<PlannedItem> =
        recurrence.dates(period).map { PlannedItem(name, amount, it, settled = settledBefore != null && it.isBefore(settledBefore)) }
}

data class SafeToSpendInput(
    val period: BudgetPeriod,
    val balances: List<AccountBalance>,
    val expectedIncome: List<PlannedItem> = emptyList(),
    val bills: List<PlannedItem> = emptyList(),
    val goalContributions: List<Contribution> = emptyList(),
    val sinkingFundContributions: List<Contribution> = emptyList(),
    val buffer: Money,
    /** Spend already made today (balances are assumed to be current, i.e. already net of it). */
    val spentToday: Money = Money.zero(buffer.currency),
    val flexibleBudgets: List<FlexibleRemaining> = emptyList(),
    /** Typical daily discretionary spend; an allowance below it is TIGHT. */
    val typicalDailySpend: Money? = null,
)

enum class SafeToSpendState { HEALTHY, TIGHT, OVER }

enum class BreakdownKind { BALANCE, INCOME, BILLS, GOALS, SINKING_FUNDS, BUFFER, SPENT_TODAY, TOTAL }

/** One signed line of the breakdown (+ adds to the pool, − removes from it). */
data class BreakdownLine(val kind: BreakdownKind, val label: String, val amount: Money, val items: List<String> = emptyList())

data class SafeToSpendResult(
    /** What's left to spend today (never negative); the widget's big number. */
    val amount: Money,
    /** Today's allowance before today's spending. */
    val dailyAllowance: Money,
    /** Money free for the rest of the period, after today's spending. */
    val pool: Money,
    val daysLeft: Int,
    val state: SafeToSpendState,
    /** How far over today's allowance (or the whole period, when the pool is negative). */
    val overBy: Money,
    val breakdown: List<BreakdownLine>,
    val explanation: List<String>,
    /** Per-day remaining of each flexible budget. */
    val flexiblePerDay: List<FlexibleRemaining>,
    /** Short text for the home-screen widget, e.g. "₹1,250 safe to spend today". */
    val widgetHeadline: String,
)

/**
 * Safe to spend today = (spending-account balances + expected income before period end − unpaid bills due
 * before period end − remaining goal contributions − sinking-fund contributions − buffer) ÷ days left
 * including today, minus what has already been spent today.
 *
 * Expected income dated before today is ignored (it is late, don't count on it); overdue unpaid bills are
 * still deducted. With [capByFlexibleBudgets], the amount is also capped at the sum of flexible per-day
 * remainders. This is the single source of truth for the Android widget.
 */
class SafeToSpendCalculator(
    private val clock: Clock,
    private val tightRatio: BigDecimal = BigDecimal("0.5"),
    private val capByFlexibleBudgets: Boolean = false,
    locale: Locale = Locale.ENGLISH,
) {
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM", locale)

    fun calculate(input: SafeToSpendInput): SafeToSpendResult {
        val today = LocalDate.now(clock)
        val c = input.buffer.currency
        val z = Money.zero(c)
        val p = input.period
        require(today in p) { "Today $today is outside the period $p" }
        fun chk(m: Money) = m.also { require(it.currency == c) { "Currency mismatch: ${it.currency} vs $c" } }

        val spendAccts = input.balances.filter { it.isSpendingAccount }
        val balance = MoneyMath.sum(spendAccts.map { chk(it.balance) }, c)
        val income = input.expectedIncome.filter { !it.settled && !it.date.isBefore(today) && it.date in p }
        val bills = input.bills.filter { !it.settled && it.date.isBefore(p.endExclusive) }
        val incomeSum = MoneyMath.sum(income.map { chk(it.amount) }, c)
        val billSum = MoneyMath.sum(bills.map { chk(it.amount) }, c)
        val goalSum = MoneyMath.sum(input.goalContributions.map { chk(it.amount) }, c)
        val fundSum = MoneyMath.sum(input.sinkingFundContributions.map { chk(it.amount) }, c)
        val spentToday = chk(input.spentToday)

        val pool = balance + incomeSum - billSum - goalSum - fundSum - chk(input.buffer)
        val days = p.daysLeftIncluding(today)
        val startOfDayPool = pool + spentToday
        val allowance = if (startOfDayPool.isPositive) MoneyMath.divFloor(startOfDayPool, days) else z
        var remainingToday = allowance - spentToday

        val flexPerDay = input.flexibleBudgets.map {
            FlexibleRemaining(it.name, if (it.remaining.isPositive) MoneyMath.divFloor(chk(it.remaining), days) else z)
        }
        if (capByFlexibleBudgets && flexPerDay.isNotEmpty()) {
            val flexToday = MoneyMath.sum(flexPerDay.map { it.remaining }, c) - spentToday
            remainingToday = MoneyMath.min(remainingToday, flexToday)
        }
        val amount = MoneyMath.max(remainingToday, z)
        val overBy = when {
            !pool.isPositive && !startOfDayPool.isPositive -> -pool
            remainingToday.isNegative -> -remainingToday
            else -> z
        }
        val state = when {
            !startOfDayPool.isPositive || remainingToday.isNegative || (amount.isZero && spentToday.isPositive) -> SafeToSpendState.OVER
            !startOfDayPool.isPositive -> SafeToSpendState.OVER
            (input.typicalDailySpend != null && allowance < chk(input.typicalDailySpend)) ||
                BigDecimal(remainingToday.minor) < BigDecimal(allowance.minor) * tightRatio -> SafeToSpendState.TIGHT
            else -> SafeToSpendState.HEALTHY
        }

        val end = dateFmt.format(p.lastDay)
        val breakdown = buildList {
            add(BreakdownLine(BreakdownKind.BALANCE, "Balance in spending accounts", balance, spendAccts.map { "${it.name} ${MoneyMath.human(it.balance)}" }))
            if (income.isNotEmpty()) add(BreakdownLine(BreakdownKind.INCOME, "Expected income by $end", incomeSum, income.map { "${it.name} ${dateFmt.format(it.date)}" }))
            if (bills.isNotEmpty()) add(BreakdownLine(BreakdownKind.BILLS, "Bills due by $end", -billSum, bills.map { "${it.name} ${MoneyMath.human(it.amount)} on ${dateFmt.format(it.date)}" }))
            if (!goalSum.isZero) add(BreakdownLine(BreakdownKind.GOALS, "Savings goals still to fund", -goalSum, input.goalContributions.map { it.name }))
            if (!fundSum.isZero) add(BreakdownLine(BreakdownKind.SINKING_FUNDS, "Sinking funds", -fundSum, input.sinkingFundContributions.map { it.name }))
            if (!input.buffer.isZero) add(BreakdownLine(BreakdownKind.BUFFER, "Safety buffer", -input.buffer))
            add(BreakdownLine(BreakdownKind.TOTAL, "Free to spend until $end", pool))
            if (spentToday.isPositive) add(BreakdownLine(BreakdownKind.SPENT_TODAY, "Already spent today", -spentToday))
        }
        val explanation = buildList {
            breakdown.forEach { b ->
                val sign = when { b.kind == BreakdownKind.TOTAL -> "= "; b.amount.isNegative -> "− "; b.kind == BreakdownKind.BALANCE -> ""; else -> "+ " }
                val detail = if (b.items.isEmpty()) "" else " (${b.items.joinToString(", ")})"
                add("$sign${b.label}: ${MoneyMath.human(b.amount.abs())}$detail")
            }
            add("${MoneyMath.human(allowance)} a day for $days day${if (days == 1) "" else "s"} including today")
            flexPerDay.forEach { add("${it.name}: ${MoneyMath.human(it.remaining)} a day left") }
            if (state == SafeToSpendState.OVER) add(
                if (!startOfDayPool.isPositive) "Upcoming commitments exceed your balance by ${MoneyMath.human(overBy)}"
                else "You're ${MoneyMath.human(overBy)} over today's allowance"
            )
        }
        val headline = when (state) {
            SafeToSpendState.OVER -> if (!startOfDayPool.isPositive) "Over by ${MoneyMath.human(overBy)} this period" else "Over today's limit by ${MoneyMath.human(overBy)}"
            else -> "${MoneyMath.human(amount)} safe to spend today"
        }
        return SafeToSpendResult(amount, allowance, pool, days, state, overBy, breakdown, explanation, flexPerDay, headline)
    }
}
