package app.ledgerly.core.insights

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.Category
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Converts a base amount into another currency on a date (e.g. backed by core:fx). */
typealias MoneyConverter = (Money, CurrencyCode, LocalDate) -> Money

data class AnswerRow(
    val label: String,
    val amount: Money? = null,
    val count: Int = 0,
    val date: LocalDate? = null,
    val transactionId: Id? = null,
)

data class Answer(
    val text: String,
    /** Headline value (total, average, max ...) when applicable. */
    val value: Money?,
    val rows: List<AnswerRow> = emptyList(),
    val count: Int? = null,
    val query: Query? = null,
    /** Change in percent for COMPARE. */
    val changePct: Double? = null,
)

/** Executes a [Query] over transactions. */
class QueryExecutor(
    private val clock: Clock = Clock.systemDefaultZone(),
    categories: Collection<Category> = emptyList(),
    private val accounts: Collection<Account> = emptyList(),
    private val trips: Collection<Trip> = emptyList(),
    private val converter: MoneyConverter? = null,
) {
    private val zone get() = clock.zone
    private val categoriesById: Map<Id, Category> = categories.associateBy { it.id }
    private val categoryLookup: (Id) -> Category? = { categoriesById[it] }

    fun execute(query: Query, transactions: Collection<Transaction>): Answer {
        if (query.subscriptionsOnly && query.metric in setOf(Metric.LIST, Metric.SUM, Metric.COUNT)) return subscriptions(query, transactions)
        val out = query.outputCurrency ?: dominantBaseCurrency(transactions)
        val filtered = filter(query, transactions, query.range)
        val subject = subjectPhrase(query)
        val verb = if (query.direction == DirectionFilter.INCOME) "received" else "spent"
        val period = query.range?.label?.let { " $it" } ?: ""

        return when (query.metric) {
            Metric.SUM -> {
                if (query.groupBy != GroupBy.NONE) return grouped(query, filtered, out, verb, subject, period)
                val total = total(filtered, out)
                Answer(
                    "You $verb ${MoneyFormat.format(total)}$subject$period" +
                        (if (filtered.isEmpty()) "." else " across ${filtered.size} transaction${if (filtered.size == 1) "" else "s"}."),
                    total, filtered.sortedByDescending { it.occurredAt }.take(50).map { row(it, out) }, filtered.size, query,
                )
            }
            Metric.COUNT -> {
                val n = filtered.size
                val text = if (query.merchant != null && query.direction != DirectionFilter.INCOME)
                    "You paid ${display(query.merchant)} $n time${if (n == 1) "" else "s"}$period, totalling ${MoneyFormat.format(total(filtered, out))}."
                else "You had $n transaction${if (n == 1) "" else "s"}$subject$period."
                Answer(text, total(filtered, out), filtered.sortedByDescending { it.occurredAt }.take(50).map { row(it, out) }, n, query)
            }
            Metric.AVG -> average(query, filtered, out, verb, subject, period)
            Metric.MAX, Metric.MIN -> {
                val pick = if (query.metric == Metric.MAX) filtered.maxByOrNull { amount(it, out).minor } else filtered.minByOrNull { amount(it, out).minor }
                if (pick == null) Answer("No matching transactions$subject$period.", null, emptyList(), 0, query)
                else {
                    val word = if (query.metric == Metric.MAX) "biggest" else "smallest"
                    val kind = if (query.direction == DirectionFilter.INCOME) "credit" else "expense"
                    val a = amount(pick, out)
                    val where = pick.merchant?.let { " at $it" } ?: ""
                    Answer("Your $word $kind$subject$period was ${MoneyFormat.format(a)}$where on ${pick.localDate(zone).longLabel()}.",
                        a, listOf(row(pick, out)), 1, query)
                }
            }
            Metric.LIST -> {
                val limit = query.limit ?: 20
                val rows = filtered.sortedByDescending { it.occurredAt }.take(limit).map { row(it, out) }
                Answer(
                    if (filtered.isEmpty()) "No matching transactions$subject$period."
                    else "Here ${if (filtered.size == 1) "is" else "are"} ${if (filtered.size > limit) "the latest $limit of ${filtered.size}" else "${filtered.size}"} transaction${if (filtered.size == 1) "" else "s"}$subject$period.",
                    total(filtered, out), rows, filtered.size, query,
                )
            }
            Metric.TOP_MERCHANTS, Metric.TOP_CATEGORIES -> top(query, filtered, out, subject, period)
            Metric.COMPARE -> compare(query, transactions, out, subject, verb)
        }
    }

    // ---- filtering ----

    fun filter(query: Query, transactions: Collection<Transaction>, range: DateRange?): List<Transaction> {
        val catIds = query.category?.let { resolveCategoryIds(it) }
        val accIds = query.account?.let { resolveAccountIds(it) }
        val tripIds = query.trip?.let { t -> trips.filter { it.name.contains(t, ignoreCase = true) || t.contains(it.name, ignoreCase = true) }.map { it.id }.toSet() }
        val countryTrips = query.country?.let { c -> trips.filter { it.countryCode.equals(c, ignoreCase = true) }.map { it.id }.toSet() }
        val countryCurrency = query.country?.let { c -> runCatching { java.util.Currency.getInstance(Locale("", c.uppercase())).currencyCode }.getOrNull() }
        val merchantNorm = query.merchant?.let { MerchantNormalizer.normalize(it) ?: it.lowercase() }
        val today = LocalDate.now(clock)
        return transactions.filter { t ->
            if (!t.isActive()) return@filter false
            val dirOk = when (query.direction) {
                DirectionFilter.SPEND -> t.type == app.ledgerly.core.model.TxnType.EXPENSE
                DirectionFilter.INCOME -> t.type == app.ledgerly.core.model.TxnType.INCOME
                DirectionFilter.ALL -> true
            }
            if (!dirOk) return@filter false
            val d = t.localDate(zone)
            if (range != null && d !in range) return@filter false
            if (range == null && d.isAfter(today)) return@filter false
            if (merchantNorm != null) {
                val key = t.merchantKey() ?: return@filter false
                val raw = (t.merchant ?: "") + " " + (t.merchantRaw ?: "")
                if (!key.contains(merchantNorm) && !merchantNorm.contains(key) && !raw.lowercase().contains(query.merchant.lowercase())) return@filter false
            }
            if (catIds != null && t.categoryId !in catIds && t.splits.none { it.categoryId in catIds }) return@filter false
            if (query.tag != null && t.tags.none { it.equals(query.tag, ignoreCase = true) || it.equals("#" + query.tag, ignoreCase = true) }) return@filter false
            if (query.currency != null && t.original.currency != query.currency) return@filter false
            if (accIds != null && t.accountId !in accIds && (t.accountLast4 == null || t.accountLast4 != query.account)) return@filter false
            if (tripIds != null && t.tripId !in tripIds) return@filter false
            if (query.country != null) {
                val inTrip = countryTrips != null && t.tripId in countryTrips
                val inCurrency = countryCurrency != null && t.original.currency.code == countryCurrency && t.original.currency != t.base.currency
                if (!inTrip && !inCurrency) return@filter false
            }
            true
        }
    }

    fun resolveCategoryIds(term: String): Set<Id> {
        val direct = categoriesById.values.filter { QueryParser.categoryMatchesTerm(it.name, term) }.map { it.id }.toMutableSet()
        // Include descendants.
        var added = true
        while (added) {
            added = false
            for (c in categoriesById.values) if (c.parentId in direct && direct.add(c.id)) added = true
        }
        return direct
    }

    private fun resolveAccountIds(term: String): Set<Id> = accounts.filter { a ->
        a.name.contains(term, ignoreCase = true) || a.institution?.contains(term, ignoreCase = true) == true || term in a.last4
    }.map { it.id }.toSet()

    // ---- amounts ----

    fun amount(t: Transaction, out: CurrencyCode): Money {
        if (t.base.currency == out) return t.baseAbs
        if (t.original.currency == out) return t.original.abs()
        if (t.account.currency == out) return t.account.abs()
        val c = converter ?: return Money(0, out).also { unconverted++ }
        return c(t.baseAbs, out, t.localDate(zone)).abs()
    }

    private var unconverted = 0

    private fun total(ts: Collection<Transaction>, out: CurrencyCode): Money = Money(ts.sumOf { amount(it, out).minor }, out)

    private fun row(t: Transaction, out: CurrencyCode) = AnswerRow(
        label = t.merchant ?: t.merchantRaw ?: t.note ?: t.type.name.lowercase(),
        amount = amount(t, out), count = 1, date = t.localDate(zone), transactionId = t.id,
    )

    // ---- metrics ----

    private fun groupKey(t: Transaction, g: GroupBy): String = when (g) {
        GroupBy.MONTH -> YearMonth.from(t.localDate(zone)).toString()
        GroupBy.CATEGORY -> t.categoryId?.let { categoryLookup(it)?.name } ?: "Uncategorised"
        GroupBy.MERCHANT -> t.merchantKey()?.let { k -> t.merchant ?: MerchantNormalizer.display(k) } ?: "Unknown"
        GroupBy.NONE -> ""
    }

    private fun groupRows(ts: List<Transaction>, g: GroupBy, out: CurrencyCode): List<AnswerRow> {
        val groups = if (g == GroupBy.MERCHANT) ts.groupBy { it.merchantKey() ?: "unknown" } else ts.groupBy { groupKey(it, g) }
        return groups.map { (k, items) ->
            val label = when (g) {
                GroupBy.MERCHANT -> items.lastOrNull { it.merchant != null }?.merchant ?: MerchantNormalizer.display(k)
                GroupBy.MONTH -> YearMonth.parse(k).let { "${it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${it.year}" }
                else -> k
            }
            AnswerRow(label, total(items, out), items.size)
        }.let { rows -> if (g == GroupBy.MONTH) rows.sortedBy { r -> ts.firstOrNull { groupKey(it, g).let { k -> YearMonth.parse(k).let { ym -> "${ym.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${ym.year}" } } == r.label }?.occurredAt } else rows.sortedByDescending { it.amount!!.minor } }
    }

    private fun grouped(q: Query, ts: List<Transaction>, out: CurrencyCode, verb: String, subject: String, period: String): Answer {
        val rows = groupRows(ts, q.groupBy, out)
        val total = total(ts, out)
        val by = q.groupBy.name.lowercase()
        val body = rows.take(5).joinToString(", ") { "${it.label} ${MoneyFormat.format(it.amount!!)}" }
        return Answer("You $verb ${MoneyFormat.format(total)}$subject$period. By $by: $body.", total, rows, ts.size, q)
    }

    private fun average(q: Query, ts: List<Transaction>, out: CurrencyCode, verb: String, subject: String, period: String): Answer {
        if (q.groupBy == GroupBy.MONTH) {
            val today = LocalDate.now(clock)
            val start = q.range?.start ?: ts.minOfOrNull { it.localDate(zone) } ?: today
            val endRaw = q.range?.end ?: today
            val end = if (endRaw.isAfter(today)) today else endRaw
            val months = if (end.isBefore(start)) 1L
            else java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1
            val total = total(ts, out)
            val avg = moneyOfMinor(total.minor.toDouble() / months, out)
            return Answer(
                "On average you $verb ${MoneyFormat.format(avg)} per month$subject$period (${MoneyFormat.format(total)} over $months month${if (months == 1L) "" else "s"}).",
                avg, groupRows(ts, GroupBy.MONTH, out), ts.size, q,
            )
        }
        if (ts.isEmpty()) return Answer("No matching transactions$subject$period.", null, emptyList(), 0, q)
        val avg = moneyOfMinor(total(ts, out).minor.toDouble() / ts.size, out)
        return Answer("Your average transaction$subject$period was ${MoneyFormat.format(avg)} (${ts.size} transactions).", avg, emptyList(), ts.size, q)
    }

    private fun top(q: Query, ts: List<Transaction>, out: CurrencyCode, subject: String, period: String): Answer {
        val g = if (q.metric == Metric.TOP_CATEGORIES) GroupBy.CATEGORY else GroupBy.MERCHANT
        val rows = groupRows(ts, g, out).sortedByDescending { it.amount!!.minor }.take(q.limit ?: 5)
        if (rows.isEmpty()) return Answer("No matching transactions$subject$period.", null, emptyList(), 0, q)
        val noun = if (g == GroupBy.CATEGORY) "categories" else "merchants"
        val list = rows.mapIndexed { i, r -> "${i + 1}. ${r.label} (${MoneyFormat.format(r.amount!!)})" }.joinToString(", ")
        return Answer("Your top ${rows.size} $noun$subject$period: $list.", rows.first().amount, rows, ts.size, q)
    }

    private fun compare(q: Query, all: Collection<Transaction>, out: CurrencyCode, subject: String, verb: String): Answer {
        val r1 = q.range!!
        val r2 = q.compareRange!!
        val a = total(filter(q, all, r1), out)
        val b = total(filter(q, all, r2), out)
        val change = pct(a.minor, b.minor)
        val trend = when {
            change == null -> if (a.isPositive) "up from nothing" else "no change"
            change > 0.5 -> "${fmtPct(change)} more"
            change < -0.5 -> "${fmtPct(change)} less"
            else -> "about the same"
        }
        val text = "You $verb ${MoneyFormat.format(a)}$subject ${r1.label} vs ${MoneyFormat.format(b)} ${r2.label} ($trend)."
        return Answer(text, a, listOf(AnswerRow(r1.label, a), AnswerRow(r2.label, b)), null, q, change)
    }

    private fun subscriptions(q: Query, transactions: Collection<Transaction>): Answer {
        val subs = SubscriptionManager(clock).subscriptions(transactions)
        val out = q.outputCurrency ?: dominantBaseCurrency(transactions)
        if (subs.isEmpty()) return Answer("I couldn't find any active subscriptions.", null, emptyList(), 0, q)
        val annual = Money(subs.filter { it.annualCost.currency == out }.sumOf { it.annualCost.minor }, out)
        val rows = subs.map { AnswerRow("${it.name} (${it.periodicity.name.lowercase()})", it.currentAmount, it.series.transactions.size, it.nextRenewal) }
        val names = subs.take(6).joinToString(", ") { "${it.name} ${MoneyFormat.format(it.currentAmount)}/${periodWord(it.periodicity)}" }
        return Answer(
            "You have ${subs.size} active subscription${if (subs.size == 1) "" else "s"} costing about ${MoneyFormat.format(annual)} a year: $names.",
            annual, rows, subs.size, q,
        )
    }

    private fun periodWord(p: Periodicity) = when (p) {
        Periodicity.WEEKLY -> "wk"; Periodicity.BIWEEKLY -> "2wk"; Periodicity.MONTHLY -> "mo"
        Periodicity.QUARTERLY -> "qtr"; Periodicity.YEARLY -> "yr"
    }

    private fun display(s: String): String =
        if (s.any { it.isUpperCase() }) s else MerchantNormalizer.display(s)

    private fun subjectPhrase(q: Query): String = buildString {
        if (q.merchant != null) append(" on ${display(q.merchant)}")
        if (q.category != null) append(" on ${q.category.replaceFirstChar { it.lowercase() }.let { c -> categoriesById.values.firstOrNull { it.name.equals(q.category, true) }?.name ?: c }}")
        if (q.trip != null) append(" on your ${trips.firstOrNull { it.name.contains(q.trip, true) }?.name ?: display(q.trip)} trip")
        if (q.country != null) append(" in ${Locale("", q.country).getDisplayCountry(Locale.ENGLISH)}")
        if (q.account != null) append(" using ${q.account}")
        if (q.tag != null) append(" tagged #${q.tag}")
        if (q.currency != null) append(" in ${q.currency} transactions")
    }
}
