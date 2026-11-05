package app.ledgerly.core.budget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Half-open date range [start, endExclusive). */
data class BudgetPeriod(val start: LocalDate, val endExclusive: LocalDate) {
    init { require(endExclusive.isAfter(start)) { "Empty period $start..$endExclusive" } }

    val days: Int get() = ChronoUnit.DAYS.between(start, endExclusive).toInt()
    val lastDay: LocalDate get() = endExclusive.minusDays(1)

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    /** Days from [today] to the last day, both inclusive. 0 after the period, [days] before it. */
    fun daysLeftIncluding(today: LocalDate): Int = when {
        today.isBefore(start) -> days
        !today.isBefore(endExclusive) -> 0
        else -> ChronoUnit.DAYS.between(today, endExclusive).toInt()
    }

    /** Days from start to [today] inclusive, clamped to 0..days. */
    fun elapsedIncluding(today: LocalDate): Int = days - daysLeftIncluding(today) + if (today in this) 1 else 0

    fun dates(): Sequence<LocalDate> = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(endExclusive) }
}

enum class PeriodKind { MONTHLY, WEEKLY, CUSTOM }

/** How a budget's periods are cut. CUSTOM periods are [customLengthDays] long, aligned to [customAnchor]. */
data class PeriodSpec(
    val kind: PeriodKind = PeriodKind.MONTHLY,
    val customLengthDays: Int = 0,
    val customAnchor: LocalDate? = null,
) {
    init {
        if (kind == PeriodKind.CUSTOM) require(customLengthDays > 0 && customAnchor != null) { "CUSTOM needs length + anchor" }
    }

    companion object {
        val MONTHLY = PeriodSpec(PeriodKind.MONTHLY)
        val WEEKLY = PeriodSpec(PeriodKind.WEEKLY)
        fun custom(anchor: LocalDate, lengthDays: Int) = PeriodSpec(PeriodKind.CUSTOM, lengthDays, anchor)
    }
}

/**
 * Financial-month / week calculator. With `monthStartDay = 25` the month runs 25th → 24th of the next month
 * (start day is clamped to short months, so 31 becomes 28/29 Feb, 30 Apr, ...). If
 * [shiftStartBeforeWeekend] is true, a start day falling on Sat/Sun moves to the preceding Friday
 * (salary is usually credited the working day before).
 */
class PeriodCalculator(
    val monthStartDay: Int = 1,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val shiftStartBeforeWeekend: Boolean = false,
) {
    init { require(monthStartDay in 1..31) { "monthStartDay must be 1..31" } }

    fun monthStart(ym: YearMonth): LocalDate {
        val d = ym.atDay(minOf(monthStartDay, ym.lengthOfMonth()))
        if (!shiftStartBeforeWeekend) return d
        return when (d.dayOfWeek) {
            DayOfWeek.SATURDAY -> d.minusDays(1)
            DayOfWeek.SUNDAY -> d.minusDays(2)
            else -> d
        }
    }

    fun month(date: LocalDate): BudgetPeriod {
        val ym = YearMonth.from(date)
        // A shifted start for next month can fall into this calendar month (e.g. 25th is Sunday → 23rd).
        val next = ym.plusMonths(1)
        if (!date.isBefore(monthStart(next))) return BudgetPeriod(monthStart(next), monthStart(next.plusMonths(1)))
        val s = monthStart(ym)
        return if (!date.isBefore(s)) BudgetPeriod(s, monthStart(next))
        else BudgetPeriod(monthStart(ym.minusMonths(1)), s)
    }

    fun week(date: LocalDate): BudgetPeriod {
        val s = date.with(TemporalAdjusters.previousOrSame(weekStart))
        return BudgetPeriod(s, s.plusDays(7))
    }

    fun custom(date: LocalDate, anchor: LocalDate, lengthDays: Int): BudgetPeriod {
        val offset = Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), lengthDays.toLong())
        val s = anchor.plusDays(offset * lengthDays)
        return BudgetPeriod(s, s.plusDays(lengthDays.toLong()))
    }

    fun periodFor(spec: PeriodSpec, date: LocalDate): BudgetPeriod = when (spec.kind) {
        PeriodKind.MONTHLY -> month(date)
        PeriodKind.WEEKLY -> week(date)
        PeriodKind.CUSTOM -> custom(date, spec.customAnchor!!, spec.customLengthDays)
    }

    fun next(spec: PeriodSpec, p: BudgetPeriod): BudgetPeriod = periodFor(spec, p.endExclusive)
    fun previous(spec: PeriodSpec, p: BudgetPeriod): BudgetPeriod = periodFor(spec, p.start.minusDays(1))

    /** [count] consecutive periods starting with the one containing [from]. */
    fun series(spec: PeriodSpec, from: LocalDate, count: Int): List<BudgetPeriod> {
        val out = ArrayList<BudgetPeriod>()
        var p = periodFor(spec, from)
        repeat(count) { out += p; p = next(spec, p) }
        return out
    }

    /**
     * The month a financial month is named after: the calendar month holding most of its days
     * (25 Dec – 24 Jan → January).
     */
    fun label(p: BudgetPeriod): YearMonth =
        p.dates().groupingBy { YearMonth.from(it) }.eachCount().maxWith(compareBy<Map.Entry<YearMonth, Int>> { it.value }.thenBy { it.key }).key
}
