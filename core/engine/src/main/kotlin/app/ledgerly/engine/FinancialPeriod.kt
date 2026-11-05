package app.ledgerly.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A financial month that starts on a configurable day (e.g. the 25th, salary day). [end] is exclusive. */
data class FinancialPeriod(val start: LocalDate, val end: LocalDate) {

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(end)

    fun startInstant(zone: ZoneId): Instant = start.atStartOfDay(zone).toInstant()
    fun endInstant(zone: ZoneId): Instant = end.atStartOfDay(zone).toInstant()

    /** Days remaining including [today]; at least 1. */
    fun daysLeft(today: LocalDate): Int = ChronoUnit.DAYS.between(today, end).toInt().coerceAtLeast(1)

    fun previous(): FinancialPeriod = FinancialPeriod(start.minusMonths(1), start)

    companion object {
        fun current(today: LocalDate, startDay: Int): FinancialPeriod {
            val day = startDay.coerceIn(1, 28)
            val start = if (today.dayOfMonth >= day) today.withDayOfMonth(day) else today.minusMonths(1).withDayOfMonth(day)
            return FinancialPeriod(start, start.plusMonths(1))
        }
    }
}
