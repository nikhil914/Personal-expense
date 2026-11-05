package app.ledgerly.core.budget

import app.ledgerly.core.model.Id

/** A threshold newly crossed between two snapshots of the same budget period. */
data class AlertCrossing(
    val lineId: Id,
    val categoryId: Id,
    /** Highest threshold crossed (the one to notify about). */
    val threshold: Int,
    /** All thresholds crossed in this step, ascending (e.g. [50, 80] after one big purchase). */
    val allCrossed: List<Int>,
    val previousPercent: Double,
    val currentPercent: Double,
)

/** Alert levels at configurable % thresholds (default 50/80/100). */
class AlertEvaluator(thresholds: List<Int> = DEFAULT_THRESHOLDS) {
    val thresholds: List<Int> = thresholds.distinct().sorted().also { t -> require(t.isNotEmpty() && t.all { it > 0 }) }

    /** Highest threshold reached at [percent], or null. */
    fun level(percent: Double): Int? = thresholds.lastOrNull { percent >= it }

    /**
     * Thresholds crossed going from [previous] to [current]. If the period changed (or there is no previous
     * snapshot) the previous percentage counts as 0. Lines whose usage went down never alert.
     */
    fun crossings(previous: BudgetReport?, current: BudgetReport): List<AlertCrossing> {
        val prevByLine = if (previous != null && previous.period == current.period) previous.lines.associateBy { it.line.id } else emptyMap()
        return current.lines.mapNotNull { cur ->
            val prevPct = prevByLine[cur.line.id]?.percentUsed ?: 0.0
            val crossed = thresholds.filter { prevPct < it && cur.percentUsed >= it }
            if (crossed.isEmpty()) null
            else AlertCrossing(cur.line.id, cur.line.categoryId, crossed.last(), crossed, prevPct, cur.percentUsed)
        }
    }

    companion object { val DEFAULT_THRESHOLDS = listOf(50, 80, 100) }
}
