package app.ledgerly.core.budget

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Money set aside monthly for a known future cost (insurance, school fees, Diwali). */
data class SinkingFund(
    val id: Id,
    val name: String,
    val target: Money,
    val startDate: LocalDate,
    val dueDate: LocalDate,
    val saved: Money = Money.zero(target.currency),
) {
    init { require(dueDate.isAfter(startDate)) { "Due date must be after start" } }

    companion object {
        /** An annual cost due on [nextDue]: accrues over the 12 months before it. */
        fun annual(id: Id, name: String, annualCost: Money, nextDue: LocalDate, saved: Money = Money.zero(annualCost.currency)) =
            SinkingFund(id, name, annualCost, nextDue.minusYears(1), nextDue, saved)
    }
}

enum class FundState { AHEAD, ON_TRACK, BEHIND, FUNDED, OVERDUE }

data class SinkingFundStatus(
    val fund: SinkingFund,
    val monthsTotal: Int,
    /** Contributions left including the current month. */
    val monthsRemaining: Int,
    /** target / months: the plain monthly accrual. */
    val monthlyAccrual: Money,
    /** What should be saved after this month's contribution. */
    val expectedByNow: Money,
    /** saved − expected (positive = ahead). */
    val difference: Money,
    /** What to put in this month to finish on time from the current balance. */
    val contributionNeeded: Money,
    val state: FundState,
)

/**
 * Sinking-fund maths. One contribution per month from the start month; AHEAD means at least one full
 * monthly accrual ahead of schedule, BEHIND means below the expected balance.
 */
class SinkingFundCalculator(private val clock: Clock) {

    fun status(fund: SinkingFund, today: LocalDate = LocalDate.now(clock)): SinkingFundStatus {
        val total = maxOf(1, ChronoUnit.MONTHS.between(fund.startDate, fund.dueDate).toInt())
        val k = ChronoUnit.MONTHS.between(fund.startDate, today).toInt().coerceIn(0, total - 1)
        val accrual = MoneyMath.divCeil(fund.target, total)
        val expected = if (today.isBefore(fund.startDate)) Money.zero(fund.target.currency)
        else MoneyMath.scale(fund.target, BigDecimal(k + 1), BigDecimal(total))
        val remainingMonths = if (today.isBefore(fund.startDate)) total else total - k
        val shortfall = MoneyMath.max(fund.target - fund.saved, Money.zero(fund.target.currency))
        val diff = fund.saved - expected
        val state = when {
            fund.saved >= fund.target -> FundState.FUNDED
            !today.isBefore(fund.dueDate) -> FundState.OVERDUE
            diff >= accrual -> FundState.AHEAD
            diff.isNegative -> FundState.BEHIND
            else -> FundState.ON_TRACK
        }
        val needed = if (state == FundState.FUNDED) Money.zero(fund.target.currency)
        else MoneyMath.divCeil(shortfall, maxOf(1, remainingMonths))
        return SinkingFundStatus(fund, total, remainingMonths, accrual, expected, diff, needed, state)
    }
}

data class SavingsGoal(
    val id: Id,
    val name: String,
    val target: Money,
    val saved: Money,
    val startDate: LocalDate,
    val targetDate: LocalDate? = null,
)

data class GoalProgress(
    val goal: SavingsGoal,
    val percent: Double,
    val remaining: Money,
    val monthsLeft: Int?,
    val monthlyContributionNeeded: Money?,
    val expectedByNow: Money?,
    /** null when the goal has no target date. */
    val onTrack: Boolean?,
    val isComplete: Boolean,
)

data class EmergencyFundStatus(
    val balance: Money,
    val monthlyEssentials: Money,
    /** balance / monthly essentials, 1 decimal. */
    val monthsCovered: BigDecimal,
    val targetMonths: Int,
    val shortfall: Money,
)

class GoalCalculator(private val clock: Clock) {

    fun progress(goal: SavingsGoal, today: LocalDate = LocalDate.now(clock)): GoalProgress {
        val c = goal.target.currency
        val remaining = MoneyMath.max(goal.target - goal.saved, Money.zero(c))
        val complete = remaining.isZero
        val td = goal.targetDate
        if (td == null) return GoalProgress(goal, MoneyMath.percent(goal.saved, goal.target), remaining, null, null, null, null, complete)
        val monthsLeft = monthsCeil(today, td)
        val needed = if (complete) Money.zero(c) else MoneyMath.divCeil(remaining, maxOf(1, monthsLeft))
        val totalDays = ChronoUnit.DAYS.between(goal.startDate, td).coerceAtLeast(1)
        val elapsed = ChronoUnit.DAYS.between(goal.startDate, today).coerceIn(0, totalDays)
        val expected = MoneyMath.scale(goal.target, BigDecimal(elapsed), BigDecimal(totalDays))
        return GoalProgress(goal, MoneyMath.percent(goal.saved, goal.target), remaining, monthsLeft, needed, expected,
            goal.saved >= expected, complete)
    }

    /** What is still to be contributed in the current period (for safe-to-spend). */
    fun remainingThisPeriod(goal: SavingsGoal, contributedThisPeriod: Money, today: LocalDate = LocalDate.now(clock)): Money {
        val need = progress(goal, today).monthlyContributionNeeded ?: return Money.zero(goal.target.currency)
        return MoneyMath.max(need - contributedThisPeriod, Money.zero(goal.target.currency))
    }

    fun emergencyFund(balance: Money, monthlyEssentials: Money, targetMonths: Int = 6): EmergencyFundStatus {
        val covered = if (monthlyEssentials.minor <= 0) BigDecimal(99)
        else BigDecimal(balance.minor).divide(BigDecimal(monthlyEssentials.minor), 1, RoundingMode.DOWN)
        val shortfall = MoneyMath.max(monthlyEssentials * targetMonths - balance, Money.zero(balance.currency))
        return EmergencyFundStatus(balance, monthlyEssentials, covered, targetMonths, shortfall)
    }

    private fun monthsCeil(from: LocalDate, to: LocalDate): Int {
        if (!to.isAfter(from)) return 0
        val m = ChronoUnit.MONTHS.between(from, to).toInt()
        return if (from.plusMonths(m.toLong()).isBefore(to)) m + 1 else m
    }
}
