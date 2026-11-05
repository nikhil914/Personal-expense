package app.ledgerly.core.insights

import app.ledgerly.core.model.CurrencyCode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

enum class Metric { SUM, COUNT, AVG, MAX, MIN, LIST, TOP_MERCHANTS, TOP_CATEGORIES, COMPARE }

enum class GroupBy { NONE, MONTH, CATEGORY, MERCHANT }

enum class DirectionFilter { SPEND, INCOME, ALL }

/** Inclusive local-date range with a natural-language label ("last month", "in October 2026"). */
data class DateRange(val start: LocalDate, val end: LocalDate, val label: String) {
    init { require(!end.isBefore(start)) { "end before start: $start..$end" } }
    operator fun contains(d: LocalDate): Boolean = !d.isBefore(start) && !d.isAfter(end)
}

/** Structured question. All filters are optional and combined with AND. */
data class Query(
    val metric: Metric = Metric.SUM,
    val merchant: String? = null,
    /** Category name or synonym term (e.g. "food", "Groceries"); resolved by the executor incl. sub-categories. */
    val category: String? = null,
    val tag: String? = null,
    /** Filter on the transaction's original (charged) currency. */
    val currency: CurrencyCode? = null,
    /** Account name / institution / last-4 fragment. */
    val account: String? = null,
    /** Trip name fragment. */
    val trip: String? = null,
    /** ISO 3166 alpha-2 country code. */
    val country: String? = null,
    val direction: DirectionFilter = DirectionFilter.SPEND,
    val range: DateRange? = null,
    /** Second range for COMPARE. */
    val compareRange: DateRange? = null,
    val groupBy: GroupBy = GroupBy.NONE,
    val limit: Int? = null,
    val outputCurrency: CurrencyCode? = null,
    val subscriptionsOnly: Boolean = false,
    /** Parser confidence 0..1 (1 = every part of the question was understood). */
    val confidence: Double = 1.0,
)

/** Resolves time expressions relative to [today]. */
class TimeExpressionParser(private val today: LocalDate) {

    data class Match(val range: DateRange, val matchedText: String)

    private val monthNames = Month.entries

    private enum class Month(val regex: String, val num: Int) {
        JAN("jan(?:uary)?", 1), FEB("feb(?:ruary)?", 2), MAR("mar(?:ch)?", 3), APR("apr(?:il)?", 4),
        MAY("may", 5), JUN("jun(?:e)?", 6), JUL("jul(?:y)?", 7), AUG("aug(?:ust)?", 8),
        SEP("sep(?:t(?:ember)?)?", 9), OCT("oct(?:ober)?", 10), NOV("nov(?:ember)?", 11), DEC("dec(?:ember)?", 12),
    }

    private val monthAlt = monthNames.joinToString("|") { it.regex }
    private val ord = "(?:st|nd|rd|th)?"
    private val dateAlt = "(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}$ord\\s+(?:$monthAlt)(?:\\s+\\d{4})?|(?:$monthAlt)\\s+\\d{1,2}$ord(?:,?\\s+\\d{4})?|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?)"
    private val numberWords = mapOf(
        "a" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "twelve" to 12, "fifteen" to 15, "thirty" to 30, "ninety" to 90,
    )

    private fun monthOf(s: String): Int? = monthNames.firstOrNull { Regex("^(?:${it.regex})$").matches(s) }?.num

    private fun monthLabel(ym: YearMonth) = "${ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${ym.year}"

    private fun monthRange(ym: YearMonth, label: String) = DateRange(ym.atDay(1), ym.atEndOfMonth(), label)

    /** Most recent occurrence of a month (current or past). */
    private fun recentMonth(m: Int): YearMonth =
        if (m > today.monthValue) YearMonth.of(today.year - 1, m) else YearMonth.of(today.year, m)

