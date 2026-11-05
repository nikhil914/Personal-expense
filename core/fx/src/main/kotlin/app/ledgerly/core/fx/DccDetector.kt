package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import java.time.LocalDate
import java.time.ZoneId

data class DccAlert(val txnId: Id, val tripId: Id, val confidence: Double, val message: String)

/**
 * Flags likely Dynamic Currency Conversion: during an active trip abroad, a transaction was charged in the
 * home currency instead of the local one. Confidence rises when the merchant's country matches the trip.
 */
class DccDetector(
    private val homeCurrency: CurrencyCode,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Optional merchant-country resolver (ISO-3166 alpha-2), e.g. from the parser. */
    private val merchantCountry: (Transaction) -> String? = { null },
) {
    fun detect(txn: Transaction, trips: List<Trip>): DccAlert? {
        if (txn.isDeleted) return null
        if (txn.original.currency != homeCurrency) return null
        val date = txn.occurredAt.atZone(zone).toLocalDate()
        val trip = trips.firstOrNull { it.currency != homeCurrency && covers(it, date) } ?: return null
        val country = merchantCountry(txn)
        val tripCountry = trip.countryCode ?: CurrencyCountries.countryFor(trip.currency)
        var confidence = 0.6
        if (country != null && tripCountry != null) {
            if (country.equals(tripCountry, ignoreCase = true)) confidence = 0.9
            else if (country.equals(CurrencyCountries.countryFor(homeCurrency), ignoreCase = true)) return null
        }
        val where = txn.merchant ?: txn.merchantRaw ?: "a merchant"
        val countryName = tripCountry?.let { CurrencyCountries.countryName(it) } ?: trip.name
        val msg = "${FxFormat.full(txn.original.abs())} at $where in $countryName was charged in $homeCurrency — " +
            "this looks like Dynamic Currency Conversion. Next time choose to pay in ${trip.currency}; " +
            "DCC rates are usually 3–8% worse than your card's rate."
        return DccAlert(txn.id, trip.id, confidence, msg)
    }

    fun detectAll(txns: List<Transaction>, trips: List<Trip>): List<DccAlert> = txns.mapNotNull { detect(it, trips) }

    private fun covers(t: Trip, d: LocalDate) = !d.isBefore(t.start) && (t.end == null || !d.isAfter(t.end))
}
