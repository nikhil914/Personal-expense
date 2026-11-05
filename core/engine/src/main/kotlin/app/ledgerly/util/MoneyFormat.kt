package app.ledgerly.util

import app.ledgerly.core.model.Money
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Locale-aware money formatting with the correct ISO 4217 minor-unit digits. Pure JVM. */
object MoneyFormat {
    fun format(money: Money, locale: Locale = Locale.getDefault(), showSign: Boolean = false): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        try {
            nf.currency = Currency.getInstance(money.currency.code)
        } catch (e: IllegalArgumentException) {
            // Unknown to the JDK: fall back to "1,234.50 XYZ".
            val plain = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = money.currency.exponent
                maximumFractionDigits = money.currency.exponent
            }
            return (if (showSign && money.isPositive) "+" else "") + plain.format(money.toDecimal()) + " " + money.currency.code
        }
        nf.minimumFractionDigits = money.currency.exponent
        nf.maximumFractionDigits = money.currency.exponent
        val s = nf.format(money.toDecimal())
        return if (showSign && money.isPositive) "+$s" else s
    }

    /** Compact form without decimals for dashboards: "₹1,250". */
    fun formatWhole(money: Money, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        runCatching { nf.currency = Currency.getInstance(money.currency.code) }
        nf.maximumFractionDigits = 0
        nf.minimumFractionDigits = 0
        return nf.format(money.toDecimal())
    }
}
