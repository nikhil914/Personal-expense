package app.ledgerly.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ISO 4217 currency with its minor-unit exponent (JPY = 0, INR = 2, KWD = 3).
 * Unknown codes default to 2 decimals.
 */
@JvmInline
value class CurrencyCode(val code: String) {
    init {
        require(code.length == 3 && code.all { it.isUpperCase() || it.isDigit() }) {
            "Invalid ISO 4217 code: $code"
        }
    }

    val exponent: Int get() = EXPONENTS[code] ?: 2

    override fun toString(): String = code

    companion object {
        private val EXPONENTS: Map<String, Int> = buildMap {
            listOf("BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "UYI",
                "VND", "VUV", "XAF", "XOF", "XPF").forEach { put(it, 0) }
            listOf("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND").forEach { put(it, 3) }
            put("CLF", 4); put("UYW", 4)
        }

        val INR = CurrencyCode("INR")
        val USD = CurrencyCode("USD")
        val EUR = CurrencyCode("EUR")
        val GBP = CurrencyCode("GBP")
        val JPY = CurrencyCode("JPY")
        val AED = CurrencyCode("AED")
        val SGD = CurrencyCode("SGD")
        val KWD = CurrencyCode("KWD")

        fun of(code: String): CurrencyCode = CurrencyCode(code.trim().uppercase())
    }
}

/**
 * Money stored as integer minor units. Never use floating point for money.
 */
data class Money(val minor: Long, val currency: CurrencyCode) : Comparable<Money> {

    operator fun plus(other: Money): Money {
        requireSame(other); return Money(Math.addExact(minor, other.minor), currency)
    }

    operator fun minus(other: Money): Money {
        requireSame(other); return Money(Math.subtractExact(minor, other.minor), currency)
    }

    operator fun unaryMinus(): Money = Money(-minor, currency)

    operator fun times(factor: Int): Money = Money(Math.multiplyExact(minor, factor.toLong()), currency)

    fun abs(): Money = if (minor < 0) -this else this
    val isZero: Boolean get() = minor == 0L
    val isNegative: Boolean get() = minor < 0L
    val isPositive: Boolean get() = minor > 0L

    fun toDecimal(): BigDecimal = BigDecimal.valueOf(minor, currency.exponent)

    /** Multiply by a decimal factor (e.g. percentage), rounding HALF_EVEN. */
    fun times(factor: BigDecimal): Money =
        of(toDecimal().multiply(factor), currency)

    override fun compareTo(other: Money): Int {
        requireSame(other); return minor.compareTo(other.minor)
    }

    private fun requireSame(other: Money) =
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }

    override fun toString(): String = "${toDecimal().toPlainString()} $currency"

    companion object {
        fun zero(currency: CurrencyCode) = Money(0, currency)

        fun of(amount: BigDecimal, currency: CurrencyCode): Money =
            Money(amount.setScale(currency.exponent, RoundingMode.HALF_EVEN).unscaledValue().longValueExact(), currency)

        fun of(amount: String, currency: CurrencyCode): Money =
            of(BigDecimal(amount.replace(",", "").trim()), currency)

        fun of(amount: String, currency: String): Money = of(amount, CurrencyCode.of(currency))
    }
}

fun Iterable<Money>.sum(currency: CurrencyCode): Money =
    fold(Money.zero(currency)) { acc, m -> acc + m }
