package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TreeMap

/** Precision used for all intermediate rate arithmetic. */
internal val FX_MC = MathContext(20, RoundingMode.HALF_EVEN)

/** One market observation: 1 [base] = [rate] [quote] on [date]. */
data class FxRate(
    val date: LocalDate,
    val base: CurrencyCode,
    val quote: CurrencyCode,
    val rate: BigDecimal,
    val source: String,
) {
    init {
        require(rate.signum() > 0) { "Rate must be positive: $rate" }
        require(base != quote) { "Base and quote must differ: $base" }
    }

    fun inverse(): FxRate = copy(base = quote, quote = base, rate = BigDecimal.ONE.divide(rate, FX_MC))
}

/** How a [RateQuote] was derived. */
enum class RateDerivation { IDENTITY, DIRECT, INVERSE, TRIANGULATED }

/**
 * Result of a rate lookup: 1 [base] = [rate] [quote].
 * [rateDate] is the date of the (oldest) underlying observation; [isStale] is true when it is
 * older than the requested date by more than the store's stale tolerance.
 */
data class RateQuote(
    val base: CurrencyCode,
    val quote: CurrencyCode,
    val rate: BigDecimal,
    val requestedDate: LocalDate,
    val rateDate: LocalDate,
    val source: String,
    val isStale: Boolean,
    val derivation: RateDerivation,
    /** Currencies traversed, e.g. [JPY, EUR, INR] for a triangulated quote. */
    val path: List<CurrencyCode> = listOf(base, quote),
) {
    val ageDays: Long get() = ChronoUnit.DAYS.between(rateDate, requestedDate)
}

/** Storage + lookup of market rates. */
interface RateStore {
    fun put(rate: FxRate)
    fun putAll(rates: Iterable<FxRate>) = rates.forEach { put(it) }

    /** Exact stored observation for the pair on that date (no inverse/triangulation). */
    fun get(base: CurrencyCode, quote: CurrencyCode, date: LocalDate): FxRate?

    /**
     * Best rate for [base]->[quote] on [date]: direct pair, inverse pair, or triangulated via a pivot,
     * falling back to the nearest earlier date within the configured max staleness. Null if none.
     */
    fun lookup(base: CurrencyCode, quote: CurrencyCode, date: LocalDate): RateQuote?
}

/** Fetches rates from a remote source. The network implementation lives in the Android app. */
fun interface RateProvider {
    /** Rates for [date] (null = latest available). */
    fun fetch(date: LocalDate?): List<FxRate>
}

class RateUnavailableException(base: CurrencyCode, quote: CurrencyCode, date: LocalDate) :
    IllegalStateException("No FX rate for $base->$quote on or before $date")

/**
 * Thread-safe in-memory [RateStore].
 * @param maxStalenessDays how far back to look for an earlier rate when the date has none.
 * @param staleToleranceDays rates older than this many days are flagged [RateQuote.isStale]
 *        (0 = any fallback to an earlier date is stale; 3 tolerates weekends).
 * @param pivots currencies used for triangulation, in preference order.
 */
class InMemoryRateStore(
    val maxStalenessDays: Long = 7,
    val staleToleranceDays: Long = 0,
    val pivots: List<CurrencyCode> = listOf(CurrencyCode.EUR, CurrencyCode.USD),
) : RateStore {
    private val data = HashMap<Pair<CurrencyCode, CurrencyCode>, TreeMap<LocalDate, FxRate>>()
    private val lock = Any()

    override fun put(rate: FxRate) {
        synchronized(lock) {
            data.getOrPut(rate.base to rate.quote) { TreeMap() }[rate.date] = rate
        }
    }

    override fun get(base: CurrencyCode, quote: CurrencyCode, date: LocalDate): FxRate? =
        synchronized(lock) { data[base to quote]?.get(date) }

    /** All stored pairs (directional). */
    fun pairs(): Set<Pair<CurrencyCode, CurrencyCode>> = synchronized(lock) { data.keys.toSet() }

    private fun floor(base: CurrencyCode, quote: CurrencyCode, date: LocalDate): FxRate? {
        val e = data[base to quote]?.floorEntry(date) ?: return null
        return if (ChronoUnit.DAYS.between(e.key, date) <= maxStalenessDays) e.value else null
    }

    /** Direct or inverse rate, preferring the most recent observation (direct wins ties). */
    private fun leg(base: CurrencyCode, quote: CurrencyCode, date: LocalDate): Pair<FxRate, RateDerivation>? {
        val d = floor(base, quote, date)
        val i = floor(quote, base, date)
        return when {
            d != null && (i == null || !i.date.isAfter(d.date)) -> d to RateDerivation.DIRECT
            i != null -> i.inverse() to RateDerivation.INVERSE
            else -> null
        }
    }

    override fun lookup(base: CurrencyCode, quote: CurrencyCode, date: LocalDate): RateQuote? = synchronized(lock) {
        if (base == quote) {
            return RateQuote(base, quote, BigDecimal.ONE, date, date, "identity", false, RateDerivation.IDENTITY)
        }
        leg(base, quote, date)?.let { (r, how) ->
            return quote(base, quote, r.rate, date, r.date, r.source, how, listOf(base, quote))
        }
        var best: RateQuote? = null
        for (p in pivots) {
            if (p == base || p == quote) continue
            val (l1, _) = leg(base, p, date) ?: continue
            val (l2, _) = leg(p, quote, date) ?: continue
            val rd = minOf(l1.date, l2.date)
            val src = if (l1.source == l2.source) l1.source else "${l1.source}+${l2.source}"
            val q = quote(base, quote, l1.rate.multiply(l2.rate, FX_MC), date, rd, src, RateDerivation.TRIANGULATED, listOf(base, p, quote))
            if (best == null || q.rateDate.isAfter(best.rateDate)) best = q
        }
        best
    }

    private fun quote(
        base: CurrencyCode, quote: CurrencyCode, rate: BigDecimal, requested: LocalDate, rateDate: LocalDate,
        source: String, how: RateDerivation, path: List<CurrencyCode>,
    ) = RateQuote(
        base, quote, rate, requested, rateDate, source,
        isStale = ChronoUnit.DAYS.between(rateDate, requested) > staleToleranceDays,
        derivation = how, path = path,
    )
}
