package app.ledgerly.core.budget

import app.ledgerly.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

/** Relative spend intensity per weekday, mean 1.0 (e.g. Sat 1.8, Tue 0.6). */
data class DayOfWeekWeights(val weights: Map<DayOfWeek, BigDecimal>) {
    fun of(d: DayOfWeek): BigDecimal = weights[d] ?: BigDecimal.ONE
    val isUniform: Boolean get() = DayOfWeek.entries.all { of(it).compareTo(BigDecimal.ONE) == 0 }

    companion object {
        val UNIFORM = DayOfWeekWeights(DayOfWeek.entries.associateWith { BigDecimal.ONE })

        /**
         * Learn weights from history: average spend per occurrence of each weekday in [from, toExclusive),
         * normalised to mean 1. Weekdays with no spend get a small floor (0.1) so projections never assume zero.
         */
        fun fromHistory(daily: Map<LocalDate, Money>, from: LocalDate, toExclusive: LocalDate): DayOfWeekWeights {
            val totals = DayOfWeek.entries.associateWith { BigDecimal.ZERO }.toMutableMap()
            val counts = DayOfWeek.entries.associateWith { 0 }.toMutableMap()
            var d = from
            while (d.isBefore(toExclusive)) {
                counts[d.dayOfWeek] = counts[d.dayOfWeek]!! + 1
                daily[d]?.let { totals[d.dayOfWeek] = totals[d.dayOfWeek]!! + BigDecimal(maxOf(0L, it.minor)) }
                d = d.plusDays(1)
            }
            val avg = DayOfWeek.entries.associateWith {
                if (counts[it] == 0) BigDecimal.ZERO else totals[it]!!.divide(BigDecimal(counts[it]!!), 6, RoundingMode.HALF_EVEN)
            }
            val mean = avg.values.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(7), 6, RoundingMode.HALF_EVEN)
            if (mean.signum() == 0) return UNIFORM
            val floor = BigDecimal("0.1")
            return DayOfWeekWeights(avg.mapValues { (_, v) -> v.divide(mean, 6, RoundingMode.HALF_EVEN).max(floor) })
        }
    }
}

data class PaceProjection(
    val spent: Money,
    val available: Money,
    /** spent / elapsed days × total days. */
    val projectedLinear: Money,
    /** spent + daily rate × remaining weekday weights. Equals linear when weights are uniform. */
    val projectedWeighted: Money,
    /** The figure to show: weighted if weights were learned, else linear. */
    val projected: Money,
    /** projected − available when positive. */
    val projectedOverBy: Money?,
    val message: String,
)

/**
 * End-of-period spend projection. Today counts as an elapsed day. With no spend yet, projection = 0.
 */
class PaceProjector(private val weights: DayOfWeekWeights = DayOfWeekWeights.UNIFORM) {

    fun project(period: BudgetPeriod, today: LocalDate, spent: Money, available: Money, label: String): PaceProjection {
        val elapsed = period.elapsedIncluding(today)
        val c = spent.currency
        val linear = if (elapsed == 0) spent else MoneyMath.scale(spent, BigDecimal(period.days), BigDecimal(elapsed))
        val elapsedDates = period.dates().take(elapsed).toList()
        val remainingDates = period.dates().drop(elapsed).toList()
        val wElapsed = elapsedDates.fold(BigDecimal.ZERO) { a, d -> a + weights.of(d.dayOfWeek) }
        val wRemaining = remainingDates.fold(BigDecimal.ZERO) { a, d -> a + weights.of(d.dayOfWeek) }
        val weighted = if (wElapsed.signum() == 0) linear else spent + MoneyMath.scale(spent, wRemaining, wElapsed)
        val projected = if (weights.isUniform) linear else weighted
        val over = projected - available
        val message = when {
            spent.minor <= 0 -> "Nothing spent on $label yet"
            over.isPositive -> "At this pace you'll exceed $label by ${MoneyMath.human(over)}"
            else -> "On track: about ${MoneyMath.human(-over)} left in $label at period end"
        }
        return PaceProjection(spent, available, linear, weighted, projected, if (over.isPositive) over else null, message)
            .also { require(it.spent.currency == c) }
    }
}
