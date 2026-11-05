package app.ledgerly.engine.fallback

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import app.ledgerly.core.model.TxnType
import app.ledgerly.data.db.AuditEventType
import app.ledgerly.data.db.Cadence
import app.ledgerly.engine.AuditChange
import app.ledgerly.engine.AuditService
import app.ledgerly.engine.BudgetService
import app.ledgerly.engine.CashGapPrompt
import app.ledgerly.engine.DedupDecision
import app.ledgerly.engine.Deduper
import app.ledgerly.engine.FxService
import app.ledgerly.engine.InsightsService
import app.ledgerly.engine.RateTable
import app.ledgerly.engine.RecurringCandidate
import app.ledgerly.engine.SafeToSpend
import app.ledgerly.engine.SafeToSpendInput
import app.ledgerly.engine.TripSuggestion
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** TODO(core-dedup): replace with the core:dedup merge engine. */
class FallbackDeduper : Deduper {
    override fun decide(candidate: Transaction, recent: List<Transaction>): DedupDecision {
        val newChannels = candidate.sourceChannels
        for (e in recent) {
            if (e.id == candidate.id || e.isDeleted) continue
            if (candidate.reference != null && candidate.reference == e.reference) {
                return DedupDecision.Merge(e.id, 0.99, "same reference ${e.reference}")
            }
        }
        val sameAmount = recent.filter { !it.isDeleted && it.original == candidate.original && it.type == candidate.type }
        for (e in sameAmount.sortedBy { abs(Duration.between(it.occurredAt, candidate.occurredAt).toMinutes()) }) {
            val minutes = abs(Duration.between(e.occurredAt, candidate.occurredAt).toMinutes())
            val last4Compatible = e.accountLast4 == null || candidate.accountLast4 == null || e.accountLast4 == candidate.accountLast4
            val otherChannel = newChannels.none { it in e.sourceChannels }
            if (minutes <= 15 && last4Compatible && otherChannel) return DedupDecision.Merge(e.id, 0.9, "same amount on another channel within ${minutes}m")
            if (minutes <= 2 && last4Compatible) return DedupDecision.PossibleDuplicate(e.id, 0.6, "same amount within ${minutes}m")
            if (minutes <= 48 * 60 && otherChannel && merchantsSimilar(e.merchant ?: e.merchantRaw, candidate.merchant ?: candidate.merchantRaw)) {
                return DedupDecision.PossibleDuplicate(e.id, 0.55, "same amount and merchant within 48h")
            }
        }
        return DedupDecision.New
    }

    override fun merge(existing: Transaction, incoming: Transaction): Transaction = existing.copy(
        accountId = existing.accountId ?: incoming.accountId,
        // Prefer the richer multi-currency data (a message that shows both currencies).
        account = if (existing.account.currency == existing.original.currency && incoming.account.currency != incoming.original.currency) incoming.account else existing.account,
        merchant = existing.merchant ?: incoming.merchant,
        merchantRaw = existing.merchantRaw ?: incoming.merchantRaw,
        reference = existing.reference ?: incoming.reference,
        accountLast4 = existing.accountLast4 ?: incoming.accountLast4,
        sourceEventIds = (existing.sourceEventIds + incoming.sourceEventIds).distinct(),
        sourceChannels = existing.sourceChannels + incoming.sourceChannels,
    )

    private fun merchantsSimilar(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = a.lowercase().filter { it.isLetterOrDigit() }
        val y = b.lowercase().filter { it.isLetterOrDigit() }
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }
}

