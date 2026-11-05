package app.ledgerly.core.audit

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Record of a locked month (typically after reconciliation). */
data class LockedPeriod(val month: YearMonth, val lockedAt: Instant, val lockedBy: Actor, val reason: String? = null)

/**
 * Month-granular period locks, evaluated in [zone]. Edits to transactions dated in a locked month
 * must pass an explicit override flag to [TransactionLedger] and are logged with `lockOverride = true`.
 */
class PeriodLock(val zone: ZoneId) {
    private val locks = LinkedHashMap<YearMonth, LockedPeriod>()

    @Synchronized
    fun lock(month: YearMonth, at: Instant, by: Actor = Actor.USER, reason: String? = null): LockedPeriod =
        locks.getOrPut(month) { LockedPeriod(month, at, by, reason) }

    @Synchronized
    fun unlock(month: YearMonth): Boolean = locks.remove(month) != null

    @Synchronized
    fun isLocked(month: YearMonth): Boolean = month in locks

    fun isLocked(instant: Instant): Boolean = isLocked(monthOf(instant))

    fun monthOf(instant: Instant): YearMonth = YearMonth.from(instant.atZone(zone))

    @Synchronized
    fun lockedPeriods(): List<LockedPeriod> = locks.values.sortedBy { it.month }
}
