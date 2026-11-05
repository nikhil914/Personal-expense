package app.ledgerly.core.insights

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class SubscriptionConfig(
    /** Alert when a new charge exceeds the previous one by more than this percentage. */
    val priceIncreaseThresholdPct: Double = 5.0,
    /** Only alert on price increases whose charge happened within this many days. */
    val priceIncreaseLookbackDays: Long = 45,
    /** "Renewing in N days" lead time. */
    val renewalLeadDays: Long = 3,
    /** A charge at or below this many minor units (of base currency) is a possible free-trial signup. */
    val trialMaxMinor: Long = 200,
    /** Trial lengths assumed when the service has no known trial length. */
    val assumedTrialDays: List<Int> = listOf(7, 14, 30),
    /** Alert when a trial ends within this many days. */
    val trialAlertLeadDays: Long = 5,
    /** Nudge when a subscription hasn't been used for this many days (requires usage data). Null disables. */
    val unusedAfterDays: Long? = 45,
    /** Unknown (non-catalogue) recurring outflows count as subscriptions only when amount-stable and at least this confident. */
    val unknownMinConfidence: Double = 0.7,
    /** Tags that mark a transaction as a subscription even if not in the catalogue. */
    val subscriptionTags: Set<String> = setOf("subscription", "subscriptions"),
)

data class Subscription(
    val name: String,
    val merchantKey: String,
    val service: KnownService?,
    val series: RecurringSeries,
    val currentAmount: Money,
    val annualCost: Money,
    val nextRenewal: LocalDate,
    val cancelUrl: String?,
) {
    val periodicity: Periodicity get() = series.periodicity
}

sealed class SubscriptionAlert {
    abstract val message: String

    data class PriceIncrease(
        val subscription: Subscription, val oldAmount: Money, val newAmount: Money, val increasePct: Double, val chargedOn: LocalDate,
        override val message: String,
    ) : SubscriptionAlert()

    data class TrialEnding(
        val merchant: String, val service: KnownService?, val startedOn: LocalDate, val expectedEnd: LocalDate, val daysLeft: Long,
        override val message: String,
    ) : SubscriptionAlert()

    data class RenewingSoon(
        val subscription: Subscription, val renewsOn: LocalDate, val daysUntil: Long, override val message: String,
    ) : SubscriptionAlert()

    data class Unused(
        val subscription: Subscription, val lastUsed: LocalDate, val daysUnused: Long, override val message: String,
    ) : SubscriptionAlert()
}