/** TODO(core-fx): replace with core:fx MultiCurrencyEnricher + FX fee exposer. */
class FallbackFxService : FxService {
    override fun enrich(txn: Transaction, accountCurrency: CurrencyCode, baseCurrency: CurrencyCode, rates: RateTable): Transaction {
        var t = txn
        val original = txn.original
        // 1) Account amount.
        if (t.account.currency != accountCurrency && original.currency != accountCurrency) {
            // Message only showed the foreign amount: estimate from the market rate.
            val q = rates.get(original.currency, accountCurrency)
            if (q != null) {
                t = t.copy(account = Money.of(original.toDecimal().multiply(q.rate), accountCurrency), marketRate = q.rate, rateSource = "${q.source}-estimate", rateDate = q.date)
            }
        } else if (t.account.currency == accountCurrency && original.currency != accountCurrency) {
            // Both amounts known: applied rate vs market rate exposes the hidden FX fee.
            val applied = t.account.toDecimal().divide(original.toDecimal(), MathContext.DECIMAL64)
            val q = rates.get(original.currency, accountCurrency)
            t = t.copy(fxRateApplied = applied)
            if (q != null) {
                val atMarket = Money.of(original.toDecimal().multiply(q.rate), accountCurrency)
                val fee = t.account - atMarket
                t = t.copy(marketRate = q.rate, rateSource = q.source, rateDate = q.date, fxFee = if (fee.isPositive) fee else null)
            }
        }
        // 2) Base amount.
        val acc = t.account
        t = if (acc.currency == baseCurrency) {
            t.copy(base = acc)
        } else if (original.currency == baseCurrency) {
            t.copy(base = original)
        } else {
            val q = rates.get(acc.currency, baseCurrency)
            if (q != null) t.copy(base = Money.of(acc.toDecimal().multiply(q.rate), baseCurrency), rateDate = t.rateDate ?: q.date, rateSource = t.rateSource ?: q.source)
            else t.copy(base = acc) // no rate yet: base stays in account currency; re-enriched after the next rate sync
        }
        return t
    }
}

/** TODO(core-audit): replace with core:audit TransactionDiffer. */
class FallbackAuditService : AuditService {
    override fun diff(before: Transaction?, after: Transaction): List<AuditChange> {
        if (before == null) return listOf(AuditChange(AuditEventType.CREATED, null, null, "${after.original} ${after.merchant.orEmpty()}".trim()))
        val out = mutableListOf<AuditChange>()
        fun field(name: String, old: Any?, new: Any?, type: AuditEventType = AuditEventType.UPDATED) {
            if (old != new) out += AuditChange(type, name, old?.toString(), new?.toString())
        }
        field("categoryId", before.categoryId, after.categoryId, AuditEventType.CATEGORISED)
        field("type", before.type, after.type)
        field("status", before.status, after.status)
        field("occurredAt", before.occurredAt, after.occurredAt)
        field("original", before.original, after.original)
        field("account", before.account, after.account)
        field("base", before.base, after.base, if (before.original == after.original) AuditEventType.FX_ENRICHED else AuditEventType.UPDATED)
        field("fxFee", before.fxFee, after.fxFee, AuditEventType.FX_ENRICHED)
        field("accountId", before.accountId, after.accountId)
        field("merchant", before.merchant, after.merchant)
        field("note", before.note, after.note)
        field("tags", before.tags.sorted().joinToString(","), after.tags.sorted().joinToString(","))
        field("tripId", before.tripId, after.tripId)
        field("sourceChannels", before.sourceChannels.sorted().joinToString("+"), after.sourceChannels.sorted().joinToString("+"), AuditEventType.MERGED)
        if (before.splits != after.splits) out += AuditChange(AuditEventType.SPLIT, "splits", before.splits.size.toString(), after.splits.joinToString { "${it.categoryId}=${it.amount}" })
        if (before.isReviewed != after.isReviewed && after.isReviewed) out += AuditChange(AuditEventType.REVIEWED, "isReviewed", "false", "true")
        if (before.deletedAt == null && after.deletedAt != null) out += AuditChange(AuditEventType.DELETED, null, null, null)
        if (before.deletedAt != null && after.deletedAt == null) out += AuditChange(AuditEventType.RESTORED, null, null, null)
        return out
    }
}

/** TODO(core-budget): replace with core:budget safe-to-spend + alerting. */
class FallbackBudgetService : BudgetService {
    override fun safeToSpend(input: SafeToSpendInput): SafeToSpend? {
        val total = input.budgetTotal ?: return null
        val ccy = total.currency
        if (input.spentBeforeToday.currency != ccy || input.upcomingCommitted.currency != ccy || input.spentToday.currency != ccy) return null
        val daysLeft = ChronoUnit.DAYS.between(input.today, input.periodEnd).toInt().coerceAtLeast(1)
        val remaining = total - input.spentBeforeToday - input.upcomingCommitted
        val dailyAllowance = Money(Math.floorDiv(remaining.minor, daysLeft.toLong()), ccy)
        return SafeToSpend(today = dailyAllowance - input.spentToday, remainingInPeriod = remaining - input.spentToday, daysLeft = daysLeft)
    }

