package app.ledgerly.core.insights

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import app.ledgerly.core.model.TxnType
import java.time.Clock
import java.time.Duration

enum class AnomalyType { LARGE_VS_MERCHANT, LARGE_VS_CATEGORY, DUPLICATE_CHARGE, NEW_MERCHANT_ABROAD, ODD_HOUR, CARD_NOT_PRESENT, CARD_TESTING }

enum class Severity { LOW, MEDIUM, HIGH }

data class Anomaly(val transaction: Transaction, val type: AnomalyType, val severity: Severity, val reason: String, val relatedIds: List<Id> = emptyList())

data class AnomalyConfig(
    /** Flag when amount > multiplier x median of history. */
    val largeMultiplier: Double = 3.0,
    /** ...and z-score above this. */
    val zThreshold: Double = 3.0,
    val minHistory: Int = 4,
    val duplicateWindow: Duration = Duration.ofMinutes(10),
    /** Odd hours [start, end) local time; set start == end to disable. */
    val oddHourStart: Int = 1,
    val oddHourEnd: Int = 5,
    /** Home currency; transactions in other original currencies count as "abroad". Null = dominant base currency. */
    val homeCurrency: CurrencyCode? = null,
    /** Home country ISO code; trips elsewhere count as abroad. */
    val homeCountry: String? = null,
    val onlineKeywords: Set<String> = setOf("www", ".com", "online", "ecom", "e-com", "paypal", "stripe", "razorpay", "payu",
        "billdesk", "ccavenue", "adyen", "checkout", "web", "app store", "google play", "*"),
    /** Tiny charge threshold (minor units of base) for card-testing detection. */
    val testChargeMaxMinor: Long = 200,
    val cardTestingWindow: Duration = Duration.ofHours(2),
)

