package app.ledgerly.core.insights

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Supported recurrence periods with nominal length and matching tolerance (days). */
enum class Periodicity(val nominalDays: Double, val toleranceDays: Double, val perYear: Double) {
    WEEKLY(7.0, 1.5, 52.1775),
    BIWEEKLY(14.0, 2.5, 26.0888),
    MONTHLY(30.44, 5.0, 12.0),
    QUARTERLY(91.31, 10.0, 4.0),
    YEARLY(365.25, 18.0, 1.0);

    /** Advance a date by one period. */
    fun next(from: LocalDate, anchorDayOfMonth: Int? = null): LocalDate = when (this) {
        WEEKLY -> from.plusDays(7)
        BIWEEKLY -> from.plusDays(14)
        MONTHLY -> withDay(from.plusMonths(1), anchorDayOfMonth)
        QUARTERLY -> withDay(from.plusMonths(3), anchorDayOfMonth)
        YEARLY -> from.plusYears(1)
    }

    private fun withDay(d: LocalDate, day: Int?): LocalDate =
        if (day == null) d else d.withDayOfMonth(min(day, d.lengthOfMonth()))
}

data class RecurringConfig(
    /** Minimum charges for weekly/biweekly/monthly series. */
    val minOccurrences: Int = 3,
    /** Minimum charges for quarterly/yearly series. */
    val minOccurrencesLongPeriod: Int = 2,
    /** Share of intervals that must match the period (a skipped cycle counts as a match). */
    val minRegularity: Double = 0.75,
    /** Coefficient of variation at or below which amounts count as "stable". */
    val stableAmountCv: Double = 0.12,
    /** Series with a higher CV are not considered recurring at all. */
    val maxAmountCv: Double = 0.6,
    /** Amount-cluster tolerance used to split one merchant into several series (e.g. two Apple plans). */
    val clusterTolerance: Double = 0.2,
    /** A series whose next expected date is this many periods overdue is considered lapsed (inactive). */
    val lapsedAfterPeriods: Double = 1.6,
)

/** A detected recurring income or expense series. Amounts are in base currency. */
data class RecurringSeries(
    val key: String,
    val merchant: String,
    val flow: Flow,
    val periodicity: Periodicity,
    val transactions: List<Transaction>,
    val averageAmount: Money,
    val lastAmount: Money,
    val previousAmount: Money?,
    val amountStable: Boolean,
    val amountCv: Double,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val nextExpectedDate: LocalDate,
    val nextExpectedAmount: Money,
    val anchorDayOfMonth: Int?,
    val confidence: Double,
    val categoryId: Id?,
    /** False when the series appears to have stopped (overdue by > lapsedAfterPeriods). */
    val active: Boolean,
) {
    val transactionIds: Set<Id> get() = transactions.mapTo(mutableSetOf()) { it.id }

    /** Expected occurrence dates from (exclusive of) the last charge up to and including [until]. */
    fun occurrencesUntil(until: LocalDate, from: LocalDate? = null): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var d = nextExpectedDate
        var guard = 0
        while (!d.isAfter(until) && guard++ < 2000) {
            if (from == null || !d.isBefore(from)) out += d
            d = periodicity.next(d, anchorDayOfMonth)
        }
        return out
    }
}

/**
 * Detects recurring series by grouping transactions on normalised merchant + flow, then testing
 * intervals against each [Periodicity] with tolerance (robust to jittered posting dates and a skipped cycle).
 */
