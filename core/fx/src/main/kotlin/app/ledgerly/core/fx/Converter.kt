package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Result of converting money at a market rate. [rate] is 1 unit of source = rate units of [result] currency. */
data class Conversion(
    val result: Money,
    val rate: BigDecimal,
    val rateDate: LocalDate,
    val source: String,
    val stale: Boolean,
)

/** Converts [Money] using a [RateStore]; rounds HALF_EVEN to the target currency's exponent (JPY 0, KWD 3). */
class Converter(private val rates: RateStore) {

    fun convert(money: Money, to: CurrencyCode, date: LocalDate): Conversion =
        convertOrNull(money, to, date) ?: throw RateUnavailableException(money.currency, to, date)

    fun convertOrNull(money: Money, to: CurrencyCode, date: LocalDate): Conversion? {
        val q = rates.lookup(money.currency, to, date) ?: return null
        return Conversion(applyRate(money, q.rate, to), q.rate, q.rateDate, q.source, q.isStale)
    }

    fun rate(from: CurrencyCode, to: CurrencyCode, date: LocalDate): RateQuote? = rates.lookup(from, to, date)

    companion object {
        /** money * rate, expressed in [to] with HALF_EVEN rounding at [to]'s exponent. */
        fun applyRate(money: Money, rate: BigDecimal, to: CurrencyCode): Money =
            Money.of(money.toDecimal().multiply(rate, FX_MC), to)
    }
}

/** Display helpers shared by FX messages. */
object FxFormat {
    private val SYMBOLS = mapOf(
        "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥", "CNY" to "CN¥", "KRW" to "₩",
        "THB" to "฿", "VND" to "₫", "PHP" to "₱", "ILS" to "₪", "NGN" to "₦", "TRY" to "₺", "RUB" to "₽",
        "UAH" to "₴", "AUD" to "A$", "CAD" to "C$", "SGD" to "S$", "HKD" to "HK$", "NZD" to "NZ$",
        "AED" to "AED ", "CHF" to "CHF ", "KWD" to "KWD ",
    )

    fun symbol(c: CurrencyCode): String = SYMBOLS[c.code] ?: "${c.code} "

    /** Compact display: whole units when |amount| >= 100 (e.g. ₹147), otherwise full precision. */
    fun short(m: Money): String {
        val d = m.toDecimal().abs()
        val shown = if (d >= BigDecimal(100)) d.setScale(0, RoundingMode.HALF_UP) else d
        val sign = if (m.isNegative) "-" else ""
        return sign + symbol(m.currency) + group(shown)
    }

    fun full(m: Money): String {
        val sign = if (m.isNegative) "-" else ""
        return sign + symbol(m.currency) + group(m.toDecimal().abs())
    }

    /** Percentage with at most 1 decimal, e.g. 3.5%, 2%. */
    fun pct(p: BigDecimal): String = p.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().let {
        (if (it.scale() < 0) it.setScale(0) else it).toPlainString()
    } + "%"

    private fun group(d: BigDecimal): String {
        val plain = d.toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = plain.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return if (frac.isEmpty()) grouped else "$grouped.$frac"
    }
}