/** Unusual / potentially fraudulent transaction flags. */
class AnomalyDetector(
    private val clock: Clock = Clock.systemDefaultZone(),
    accounts: Collection<Account> = emptyList(),
    trips: Collection<Trip> = emptyList(),
    private val config: AnomalyConfig = AnomalyConfig(),
) {
    private val zone get() = clock.zone
    private val accountsById = accounts.associateBy { it.id }
    private val tripsById = trips.associateBy { it.id }

    /** Scans [candidates] (default: all) against the whole [history]. */
    fun scan(history: Collection<Transaction>, candidates: Collection<Transaction> = history): List<Anomaly> {
        val home = config.homeCurrency ?: dominantBaseCurrency(history)
        val sorted = history.filter { it.isActive() }.sortedBy { it.occurredAt }
        return candidates.filter { it.isActive() && it.flow() == Flow.OUT }.flatMap { check(it, sorted, home) }
    }

    fun check(t: Transaction, history: List<Transaction>, home: CurrencyCode = config.homeCurrency ?: dominantBaseCurrency(history)): List<Anomaly> {
        val out = mutableListOf<Anomaly>()
        val prior = history.filter { it.id != t.id && it.occurredAt.isBefore(t.occurredAt) && it.flow() == Flow.OUT && it.base.currency == t.base.currency }
        val key = t.merchantKey()
        val amt = t.baseAbs.minor.toDouble()

        // Large vs merchant, else vs category
        val merchantHist = if (key != null) prior.filter { it.merchantKey() == key }.map { it.baseAbs.minor.toDouble() } else emptyList()
        val catHist = if (t.categoryId != null) prior.filter { it.categoryId == t.categoryId }.map { it.baseAbs.minor.toDouble() } else emptyList()
        val largeM = isLarge(amt, merchantHist)
        if (largeM != null) {
            out += Anomaly(t, AnomalyType.LARGE_VS_MERCHANT, if (largeM > 5) Severity.HIGH else Severity.MEDIUM,
                "${MoneyFormat.format(t.baseAbs)} at ${t.merchant ?: key} is ${"%.1f".format(largeM)}x your usual there.")
        } else {
            isLarge(amt, catHist)?.let { r ->
                out += Anomaly(t, AnomalyType.LARGE_VS_CATEGORY, if (r > 5) Severity.HIGH else Severity.MEDIUM,
                    "${MoneyFormat.format(t.baseAbs)} is ${"%.1f".format(r)}x your typical spend in this category.")
            }
        }

        // Duplicate: same merchant, same amount within window
        if (key != null) {
            val dup = history.filter {
                it.id != t.id && it.flow() == Flow.OUT && it.merchantKey() == key && it.original == t.original &&
                    it.occurredAt.isBefore(t.occurredAt.plusNanos(1)) &&
                    Duration.between(it.occurredAt, t.occurredAt).abs() <= config.duplicateWindow &&
                    (it.occurredAt != t.occurredAt || it.id < t.id)
            }
            if (dup.isNotEmpty()) {
                out += Anomaly(t, AnomalyType.DUPLICATE_CHARGE, Severity.HIGH,
                    "Possible duplicate: ${MoneyFormat.format(t.original.abs())} at ${t.merchant ?: key} charged twice within ${config.duplicateWindow.toMinutes()} minutes.",
                    dup.map { it.id })
            }
        }

        // Abroad
        val trip = t.tripId?.let { tripsById[it] }
        val abroad = t.original.currency != home ||
            (trip?.countryCode != null && config.homeCountry != null && !trip.countryCode.equals(config.homeCountry, true))
        val newMerchant = key != null && prior.none { it.merchantKey() == key }
        if (abroad && newMerchant) {
            out += Anomaly(t, AnomalyType.NEW_MERCHANT_ABROAD, Severity.MEDIUM,
                "First payment to ${t.merchant ?: key} in ${t.original.currency}${trip?.let { " during ${it.name}" } ?: ""}. Was this you?")
        }

        // Odd hour
        if (config.oddHourStart != config.oddHourEnd) {
            val h = t.occurredAt.atZone(zone).hour
            val odd = if (config.oddHourStart < config.oddHourEnd) h in config.oddHourStart until config.oddHourEnd
            else h >= config.oddHourStart || h < config.oddHourEnd
            if (odd && t.type == TxnType.EXPENSE || odd && t.type == TxnType.ATM_WITHDRAWAL) {
                out += Anomaly(t, AnomalyType.ODD_HOUR, if (t.type == TxnType.ATM_WITHDRAWAL) Severity.MEDIUM else Severity.LOW,
                    "${MoneyFormat.format(t.baseAbs)} at ${"%02d:%02d".format(h, t.occurredAt.atZone(zone).minute)}, an unusual time for you.")
            }
        }

        // Card-not-present heuristics
        val isCard = t.accountId?.let { accountsById[it]?.type } in setOf(AccountType.CREDIT_CARD, AccountType.FOREX_CARD, AccountType.PREPAID) ||
            (t.accountLast4 != null && t.accountId == null)
        val raw = ((t.merchantRaw ?: "") + " " + (t.merchant ?: "")).lowercase()
        val online = config.onlineKeywords.any { raw.contains(it) }
        val bigVsUsual = prior.size >= config.minHistory && amt >= median(prior.map { it.baseAbs.minor.toDouble() }) * config.largeMultiplier
        if (isCard && online && (abroad || (newMerchant && bigVsUsual) || largeM != null)) {
            out += Anomaly(t, AnomalyType.CARD_NOT_PRESENT, if (abroad) Severity.HIGH else Severity.MEDIUM,
                "Online card payment to ${if (newMerchant) "a new merchant " else ""}${t.merchant ?: key}${if (abroad) " in ${t.original.currency}" else ""}. Confirm you made it.")
        }

        // Card testing: tiny charge followed by a bigger one at the same merchant shortly after
        if (key != null && t.baseAbs.minor > config.testChargeMaxMinor) {
            val tiny = prior.filter {
                it.merchantKey() == key && it.baseAbs.minor <= config.testChargeMaxMinor &&
                    Duration.between(it.occurredAt, t.occurredAt) <= config.cardTestingWindow
            }
            if (tiny.isNotEmpty()) {
                out += Anomaly(t, AnomalyType.CARD_TESTING, Severity.HIGH,
                    "A tiny test charge at ${t.merchant ?: key} was followed by ${MoneyFormat.format(t.baseAbs)}. This is a common card-fraud pattern.",
                    tiny.map { it.id })
            }
        }
        return out
    }

    /** Ratio to median if both multiplier and z-score thresholds are exceeded. */
    private fun isLarge(amt: Double, hist: List<Double>): Double? {
        if (hist.size < config.minHistory) return null
        val med = median(hist)
        if (med <= 0) return null
        val ratio = amt / med
        if (ratio < config.largeMultiplier) return null
        val sd = stddev(hist)
        val z = if (sd == 0.0) Double.POSITIVE_INFINITY else (amt - mean(hist)) / sd
        return if (z >= config.zThreshold) ratio else null
    }
}