class RecurringDetector(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val config: RecurringConfig = RecurringConfig(),
) {
    private val zone get() = clock.zone

    fun detect(transactions: Collection<Transaction>): List<RecurringSeries> {
        val today = LocalDate.now(clock)
        val groups = transactions.asSequence()
            .filter { it.isActive() && it.flow() != Flow.NEUTRAL }
            .mapNotNull { t -> t.merchantKey()?.let { k -> (k to t.flow()) to t } }
            .groupBy({ it.first }, { it.second })

        val result = mutableListOf<RecurringSeries>()
        for ((k, txns) in groups) {
            val (key, flow) = k
            val whole = analyse(key, flow, txns, today)
            if (whole != null) { result += whole; continue }
            // Try splitting by amount clusters (one merchant, several plans).
            for (cluster in clusterByAmount(txns)) {
                if (cluster.size == txns.size) continue
                analyse(key, flow, cluster, today)?.let { result += it }
            }
        }
        return result.sortedWith(compareBy({ it.nextExpectedDate }, { it.merchant }))
    }

    private fun clusterByAmount(txns: List<Transaction>): List<List<Transaction>> {
        val sorted = txns.sortedBy { it.baseAbs.minor }
        val clusters = mutableListOf<MutableList<Transaction>>()
        for (t in sorted) {
            val c = clusters.lastOrNull()
            val ref = c?.let { mean(it.map { x -> x.baseAbs.minor.toDouble() }) }
            if (c != null && ref != null && ref > 0 && abs(t.baseAbs.minor - ref) / ref <= config.clusterTolerance) c += t
            else clusters += mutableListOf(t)
        }
        return clusters
    }

    private fun analyse(key: String, flow: Flow, raw: List<Transaction>, today: LocalDate): RecurringSeries? {
        if (raw.size < 2) return null
        val currency = dominantBaseCurrency(raw)
        // One charge per day (keep the largest; duplicates are a dedup concern).
        val txns = raw.filter { it.base.currency == currency }
            .groupBy { it.localDate(zone) }
            .map { (_, ts) -> ts.maxBy { it.baseAbs.minor } }
            .sortedBy { it.occurredAt }
        if (txns.size < 2) return null
        val dates = txns.map { it.localDate(zone) }
        val intervals = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toDouble() }
        val med = median(intervals)

        val period = Periodicity.entries.firstOrNull { abs(med - it.nominalDays) <= it.toleranceDays }
            ?: return null
        val minOcc = if (period == Periodicity.QUARTERLY || period == Periodicity.YEARLY)
            config.minOccurrencesLongPeriod else config.minOccurrences
        if (txns.size < minOcc) return null

        val matching = intervals.count { iv ->
            val cycles = (iv / period.nominalDays).roundToInt().coerceAtLeast(1)
            cycles <= 2 && abs(iv - cycles * period.nominalDays) <= period.toleranceDays * cycles
        }
        val regularity = matching.toDouble() / intervals.size
        if (regularity < config.minRegularity) return null

        val amounts = txns.map { it.baseAbs.minor.toDouble() }
        val avg = mean(amounts)
        val cv = if (avg == 0.0) 0.0 else stddev(amounts) / avg
        if (cv > config.maxAmountCv) return null
        val stable = cv <= config.stableAmountCv

        val last = txns.last()
        val lastDate = dates.last()
        val anchor = if (period == Periodicity.MONTHLY || period == Periodicity.QUARTERLY)
            median(dates.takeLast(4).map { it.dayOfMonth.toDouble() }).roundToInt() else null
        val next = period.next(lastDate, anchor)
        val nextAmount = if (stable || txns.size < 3) last.baseAbs
        else moneyOfMinor(mean(amounts.takeLast(3)), currency)

        val countFactor = min(1.0, txns.size / 6.0) * 0.3 + 0.7
        val amountFactor = if (stable) 1.0 else (1.0 - cv).coerceIn(0.4, 1.0)
        val confidence = (regularity * amountFactor * countFactor).coerceIn(0.0, 1.0)
        val overdueDays = ChronoUnit.DAYS.between(next, today).toDouble()
        val active = overdueDays <= period.nominalDays * (config.lapsedAfterPeriods - 1.0) + period.toleranceDays

        val name = txns.lastOrNull { !it.merchant.isNullOrBlank() }?.merchant ?: MerchantNormalizer.display(key)
        return RecurringSeries(
            key = key,
            merchant = name,
            flow = flow,
            periodicity = period,
            transactions = txns,
            averageAmount = moneyOfMinor(avg, currency),
            lastAmount = last.baseAbs,
            previousAmount = txns.getOrNull(txns.size - 2)?.baseAbs,
            amountStable = stable,
            amountCv = cv,
            firstDate = dates.first(),
            lastDate = lastDate,
            nextExpectedDate = next,
            nextExpectedAmount = nextAmount,
            anchorDayOfMonth = anchor,
            confidence = confidence,
            categoryId = txns.mapNotNull { it.categoryId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            active = active,
        )
    }
}