    /** Parses a single date token; year defaults to the most recent date not after today. */
    fun parseDate(raw: String, defaultYear: Int? = null): LocalDate? {
        val s = raw.trim().lowercase()
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(s)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        var day: Int? = null; var month: Int? = null; var year: Int? = null
        Regex("^(\\d{1,2})$ord\\s+($monthAlt)(?:\\s+(\\d{4}))?$").find(s)?.let { m ->
            day = m.groupValues[1].toInt(); month = monthOf(m.groupValues[2]); year = m.groupValues[3].toIntOrNull()
        }
        if (day == null) Regex("^($monthAlt)\\s+(\\d{1,2})$ord(?:,?\\s+(\\d{4}))?$").find(s)?.let { m ->
            month = monthOf(m.groupValues[1]); day = m.groupValues[2].toInt(); year = m.groupValues[3].toIntOrNull()
        }
        if (day == null) Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$").find(s)?.let { m ->
            day = m.groupValues[1].toInt(); month = m.groupValues[2].toInt()
            year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        }
        val d = day ?: return null
        val mo = month ?: return null
        val y = year ?: defaultYear
        return runCatching {
            if (y != null) LocalDate.of(y, mo, d) else {
                val cand = LocalDate.of(today.year, mo, d)
                if (cand.isAfter(today)) cand.minusYears(1) else cand
            }
        }.getOrNull()
    }

    /** Finds the first time expression in [text] (lowercase). */
    fun find(text: String): Match? {
        val t = text.lowercase()

        // between X and Y / from X to Y
        Regex("\\b(?:between|from)\\s+($dateAlt)\\s+(?:and|to|till|until|-)\\s+($dateAlt)").find(t)?.let { m ->
            val a = parseDate(m.groupValues[1]) ?: return@let
            val explicitYearB = Regex("\\d{4}").containsMatchIn(m.groupValues[2])
            var b = parseDate(m.groupValues[2], if (explicitYearB) null else a.year) ?: return@let
            if (b.isBefore(a)) b = b.plusYears(1)
            return Match(DateRange(a, b, "between ${a.shortLabel()} and ${b.shortLabel()}"), m.value)
        }

        // last/past N days|weeks|months|years
        Regex("\\b(?:last|past|previous)\\s+(\\d+|${numberWords.keys.joinToString("|")})\\s+(days?|weeks?|months?|years?)\\b").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: numberWords.getValue(m.groupValues[1])
            val unit = m.groupValues[2]
            val start = when {
                unit.startsWith("day") -> today.minusDays(n - 1L)
                unit.startsWith("week") -> today.minusDays(7L * n - 1)
                unit.startsWith("month") -> today.minusMonths(n.toLong()).plusDays(1)
                else -> today.minusYears(n.toLong()).plusDays(1)
            }
            val unitLabel = if (n == 1) unit.trimEnd('s') else if (unit.endsWith("s")) unit else unit + "s"
            return Match(DateRange(start, today, "in the last $n $unitLabel"), m.value)
        }

        // since <date|month|year>
        Regex("\\bsince\\s+($dateAlt|(?:$monthAlt)(?:\\s+\\d{4})?|\\d{4})\\b").find(t)?.let { m ->
            val arg = m.groupValues[1]
            val start = parseDate(arg)
                ?: Regex("^($monthAlt)(?:\\s+(\\d{4}))?$").find(arg)?.let { mm ->
                    val mo = monthOf(mm.groupValues[1])!!
                    val y = mm.groupValues[2].toIntOrNull()
                    (if (y != null) YearMonth.of(y, mo) else recentMonth(mo)).atDay(1)
                }
                ?: arg.toIntOrNull()?.let { LocalDate.of(it, 1, 1) }
                ?: return@let
            if (start.isAfter(today)) return@let
            return Match(DateRange(start, today, "since ${start.longLabel()}"), m.value)
        }

        val fixed = listOf(
            "day before yesterday" to { DateRange(today.minusDays(2), today.minusDays(2), "the day before yesterday") },
            "yesterday" to { DateRange(today.minusDays(1), today.minusDays(1), "yesterday") },
            "today" to { DateRange(today, today, "today") },
            "this week" to {
                val mon = today.minusDays(today.dayOfWeek.value - 1L)
                DateRange(mon, today, "this week")
            },
            "last week" to {
                val mon = today.minusDays(today.dayOfWeek.value - 1L).minusWeeks(1)
                DateRange(mon, mon.plusDays(6), "last week")
            },
            "this month" to { monthRange(YearMonth.from(today), "this month") },
            "last month" to { monthRange(YearMonth.from(today).minusMonths(1), "last month") },
            "previous month" to { monthRange(YearMonth.from(today).minusMonths(1), "last month") },
            "this quarter" to { quarterRange(today.year, (today.monthValue - 1) / 3 + 1, "this quarter") },
            "last quarter" to {
                val q = (today.monthValue - 1) / 3 + 1
                if (q == 1) quarterRange(today.year - 1, 4, "last quarter") else quarterRange(today.year, q - 1, "last quarter")
            },
            "year to date" to { DateRange(LocalDate.of(today.year, 1, 1), today, "this year") },
            "ytd" to { DateRange(LocalDate.of(today.year, 1, 1), today, "this year") },
            "this year" to { DateRange(LocalDate.of(today.year, 1, 1), LocalDate.of(today.year, 12, 31), "this year") },
            "last year" to { DateRange(LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year - 1, 12, 31), "last year") },
        )
        for ((phrase, make) in fixed) {
            Regex("\\b$phrase\\b").find(t)?.let { return Match(make(), it.value) }
        }

        // Q1..Q4 [year]
        Regex("\\bq([1-4])(?:\\s+(?:of\\s+)?(\\d{4}))?\\b").find(t)?.let { m ->
            val q = m.groupValues[1].toInt()
            val y = m.groupValues[2].toIntOrNull()
                ?: if (LocalDate.of(today.year, (q - 1) * 3 + 1, 1).isAfter(today)) today.year - 1 else today.year
            return Match(quarterRange(y, q, "in Q$q $y"), m.value)
        }

        // on <date>
        Regex("\\b(?:on\\s+)?($dateAlt)\\b").find(t)?.let { m ->
            val d = parseDate(m.groupValues[1]) ?: return@let
            return Match(DateRange(d, d, "on ${d.longLabel()}"), m.value)
        }

        // in <month> [year]  (bare month name also accepted, except "may")
        Regex("\\b(?:in|during|for|of)\\s+($monthAlt)(?:\\s+(\\d{4}))?\\b|\\b((?!may\\b)(?:$monthAlt))(?:\\s+(\\d{4}))?\\b").find(t)?.let { m ->
            val mName = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val yStr = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val mo = monthOf(mName) ?: return@let
            val ym = yStr.toIntOrNull()?.let { YearMonth.of(it, mo) } ?: recentMonth(mo)
            return Match(monthRange(ym, "in ${monthLabel(ym)}"), m.value)
        }

        // in <year>
        Regex("\\b(?:in|during|for)?\\s*((?:19|20)\\d{2})\\b").find(t)?.let { m ->
            val y = m.groupValues[1].toInt()
            return Match(DateRange(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "in $y"), m.value)
        }
        return null
    }

    private fun quarterRange(y: Int, q: Int, label: String): DateRange {
        val start = YearMonth.of(y, (q - 1) * 3 + 1)
        return DateRange(start.atDay(1), start.plusMonths(2).atEndOfMonth(), label)
    }

    /** Resolves a standalone phrase like "last month" (used to validate LLM output). */
    fun parsePhrase(phrase: String): DateRange? = find(phrase)?.range
}