    override fun crossedThreshold(limit: Money, before: Money, after: Money): Int? {
        if (!limit.isPositive || limit.currency != before.currency || limit.currency != after.currency) return null
        return listOf(100, 80).firstOrNull { pct ->
            val mark = BigDecimal.valueOf(limit.minor).multiply(BigDecimal(pct)).divide(BigDecimal(100), 0, RoundingMode.CEILING).toLong()
            before.minor < mark && after.minor >= mark
        }
    }
}

/** TODO(core-insights): replace with core:insights RecurringDetector / cash-gap / trip detection. */
class FallbackInsightsService : InsightsService {

    override fun detectRecurring(history: List<Transaction>, today: LocalDate, zone: ZoneId): List<RecurringCandidate> {
        val expenses = history.filter { !it.isDeleted && it.type == TxnType.EXPENSE && (it.merchant ?: it.merchantRaw) != null }
        return expenses.groupBy { (it.merchant ?: it.merchantRaw)!!.lowercase().trim() }.mapNotNull { (_, txns) ->
            if (txns.size < 3) return@mapNotNull null
            val sorted = txns.sortedBy { it.occurredAt }
            val dates = sorted.map { it.occurredAt.atZone(zone).toLocalDate() }
            val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.filter { it > 0 }
            if (gaps.size < 2) return@mapNotNull null
            val median = gaps.sorted()[gaps.size / 2]
            val cadence = when (median) {
                in 6..8 -> Cadence.WEEKLY
                in 26..35 -> Cadence.MONTHLY
                in 85..97 -> Cadence.QUARTERLY
                in 355..375 -> Cadence.YEARLY
                else -> return@mapNotNull null
            }
            if (gaps.any { abs(it - median) > maxOf(3, median / 5) }) return@mapNotNull null
            val amounts = sorted.map { it.base.minor }.sorted()
            val medianAmount = amounts[amounts.size / 2]
            if (medianAmount <= 0 || amounts.any { abs(it - medianAmount) > medianAmount * 15 / 100 }) return@mapNotNull null
            val last = sorted.last()
            val stable = amounts.all { abs(it - medianAmount) <= medianAmount * 2 / 100 }
            RecurringCandidate(
                name = last.merchant ?: last.merchantRaw!!,
                merchant = last.merchant ?: last.merchantRaw,
                categoryId = last.categoryId,
                amount = Money(medianAmount, last.base.currency),
                cadence = cadence,
                intervalDays = median,
                lastSeen = dates.last(),
                nextDue = dates.last().plusDays(median.toLong()).let { if (it.isBefore(today)) today else it },
                occurrences = sorted.size,
                isSubscription = stable || last.categoryId?.startsWith("entertainment.streaming") == true,
            )
        }.sortedBy { it.nextDue }
    }

    override fun cashGap(history: List<Transaction>, cashAccountId: String?, today: LocalDate, zone: ZoneId): CashGapPrompt? {
        val withdrawal = history.filter { !it.isDeleted && it.type == TxnType.ATM_WITHDRAWAL }
            .maxByOrNull { it.occurredAt } ?: return null
        val wDate = withdrawal.occurredAt.atZone(zone).toLocalDate()
        val age = ChronoUnit.DAYS.between(wDate, today)
        if (age < 3 || age > 21) return null
        val cashSpent = history.filter {
            !it.isDeleted && it.type == TxnType.EXPENSE && it.occurredAt >= withdrawal.occurredAt &&
                (it.accountId == cashAccountId || "cash" in it.tags) && it.base.currency == withdrawal.base.currency
        }.sumOf { it.base.minor }
        val unaccounted = withdrawal.base.minor - cashSpent
        if (unaccounted <= withdrawal.base.minor * 30 / 100) return null
        return CashGapPrompt(withdrawal.id, withdrawal.base, Money(unaccounted, withdrawal.base.currency))
    }

    override fun suggestTrip(txn: Transaction, baseCurrency: CurrencyCode, activeTrips: List<Trip>, priorCountInCurrency: Int, zone: ZoneId): TripSuggestion? {
        val ccy = txn.original.currency
        if (ccy == baseCurrency || txn.type != TxnType.EXPENSE) return null
        if (activeTrips.any { it.currency == ccy }) return null
        if (priorCountInCurrency > 0) return null
        val date = txn.occurredAt.atZone(zone).toLocalDate()
        val month = date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        return TripSuggestion(ccy, date, "${ccy.code} trip, $month ${date.year}")
    }
}
