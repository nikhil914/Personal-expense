package app.ledgerly.core.parser

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import java.math.BigDecimal

/** Text clean-up shared by every parser stage. */
object TextNormalizer {
    private val WS = Regex("[\\s\\u00A0\\u2007\\u202F]+")

    /** Collapses all whitespace (incl. NBSP and newlines) to single spaces and trims. */
    fun normalize(text: String?): String = if (text == null) "" else WS.replace(text, " ").trim()

    /**
     * Combines notification title and body into one line. The title is dropped when the body
     * already contains it. Parts are separated by " | " so extractors can stop at the boundary.
     */
    fun combine(title: String?, body: String?): String {
        val t = normalize(title)
        val b = normalize(body)
        return when {
            t.isEmpty() -> b
            b.isEmpty() -> t
            b.contains(t, ignoreCase = true) -> b
            else -> "$t | $b"
        }
    }
}

/** Resolves currency tokens (symbols, abbreviations, ISO codes) to [CurrencyCode]. */
object Currencies {
    /** ISO codes the parser recognises as currency markers in free text. */
    val KNOWN_CODES: List<String> = listOf(
        "INR", "USD", "EUR", "GBP", "AED", "SGD", "JPY", "AUD", "CAD", "CHF", "CNY", "HKD", "NZD",
        "SAR", "QAR", "KWD", "BHD", "OMR", "THB", "MYR", "IDR", "LKR", "NPR", "BDT", "ZAR", "SEK",
        "NOK", "DKK", "PLN", "CZK", "HUF", "TRY", "KRW", "VND", "PHP", "MXN", "BRL", "ILS", "EGP",
    )

    private val SYMBOLS: List<Pair<String, String>> = listOf(
        "US$" to "USD", "S$" to "SGD", "A$" to "AUD", "C$" to "CAD", "HK$" to "HKD", "NZ$" to "NZD",
        "₹" to "INR", "€" to "EUR", "£" to "GBP", "¥" to "JPY", "฿" to "THB", "₩" to "KRW", "₱" to "PHP",
        "Rs." to "INR", "Rs" to "INR", "Rs:" to "INR", "Dhs" to "AED", "Dhs." to "AED", "\$" to "USD",
    )

    /** Regex alternation (longest first) matching any currency marker that may precede an amount. */
    val PREFIX_TOKEN: String = run {
        val sym = listOf("US\\$", "S\\$", "A\\$", "C\\$", "HK\\$", "NZ\\$", "₹", "€", "£", "¥", "฿", "₩", "₱",
            "Rs\\.?", "Dhs\\.?", "\\$")
        "(?:" + (KNOWN_CODES + sym).joinToString("|") + ")"
    }

    /** Regex alternation matching ISO codes that may follow an amount ("23.40 EUR"). */
    val SUFFIX_TOKEN: String = "(?:" + KNOWN_CODES.joinToString("|") + ")"

    /**
     * Resolves a currency token. Unknown or blank tokens yield null.
     * @param dollar the currency a bare "$" maps to (USD by default).
     */
    fun resolve(token: String?, dollar: CurrencyCode = CurrencyCode.USD): CurrencyCode? {
        val t = token?.trim()?.trimEnd(':')?.trim() ?: return null
        if (t.isEmpty()) return null
        if (t == "\$") return dollar
        SYMBOLS.firstOrNull { it.first.equals(t, ignoreCase = true) || it.first.trimEnd('.') == t.trimEnd('.') }
            ?.let { return CurrencyCode.of(it.second) }
        val up = t.uppercase()
        if (up.length == 3 && up.all { it in 'A'..'Z' }) return runCatching { CurrencyCode.of(up) }.getOrNull()
        return null
    }
}

/** One amount found in text. */
data class AmountHit(
    val money: Money,
    val range: IntRange,
    val explicitCurrency: Boolean,
    val numberText: String,
)

/** Robust amount detection and number parsing (lakh grouping, decimal commas, ...). */
object AmountParser {
    /** Number pattern without groups: thousands/lakh grouping, euro style, plain decimals. */
    const val NUMBER: String =
        "(?:\\d{1,3}(?:,\\d{2,3})*,\\d{3}(?:\\.\\d{1,3})?" +
            "|\\d{1,3}(?:\\.\\d{3})+,\\d{1,2}" +
            "|\\d+\\.\\d{1,3}" +
            "|\\d+,\\d{2}(?![\\d,])" +
            "|\\d+)(?![\\d])"

    private val PREFIXED = Regex(
        "(?<![A-Za-z])(" + Currencies.PREFIX_TOKEN + ")\\s*(" + NUMBER + ")(?:/-)?",
    )
    private val SUFFIXED = Regex(
        "(?<![\\d.,])(" + NUMBER + ")\\s?(" + Currencies.SUFFIX_TOKEN + ")(?![A-Za-z])",
    )

    /** Parses a number string such as "1,23,456.78", "1.234,56", "12,50" or "500". */
    fun parseNumber(raw: String?): BigDecimal? {
        var s = raw?.trim()?.removeSuffix("/-")?.trim() ?: return null
        if (s.isEmpty()) return null
        s = s.trimEnd('.', ',')
        val hasComma = s.contains(',')
        val hasDot = s.contains('.')
        val normalized = when {
            hasComma && hasDot ->
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            hasComma ->
                if (Regex("\\d+,\\d{1,2}").matches(s)) s.replace(',', '.') else s.replace(",", "")
            hasDot && s.count { it == '.' } > 1 -> s.replace(".", "")
            else -> s
        }
        if (!Regex("\\d+(?:\\.\\d+)?").matches(normalized)) return null
        return runCatching { BigDecimal(normalized) }.getOrNull()
    }

    /** Builds [Money] from number + currency token, falling back to [default] currency. */
    fun toMoney(number: String?, currencyToken: String?, default: CurrencyCode, dollar: CurrencyCode = CurrencyCode.USD): Money? {
        val value = parseNumber(number) ?: return null
        val cur = Currencies.resolve(currencyToken, dollar) ?: default
        return runCatching { Money.of(value, cur) }.getOrNull()
    }

    /** Finds every amount carrying an explicit currency marker, in text order. */
    fun findAll(text: String, dollar: CurrencyCode = CurrencyCode.USD): List<AmountHit> {
        val hits = mutableListOf<AmountHit>()
        for (m in PREFIXED.findAll(text)) {
            val money = toMoney(m.groupValues[2], m.groupValues[1], CurrencyCode.INR, dollar) ?: continue
            hits += AmountHit(money, m.range, true, m.groupValues[2])
        }
        for (m in SUFFIXED.findAll(text)) {
            if (hits.any { it.range.overlaps(m.range) }) continue
            val money = toMoney(m.groupValues[1], m.groupValues[2], CurrencyCode.INR, dollar) ?: continue
            hits += AmountHit(money, m.range, true, m.groupValues[1])
        }
        return hits.sortedBy { it.range.first }
    }

    /** True when [value] appears in [text] as a number (any grouping/decimal style). */
    fun textContainsAmount(text: String, value: BigDecimal): Boolean =
        Regex(NUMBER).findAll(text).any { m ->
            val n = parseNumber(m.value)
            n != null && n.compareTo(value) == 0
        }

    internal fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}