/** Lists subscriptions and raises alerts (price increase, trial ending, renewal, unused). */
class SubscriptionManager(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val config: SubscriptionConfig = SubscriptionConfig(),
    private val detector: RecurringDetector = RecurringDetector(clock),
) {
    private val zone get() = clock.zone

    fun subscriptions(transactions: Collection<Transaction>, series: List<RecurringSeries>? = null): List<Subscription> {
        val all = series ?: detector.detect(transactions)
        return all.asSequence()
            .filter { it.flow == Flow.OUT && it.active }
            .mapNotNull { s ->
                val service = KnownServices.match(s.merchant) ?: KnownServices.match(s.key)
                val tagged = s.transactions.any { t -> t.tags.any { it.lowercase() in config.subscriptionTags } }
                val qualifies = service != null || tagged ||
                    (s.amountStable && s.confidence >= config.unknownMinConfidence && s.periodicity != Periodicity.WEEKLY)
                if (!qualifies) return@mapNotNull null
                val amount = s.lastAmount
                Subscription(
                    name = service?.name ?: s.merchant,
                    merchantKey = s.key,
                    service = service,
                    series = s,
                    currentAmount = amount,
                    annualCost = amount.times(BigDecimal.valueOf(s.periodicity.perYear)),
                    nextRenewal = s.nextExpectedDate,
                    cancelUrl = service?.cancelUrl,
                )
            }
            .sortedByDescending { it.annualCost.minor }
            .toList()
    }

    fun totalAnnualCost(subscriptions: List<Subscription>, currency: CurrencyCode): Money =
        Money(subscriptions.filter { it.annualCost.currency == currency }.sumOf { it.annualCost.minor }, currency)

    fun totalMonthlyCost(subscriptions: List<Subscription>, currency: CurrencyCode): Money =
        moneyOfMinor(totalAnnualCost(subscriptions, currency).minor / 12.0, currency)

    /**
     * @param lastUsed last-used date per subscription, keyed by [KnownService.id] or [Subscription.merchantKey]
     *   (e.g. from Android UsageStats). Subscriptions without usage data are never nudged.
     */
    fun alerts(transactions: Collection<Transaction>, lastUsed: Map<String, LocalDate> = emptyMap()): List<SubscriptionAlert> {
        val today = LocalDate.now(clock)
        val subs = subscriptions(transactions)
        val out = mutableListOf<SubscriptionAlert>()
        for (sub in subs) {
            priceIncrease(sub, today)?.let { out += it }
            val days = ChronoUnit.DAYS.between(today, sub.nextRenewal)
            if (days in 0..config.renewalLeadDays) {
                val whenStr = when (days) { 0L -> "today"; 1L -> "tomorrow"; else -> "in $days days" }
                out += SubscriptionAlert.RenewingSoon(
                    sub, sub.nextRenewal, days,
                    "${sub.name} renews $whenStr (${sub.nextRenewal.shortLabel()}) for about ${MoneyFormat.format(sub.currentAmount)}.",
                )
            }
            val unusedAfter = config.unusedAfterDays
            val used = lastUsed[sub.service?.id ?: ""] ?: lastUsed[sub.merchantKey]
            if (unusedAfter != null && used != null) {
                val idle = ChronoUnit.DAYS.between(used, today)
                if (idle >= unusedAfter) {
                    out += SubscriptionAlert.Unused(
                        sub, used, idle,
                        "You haven't used ${sub.name} in $idle days but pay ${MoneyFormat.format(sub.annualCost)}/year. Cancel?",
                    )
                }
            }
        }
        out += trialAlerts(transactions, subs, today)
        return out
    }

    private fun priceIncrease(sub: Subscription, today: LocalDate): SubscriptionAlert.PriceIncrease? {
        val txns = sub.series.transactions
        if (txns.size < 2) return null
        val last = txns[txns.size - 1]
        val prev = txns[txns.size - 2]
        val lastDate = last.localDate(zone)
        if (ChronoUnit.DAYS.between(lastDate, today) > config.priceIncreaseLookbackDays) return null
        // Compare in original currency when possible to avoid FX noise.
        val (newM, oldM) = if (last.original.currency == prev.original.currency)
            last.original.abs() to prev.original.abs() else last.baseAbs to prev.baseAbs
        val p = pct(newM.minor, oldM.minor) ?: return null
        if (p <= config.priceIncreaseThresholdPct) return null
        return SubscriptionAlert.PriceIncrease(
            sub, oldM, newM, p, lastDate,
            "${sub.name} went up ${fmtPct(p)}: ${MoneyFormat.format(oldM)} to ${MoneyFormat.format(newM)} on ${lastDate.shortLabel()}.",
        )
    }

    private fun trialAlerts(transactions: Collection<Transaction>, subs: List<Subscription>, today: LocalDate): List<SubscriptionAlert> {
        val subKeys = subs.mapTo(mutableSetOf()) { it.merchantKey }
        val byMerchant = transactions.filter { it.isActive() && it.flow() == Flow.OUT }
            .groupBy { it.merchantKey() ?: "" }
            .filterKeys { it.isNotEmpty() && it !in subKeys }
        val out = mutableListOf<SubscriptionAlert>()
        for ((key, txns) in byMerchant) {
            val sorted = txns.sortedBy { it.occurredAt }
            val first = sorted.first()
            if (first.baseAbs.minor > config.trialMaxMinor) continue
            // "small/zero charge followed by none yet"
            if (sorted.size > 1) continue
            val service = KnownServices.match(first.merchant ?: first.merchantRaw)
            val taggedTrial = first.tags.any { it.equals("trial", true) || it.equals("free trial", true) }
            if (service == null && !taggedTrial) continue
            val start = first.localDate(zone)
            val lengths = service?.trialDays?.let { listOf(it) } ?: config.assumedTrialDays
            val end = lengths.map { start.plusDays(it.toLong()) }.firstOrNull { !it.isBefore(today) } ?: continue
            val daysLeft = ChronoUnit.DAYS.between(today, end)
            if (daysLeft > config.trialAlertLeadDays) continue
            val name = service?.name ?: first.merchant ?: MerchantNormalizer.display(key)
            out += SubscriptionAlert.TrialEnding(
                name, service, start, end, daysLeft,
                "Your $name trial (started ${start.shortLabel()}) likely ends ${if (daysLeft == 0L) "today" else "in $daysLeft days"}. " +
                    "Cancel before ${end.shortLabel()} if you don't want to be charged.",
            )
        }
        return out
    }
}
