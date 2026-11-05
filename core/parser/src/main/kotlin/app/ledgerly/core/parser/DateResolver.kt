package app.ledgerly.core.parser

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A date (and optional time) found in message text. */
data class DateHit(val date: LocalDate, val time: LocalTime?, val range: IntRange, val hadYear: Boolean)

/**
 * Finds and resolves dates in many bank formats: dd-MM-yy, dd/MM/yyyy, dd.MM.yyyy, dd-MMM-yy,
 * ddMMMyy, yyyy-MM-dd (optionally ":HH:mm:ss"), "03 Nov", "Nov 3, 2026", with optional times.
 * Year-less dates are resolved relative to the message's receivedAt in [zone].
 */
class DateResolver(
    val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    /** Interpret ambiguous numeric dates as day-first (India/UK). US-style banks may set false. */
    val dayFirst: Boolean = true,
) {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val mon = "(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:t|tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)(?![a-z])\\.?"
    private val time = "(?:\\s*(?:at|,|:|T|@)?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\s*([AaPp])\\.?[Mm]\\.?)?)?"

    private val iso = Regex("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)$time")
    private val numeric = Regex("(?<![\\d.])(\\d{1,2})([-/.])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d])$time")
    private val dayMon = Regex("(?i)(?<![\\d])(\\d{1,2})(?:st|nd|rd|th)?[-\\s/]?$mon(?:[-\\s/,]*(\\d{4}|\\d{2}(?![:\\d])))?(?![a-z])$time")
    private val monDay = Regex("(?i)(?<![a-z])$mon\\s+(\\d{1,2})(?:st|nd|rd|th)?(?![\\d:])(?:,?\\s*(\\d{4}))?$time")

    /** All dates in text order (non-overlapping). */
    fun findAll(text: String, receivedAt: Instant, preferFuture: Boolean = false): List<DateHit> {
        val ref = receivedAt.atZone(zone).toLocalDate()
        val out = mutableListOf<DateHit>()
        fun add(hit: DateHit?) {
            if (hit != null && out.none { it.range.first <= hit.range.last && hit.range.first <= it.range.last }) out += hit
        }
        iso.findAll(text).forEach { m ->
            add(build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m, 4, m.range))
        }
        numeric.findAll(text).forEach { m ->
            var a = m.groupValues[1].toInt()
            var b = m.groupValues[3].toInt()
            val yRaw = m.groupValues[4]
            val y = if (yRaw.length == 2) 2000 + yRaw.toInt() else yRaw.toInt()
            val dayFirstHere = when {
                a > 12 -> true
                b > 12 -> false
                else -> dayFirst
            }
            if (!dayFirstHere) { val t = a; a = b; b = t }
            add(build(y, b, a, m, 5, m.range))
        }
        dayMon.findAll(text).forEach { m ->
            val day = m.groupValues[1].toInt()
            val month = monthIndex(m.groupValues[2])
            val yRaw = m.groupValues[3]
            if (yRaw.isEmpty()) add(yearless(month, day, ref, preferFuture, m, 4))
            else add(build(if (yRaw.length == 2) 2000 + yRaw.toInt() else yRaw.toInt(), month, day, m, 4, m.range))
        }
        monDay.findAll(text).forEach { m ->
            val month = monthIndex(m.groupValues[1])
            val day = m.groupValues[2].toInt()
            val yRaw = m.groupValues[3]
            if (yRaw.isEmpty()) add(yearless(month, day, ref, preferFuture, m, 4))
            else add(build(yRaw.toInt(), month, day, m, 4, m.range))
        }
        return out.sortedBy { it.range.first }
    }

    /** First date in text, or null. */
    fun findFirst(text: String, receivedAt: Instant, preferFuture: Boolean = false): DateHit? =
        findAll(text, receivedAt, preferFuture).firstOrNull()

    /** Parses a single date token (e.g. a template "date" group). */
    fun parse(token: String?, receivedAt: Instant, preferFuture: Boolean = false): DateHit? =
        token?.takeIf { it.isNotBlank() }?.let { findFirst(it, receivedAt, preferFuture) }

    /**
     * Converts a hit to an Instant. Without a time: same day as receivedAt keeps receivedAt;
     * other days use local noon (never later than receivedAt). Dates implausibly far in the
     * future (> 36 h after receipt) fall back to receivedAt.
     */
    fun toInstant(hit: DateHit?, receivedAt: Instant): Instant {
        if (hit == null) return receivedAt
        val refDate = receivedAt.atZone(zone).toLocalDate()
        val candidate = when {
            hit.time != null -> hit.date.atTime(hit.time).atZone(zone).toInstant()
            hit.date == refDate -> receivedAt
            else -> hit.date.atTime(12, 0).atZone(zone).toInstant().let { if (it.isAfter(receivedAt) && hit.date.isBefore(refDate)) receivedAt else it }
        }
        return if (candidate.isAfter(receivedAt.plus(Duration.ofHours(36)))) receivedAt else candidate
    }

    private fun monthIndex(token: String): Int = months.indexOf(token.lowercase().take(3)) + 1

    private fun yearless(month: Int, day: Int, ref: LocalDate, preferFuture: Boolean, m: MatchResult, timeGroup: Int): DateHit? {
        val base = build(ref.year, month, day, m, timeGroup, m.range) ?: return null
        val d = base.date
        val adjusted = if (preferFuture) {
            if (d.isBefore(ref.minusDays(60))) d.plusYears(1) else d
        } else {
            if (d.isAfter(ref.plusDays(7))) d.minusYears(1) else d
        }
        return base.copy(date = adjusted, hadYear = false)
    }

    private fun build(y: Int, mo: Int, d: Int, m: MatchResult, timeGroup: Int, range: IntRange): DateHit? {
        val date = try { LocalDate.of(y, mo, d) } catch (e: DateTimeException) { return null }
        if (y < 1990 || y > 2100) return null
        val hh = m.groupValues.getOrNull(timeGroup)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val mm = m.groupValues.getOrNull(timeGroup + 1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val ss = m.groupValues.getOrNull(timeGroup + 2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val ampm = m.groupValues.getOrNull(timeGroup + 3)?.lowercase()
        var t: LocalTime? = null
        var end = range.last
        if (hh != null && mm != null) {
            var h = hh
            if (ampm == "p" && h < 12) h += 12
            if (ampm == "a" && h == 12) h = 0
            t = runCatching { LocalTime.of(h, mm, ss) }.getOrNull()
        }
        if (t == null) {
            // Strip the unmatched time suffix from the range.
            val g = m.groups[timeGroup]
            if (g != null) end = g.range.first - 1
        }
        return DateHit(date, t, range.first..end, true)
    }
}
