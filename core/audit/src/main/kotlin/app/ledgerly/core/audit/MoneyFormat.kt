package app.ledgerly.core.audit

import app.ledgerly.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/** Money rendering helpers. [stable] is used in diffs/exports, [human] in UI text. */
object MoneyFormat {
    private val SYMBOLS = mapOf(
        "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥",
    )

    /** Stable, locale-independent rendering: "1240.00 INR". */
    fun stable(m: Money?): String? = m?.toString()

    /** "₹1,240", "₹1,240.50", "-₹50", "AED 12.00" style; INR uses lakh grouping (₹1,23,456). */
    fun human(m: Money): String {
        val dec = m.toDecimal().abs()
        val whole = dec.setScale(0, RoundingMode.DOWN)
        val frac = dec.subtract(whole)
        val grouped = group(whole.toPlainString(), m.currency.code == "INR")
        val fracText = if (frac.signum() == 0) "" else
            "." + frac.setScale(m.currency.exponent, RoundingMode.HALF_EVEN).toPlainString().substringAfter('.')
        val sym = SYMBOLS[m.currency.code]
        val body = if (sym != null) "$sym$grouped$fracText" else "${m.currency.code} $grouped$fracText"
        return if (m.isNegative) "-$body" else body
    }

    private fun group(digits: String, indian: Boolean): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val size = if (indian) 2 else 3
        val parts = ArrayList<String>()
        var i = rest.length
        while (i > 0) {
            parts.add(0, rest.substring(maxOf(0, i - size), i)); i -= size
        }
        return parts.joinToString(",") + "," + last3
    }

    internal fun decimal(b: BigDecimal?): String? = b?.stripTrailingZeros()?.toPlainString()
}
