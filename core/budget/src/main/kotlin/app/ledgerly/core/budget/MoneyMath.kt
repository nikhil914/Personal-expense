package app.ledgerly.core.budget

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/** Small money helpers shared inside the budget module. */
object MoneyMath {
    private val SYMBOLS = mapOf("INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥")

    /** "₹2,300", "₹1,23,456.50", "-₹50". INR uses lakh grouping. */
    fun human(m: Money): String {
        val dec = m.toDecimal().abs()
        val whole = dec.setScale(0, RoundingMode.DOWN)
        val frac = dec.subtract(whole)
        val digits = whole.toPlainString()
        val grouped = if (digits.length <= 3) digits else {
            val size = if (m.currency.code == "INR") 2 else 3
            val rest = digits.dropLast(3)
            val parts = ArrayList<String>()
            var i = rest.length
            while (i > 0) { parts.add(0, rest.substring(maxOf(0, i - size), i)); i -= size }
            parts.joinToString(",") + "," + digits.takeLast(3)
        }
        val fracText = if (frac.signum() == 0) "" else "." + frac.toPlainString().substringAfter('.')
        val sym = SYMBOLS[m.currency.code]
        val body = if (sym != null) "$sym$grouped$fracText" else "${m.currency.code} $grouped$fracText"
        return if (m.isNegative) "-$body" else body
    }

    fun zero(c: CurrencyCode) = Money.zero(c)

    fun max(a: Money, b: Money): Money = if (a >= b) a else b
    fun min(a: Money, b: Money): Money = if (a <= b) a else b

    /** Floor division into [n] parts (never over-promises money). */
    fun divFloor(m: Money, n: Int): Money {
        require(n > 0); return Money(Math.floorDiv(m.minor, n.toLong()), m.currency)
    }

    /** Ceiling division (for "you need to save X per month"). */
    fun divCeil(m: Money, n: Int): Money {
        require(n > 0); return Money(-Math.floorDiv(-m.minor, n.toLong()), m.currency)
    }

    /** part/whole*100, one decimal. whole <= 0 → 100 if part > 0 else 0. */
    fun percent(part: Money, whole: Money): Double {
        if (whole.minor <= 0) return if (part.minor > 0) 100.0 else 0.0
        return BigDecimal(part.minor).multiply(BigDecimal(100))
            .divide(BigDecimal(whole.minor), 1, RoundingMode.HALF_UP).toDouble()
    }

    /** m * num / den with HALF_EVEN rounding to minor units. */
    fun scale(m: Money, num: BigDecimal, den: BigDecimal): Money =
        if (den.signum() == 0) Money.zero(m.currency)
        else Money(BigDecimal(m.minor).multiply(num).divide(den, 0, RoundingMode.HALF_EVEN).longValueExact(), m.currency)

    fun sum(items: Iterable<Money>, c: CurrencyCode): Money = items.fold(Money.zero(c)) { a, b -> a + b }
}
