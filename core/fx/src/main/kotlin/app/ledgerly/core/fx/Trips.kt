package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import app.ledgerly.core.model.TxnType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class TripSuggestion(
    val currency: CurrencyCode,
    val countryGuess: String?,
    val startDate: LocalDate,
    val txnIds: List<Id>,
) {
    val message: String
        get() = "Looks like you're in ${countryGuess?.let { CurrencyCountries.countryName(it) } ?: currency.code}. Start a trip from $startDate?"
}

data class TripEndSuggestion(val tripId: Id, val suggestedEnd: LocalDate, val daysWithoutForeign: Long)

/**
 * Detects trips from the transaction stream.
 * @param minForeignTxns foreign transactions (same currency/country) needed before suggesting a trip.
 * @param endAfterDays suggest ending a trip after this many days without matching foreign transactions.
 * @param merchantCountry optional country resolver (ISO alpha-2) from merchant data.
 * @param ignore predicate for foreign transactions that do not imply travel (e.g. online subscriptions billed in USD).
 */
class TripDetector(
    private val homeCurrency: CurrencyCode,
    private val homeCountry: String? = CurrencyCountries.countryFor(homeCurrency),
    private val minForeignTxns: Int = 1,
    private val endAfterDays: Long = 3,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val merchantCountry: (Transaction) -> String? = { null },
    private val ignore: (Transaction) -> Boolean = { false },
) {
    private fun date(t: Transaction) = t.occurredAt.atZone(zone).toLocalDate()

    private fun covers(trip: Trip, d: LocalDate) = !d.isBefore(trip.start) && (trip.end == null || !d.isAfter(trip.end))

    /** (currency, country) signal of a foreign transaction, or null if domestic. */
    private fun signal(t: Transaction): Pair<CurrencyCode, String?>? {
        val country = merchantCountry(t)?.uppercase()
        val foreignCountry = country != null && homeCountry != null && country != homeCountry.uppercase()
        val foreignCurrency = t.original.currency != homeCurrency
        if (!foreignCountry && !foreignCurrency) return null
        if (country != null && homeCountry != null && country == homeCountry.uppercase()) return null
        val cur = if (foreignCurrency) t.original.currency else CurrencyCountries.currencyFor(country!!) ?: t.original.currency
        return cur to (country ?: CurrencyCountries.countryFor(cur))
    }

    private fun matchesTrip(trip: Trip, cur: CurrencyCode, country: String?) =
        trip.currency == cur || (country != null && trip.countryCode?.equals(country, true) == true)

    fun suggestStart(transactions: List<Transaction>, trips: List<Trip>): List<TripSuggestion> {
        val uncovered = transactions.asSequence()
            .filter { !it.isDeleted && it.tripId == null && !ignore(it) && it.type != TxnType.TRANSFER }
            .mapNotNull { t -> signal(t)?.let { Triple(t, it.first, it.second) } }
            .filter { (t, cur, country) -> trips.none { matchesTrip(it, cur, country) && covers(it, date(t)) } }
            .toList()
        return uncovered.groupBy { it.second }
            .filter { it.value.size >= minForeignTxns }
            .map { (cur, list) ->
                val sorted = list.sortedBy { it.first.occurredAt }
                val country = sorted.mapNotNull { it.third }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                TripSuggestion(cur, country, date(sorted.first().first), sorted.map { it.first.id })
            }
            .sortedBy { it.startDate }
    }

    /** Suggest ending an open trip when no matching foreign transaction has been seen for [endAfterDays]. */
    fun suggestEnd(trip: Trip, transactions: List<Transaction>, today: LocalDate): TripEndSuggestion? {
        if (trip.end != null) return null
        val last = transactions.filter { !it.isDeleted && !ignore(it) }
            .filter { t -> t.tripId == trip.id || signal(t)?.let { matchesTrip(trip, it.first, it.second) } == true }
            .map { date(it) }.filter { !it.isBefore(trip.start) }.maxOrNull() ?: trip.start
        val gap = ChronoUnit.DAYS.between(last, today)
        return if (gap >= endAfterDays) TripEndSuggestion(trip.id, last, gap) else null
    }
}

