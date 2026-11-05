package app.ledgerly.core.insights

import app.ledgerly.core.model.Category
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class CategoryShare(val name: String, val percent: Int)

/** Shareable year stats. Deliberately contains no amounts. */
data class YearStats(
    val year: Int,
    val topCategories: List<CategoryShare>,
    val mostVisitedMerchant: String?,
    val mostVisitedCount: Int,
    /** ISO country codes. */
    val countries: List<String>,
    val countryNames: List<String>,
    val longestNoSpendStreakDays: Int,
    val noSpendStreakStart: LocalDate?,
    val noSpendStreakEnd: LocalDate?,
    val busiestDay: LocalDate?,
    val busiestDayCount: Int,
    val busiestWeekday: DayOfWeek?,
    val transactionCount: Int,
    val merchantCount: Int,
    val shareLines: List<String>,
)

/** Year-in-review generator (amount-free, safe to share). */
class YearInReview(
    private val clock: Clock = Clock.systemDefaultZone(),
    categories: Collection<Category> = emptyList(),
    trips: Collection<Trip> = emptyList(),
    /** Home currency; foreign original currencies map to countries when no trip is set. Null = dominant base. */
    private val homeCurrency: app.ledgerly.core.model.CurrencyCode? = null,
) {
    private val zone get() = clock.zone
    private val cats = categories.associateBy { it.id }
    private val tripsById = trips.associateBy { it.id }

    fun generate(year: Int, transactions: Collection<Transaction>): YearStats {
        val today = LocalDate.now(clock)
        val spend = transactions.filter { it.isSpend() && it.localDate(zone).year == year }
        val cur = dominantBaseCurrency(spend)
        val home = homeCurrency ?: cur

        // Top categories by share (top-level category when known)
        val total = spend.filter { it.base.currency == cur }.sumOf { it.baseAbs.minor }
        val byCat = spend.filter { it.base.currency == cur }.groupBy { t ->
            var c = t.categoryId?.let { cats[it] }
            var guard = 0
            while (c?.parentId != null && guard++ < 10) c = cats[c.parentId] ?: break
            c?.name ?: "Uncategorised"
        }.mapValues { (_, ts) -> ts.sumOf { it.baseAbs.minor } }
        val top = if (total == 0L) emptyList() else byCat.entries.sortedByDescending { it.value }.take(5)
            .map { CategoryShare(it.key, Math.round(it.value * 100.0 / total).toInt()) }

        val merchantVisits = spend.mapNotNull { t -> t.merchantKey()?.let { it to t } }.groupBy({ it.first }, { it.second })
        val most = merchantVisits.maxByOrNull { it.value.size }
        val mostName = most?.value?.lastOrNull { it.merchant != null }?.merchant ?: most?.key?.let { MerchantNormalizer.display(it) }

        // Countries: trips' countries + countries implied by foreign currencies
        val countryCodes = linkedSetOf<String>()
        for (t in spend) {
            val tc = t.tripId?.let { tripsById[it]?.countryCode }
            if (tc != null) countryCodes += tc.uppercase()
            else if (t.original.currency != home) currencyCountry(t.original.currency.code)?.let { countryCodes += it }
        }
        val countryNames = countryCodes.map { regionLocale(it).getDisplayCountry(Locale.ENGLISH) }

        // Longest no-spend streak within the year (until today for the current year)
        val start = LocalDate.of(year, 1, 1)
        val end = minOf(LocalDate.of(year, 12, 31), today)
        val spendDays = spend.map { it.localDate(zone) }.toSet()
        var best = 0; var bestStart: LocalDate? = null; var run = 0; var runStart: LocalDate? = null
        var d = start
        while (!d.isAfter(end)) {
            if (d in spendDays) run = 0 else {
                if (run == 0) runStart = d
                run++
                if (run > best) { best = run; bestStart = runStart }
            }
            d = d.plusDays(1)
        }
        val byDay = spend.groupBy { it.localDate(zone) }
        val busiest = byDay.maxWithOrNull(compareBy<Map.Entry<LocalDate, List<Transaction>>> { it.value.size }.thenBy { it.key })
        val weekday = spend.groupingBy { it.localDate(zone).dayOfWeek }.eachCount().maxByOrNull { it.value }?.key

        val lines = mutableListOf<String>()
        if (top.isNotEmpty()) lines += "Top categories: " + top.take(3).joinToString(", ") { "${it.name} ${it.percent}%" }
        if (mostName != null) lines += "Most visited: $mostName (${most!!.value.size} visits)"
        if (countryNames.isNotEmpty()) lines += "Spent in ${countryNames.size} countr${if (countryNames.size == 1) "y" else "ies"}: ${countryNames.joinToString(", ")}"
        if (best > 0) lines += "Longest no-spend streak: $best day${if (best == 1) "" else "s"}"
        if (busiest != null) lines += "Busiest day: ${busiest.key.longLabel()} (${busiest.value.size} transactions)"
        if (weekday != null) lines += "Favourite day to spend: ${weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}"

        return YearStats(
            year, top, mostName, most?.value?.size ?: 0, countryCodes.toList(), countryNames, best, bestStart,
            bestStart?.plusDays(best - 1L), busiest?.key, busiest?.value?.size ?: 0, weekday, spend.size, merchantVisits.size, lines,
        )
    }

    private fun currencyCountry(code: String): String? = when (code) {
        "EUR" -> null // ambiguous across the eurozone
        "USD" -> "US"; "GBP" -> "GB"; "JPY" -> "JP"; "INR" -> "IN"; "AED" -> "AE"; "SGD" -> "SG"; "THB" -> "TH"
        "AUD" -> "AU"; "CAD" -> "CA"; "CHF" -> "CH"; "CNY" -> "CN"; "HKD" -> "HK"; "MYR" -> "MY"; "IDR" -> "ID"
        "LKR" -> "LK"; "NPR" -> "NP"; "KRW" -> "KR"; "VND" -> "VN"; "TRY" -> "TR"; "ZAR" -> "ZA"; "NZD" -> "NZ"
        else -> null
    }
}