data class TripSummary(
    val tripId: Id,
    val home: CurrencyCode,
    val days: Long,
    val total: Money,
    val byCategory: Map<Id?, Money>,
    val perDayAverage: Money,
    val fxFeesPaid: Money,
    val cash: Money,
    val card: Money,
    val txnCount: Int,
    /** Transactions skipped because no home-currency amount could be determined. */
    val unconverted: List<Id>,
) {
    companion object {
        /**
         * Summarise a trip. Includes transactions with tripId == trip.id, plus untagged ones within the
         * trip dates in the trip currency. Cash = transactions on [cashAccountIds] or of type ATM_WITHDRAWAL
         * is excluded (withdrawals are transfers into cash) and [cashSpends] (from [CashPoolLedger]) are added.
         * [toHome] converts amounts that have no home-currency representation (optional).
         */
        fun compute(
            trip: Trip,
            transactions: List<Transaction>,
            home: CurrencyCode,
            zone: ZoneId = ZoneId.systemDefault(),
            cashAccountIds: Set<Id> = emptySet(),
            cashSpends: List<Pair<CashSpend, Id?>> = emptyList(),
            today: LocalDate = LocalDate.now(zone),
            toHome: (Money, LocalDate) -> Money? = { _, _ -> null },
        ): TripSummary {
            fun d(t: Transaction) = t.occurredAt.atZone(zone).toLocalDate()
            fun inRange(x: LocalDate) = !x.isBefore(trip.start) && (trip.end == null || !x.isAfter(trip.end))
            val txns = transactions.filter { t ->
                !t.isDeleted && t.type != TxnType.TRANSFER && t.type != TxnType.ATM_WITHDRAWAL &&
                    (t.tripId == trip.id || (t.tripId == null && inRange(d(t)) && t.original.currency == trip.currency))
            }
            var total = Money.zero(home); var cash = Money.zero(home); var card = Money.zero(home)
            var fees = Money.zero(home)
            val byCat = LinkedHashMap<Id?, Money>()
            val skipped = ArrayList<Id>()
            for (t in txns) {
                val amt = homeAmount(t, home) ?: toHome(t.account, d(t))?.takeIf { it.currency == home }
                if (amt == null) { skipped += t.id; continue }
                val signed = if (t.type == TxnType.REFUND || t.type == TxnType.INCOME) -amt.abs() else amt.abs()
                total += signed
                byCat[t.categoryId] = (byCat[t.categoryId] ?: Money.zero(home)) + signed
                if (t.accountId != null && t.accountId in cashAccountIds) cash += signed else card += signed
                t.fxFee?.let { f ->
                    val hf = if (f.currency == home) f else toHome(f, d(t))?.takeIf { it.currency == home }
                    if (hf != null) fees += hf
                }
            }
            for ((s, cat) in cashSpends) {
                if (!inRange(s.date)) continue
                total += s.homeCost; cash += s.homeCost
                byCat[cat] = (byCat[cat] ?: Money.zero(home)) + s.homeCost
            }
            val end = trip.end ?: minOf(today, maxOf(trip.start, (txns.map { d(it) } + cashSpends.map { it.first.date }).maxOrNull() ?: trip.start))
            val days = ChronoUnit.DAYS.between(trip.start, end) + 1
            val perDay = Money.of(total.toDecimal().divide(BigDecimal(days), home.exponent + 2, RoundingMode.HALF_EVEN), home)
            return TripSummary(trip.id, home, days, total, byCat, perDay, fees, cash, card, txns.size, skipped)
        }

        private fun homeAmount(t: Transaction, home: CurrencyCode): Money? = when (home) {
            t.base.currency -> t.base
            t.account.currency -> t.account
            t.original.currency -> t.original
            else -> null
        }
    }
}

/** Realised FX result of a cross-currency transfer between own accounts. */
data class TransferFxResult(
    val costHome: Money,
    val proceedsHome: Money,
    /** proceeds - cost; negative = loss (spread/fees or adverse move since acquisition). */
    val realisedGain: Money,
    /** to / from. */
    val effectiveRate: BigDecimal,
    /** Market from->to rate on the date, if available. */
    val marketRate: BigDecimal?,
    /** (market - effective) / market * 100: the conversion spread paid, scale 2. */
    val spreadPct: BigDecimal?,
)

/**
 * Computes realised FX gain/loss on transfers between the user's own accounts in different currencies,
 * e.g. moving USD 1,000 from a USD account to INR account receiving ₹91,500.
 */
class TransferFxCalculator(private val rates: RateStore, private val home: CurrencyCode) {
    /**
     * @param fromCostRate optional carrying cost of the source currency (home per 1 unit), e.g. the average
     *        rate at which the USD balance was acquired. Without it the market rate on [date] is used as cost,
     *        so the gain equals the negative spread.
     */
    fun realised(from: Money, to: Money, date: LocalDate, fromCostRate: BigDecimal? = null): TransferFxResult {
        require(from.currency != to.currency) { "Not a cross-currency transfer" }
        val market = rates.lookup(from.currency, to.currency, date)?.rate
        val cost = when {
            from.currency == home -> from.abs()
            fromCostRate != null -> Converter.applyRate(from.abs(), fromCostRate, home)
            else -> Converter.applyRate(from.abs(), rateOrThrow(from.currency, date), home)
        }
        val proceeds = if (to.currency == home) to.abs() else Converter.applyRate(to.abs(), rateOrThrow(to.currency, date), home)
        val eff = to.toDecimal().abs().divide(from.toDecimal().abs(), 8, RoundingMode.HALF_EVEN)
        val spread = market?.let {
            it.subtract(eff).multiply(BigDecimal(100)).divide(it, 2, RoundingMode.HALF_EVEN)
        }
        return TransferFxResult(cost, proceeds, proceeds - cost, eff, market, spread)
    }

    private fun rateOrThrow(c: CurrencyCode, date: LocalDate): BigDecimal =
        rates.lookup(c, home, date)?.rate ?: throw RateUnavailableException(c, home, date)
}

