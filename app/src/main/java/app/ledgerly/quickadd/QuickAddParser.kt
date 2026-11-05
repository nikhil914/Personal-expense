package app.ledgerly.quickadd

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class PaymentHint { CASH, CARD, UPI, BANK }

data class QuickAddResult(
    val amount: Money?,
    /** True when the user typed a currency ("€12.50", "12 usd"); false when [amount] used the default. */
    val currencyExplicit: Boolean,
    val description: String,
    val date: LocalDate,
    val payment: PaymentHint?,
    val isIncome: Boolean,
    val tags: Set<String>,
)

/**
 * Natural-language quick add: "450 uber", "€12.50 coffee yesterday cash", "received 5000 salary",
 * "$20 lunch with sam 3 days ago card #work". Pure Kotlin, no Android dependencies (unit-tested).
 */
object QuickAddParser {

    private val symbols = mapOf("₹" to "INR", "$" to "USD", "€" to "EUR", "£" to "GBP", "¥" to "JPY", "rs" to "INR", "rs." to "INR")

    private val isoCodes = setOf(
        "INR", "USD", "EUR", "GBP", "JPY", "AED", "SGD", "AUD", "CAD", "CHF", "THB", "MYR", "IDR", "LKR", "NPR",
        "BDT", "HKD", "CNY", "KRW", "NZD", "ZAR", "SAR", "QAR", "KWD", "OMR", "BHD", "SEK", "NOK", "DKK", "PLN",
        "CZK", "HUF", "TRY", "MXN", "BRL", "VND", "PHP",
    )

    private const val CCY = "₹|\\$|€|£|¥|rs\\.?"
    private val amountToken = Regex("^($CCY|[a-z]{3})?(\\d[\\d,]*(?:\\.\\d+)?)(k)?($CCY|[a-z]{3})?$", RegexOption.IGNORE_CASE)

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
    )

    private val incomeWords = Regex("\\b(received|got paid|income|salary|refund|cashback)\\b", RegexOption.IGNORE_CASE)
    private val removeIncomeWords = Regex("\\b(received|got paid)\\b", RegexOption.IGNORE_CASE)
    private val paymentRx = Regex("\\b(credit card|debit card|card|cash|upi|gpay|phonepe|paytm|bank transfer|bank)\\b", RegexOption.IGNORE_CASE)
    private val fillers = setOf("for", "on", "at", "spent", "paid", "pay", "bought", "a", "an", "the", "of")

    fun parse(input: String, defaultCurrency: CurrencyCode, today: LocalDate): QuickAddResult {
        var text = " ${input.trim()} "

        // Tags
        val tags = Regex("#(\\w+)").findAll(text).map { it.groupValues[1].lowercase() }.toSet()
        text = text.replace(Regex("#\\w+"), " ")

        // Dates (before amounts: "3 days ago" contains a number)
        var date = today
        fun take(rx: Regex, onMatch: (MatchResult) -> LocalDate?) {
            val m = rx.find(text) ?: return
            val d = onMatch(m) ?: return
            date = d
            text = text.removeRange(m.range).let { " $it " }
        }
        take(Regex("\\bday before yesterday\\b", RegexOption.IGNORE_CASE)) { today.minusDays(2) }
        take(Regex("\\byesterday\\b", RegexOption.IGNORE_CASE)) { today.minusDays(1) }
        take(Regex("\\btoday\\b", RegexOption.IGNORE_CASE)) { today }
        take(Regex("\\b(\\d{1,3})\\s+days?\\s+ago\\b", RegexOption.IGNORE_CASE)) { today.minusDays(it.groupValues[1].toLong()) }
        take(Regex("\\b(\\d{1,2})\\s+weeks?\\s+ago\\b", RegexOption.IGNORE_CASE)) { today.minusWeeks(it.groupValues[1].toLong()) }
        take(Regex("\\blast\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE)) {
            previous(today, weekdays.getValue(it.groupValues[1].lowercase()), strictlyBefore = true)
        }
        take(Regex("\\b(?:on\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE)) {
            previous(today, weekdays.getValue(it.groupValues[1].lowercase()), strictlyBefore = false)
        }
        take(Regex("\\b(?:on\\s+)?(\\d{4}-\\d{2}-\\d{2})\\b")) {
            try { LocalDate.parse(it.groupValues[1]) } catch (e: DateTimeParseException) { null }
        }

        // Payment method
        var payment: PaymentHint? = null
        paymentRx.find(text)?.let { m ->
            payment = when (m.groupValues[1].lowercase()) {
                "cash" -> PaymentHint.CASH
                "card", "credit card", "debit card" -> PaymentHint.CARD
                "upi", "gpay", "phonepe", "paytm" -> PaymentHint.UPI
                else -> PaymentHint.BANK
            }
            text = text.removeRange(m.range).let { " $it " }
        }

        val isIncome = incomeWords.containsMatchIn(text)
        text = removeIncomeWords.replace(text, " ")

        // Amount + currency
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        var amount: Money? = null
        var explicit = false
        var i = 0
        while (i < tokens.size) {
            val m = amountToken.matchEntire(tokens[i])
            if (m != null) {
                val pre = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { currencyOf(it) }
                val post = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { currencyOf(it) }
                // A 3-letter prefix/suffix that is not a currency means this is not an amount token.
                val badAffix = (m.groupValues[1].isNotEmpty() && pre == null) || (m.groupValues[4].isNotEmpty() && post == null)
                if (!badAffix) {
                    var number = BigDecimal(m.groupValues[2].replace(",", ""))
                    if (m.groupValues[3].isNotEmpty()) number = number.multiply(BigDecimal(1000))
                    var ccy = pre ?: post
                    var consumedPrev = false
                    var consumedNext = false
                    if (ccy == null && i > 0) currencyOf(tokens[i - 1])?.let { ccy = it; consumedPrev = true }
                    if (ccy == null && i + 1 < tokens.size) currencyOf(tokens[i + 1])?.let { ccy = it; consumedNext = true }
                    explicit = ccy != null
                    val currency = ccy ?: defaultCurrency
                    amount = Money.of(number, currency)
                    if (consumedNext) tokens.removeAt(i + 1)
                    tokens.removeAt(i)
                    if (consumedPrev) tokens.removeAt(i - 1)
                    break
                }
            }
            i++
        }

        while (tokens.isNotEmpty() && tokens.first().lowercase() in fillers) tokens.removeAt(0)
        while (tokens.isNotEmpty() && tokens.last().lowercase() in fillers) tokens.removeAt(tokens.size - 1)
        val description = tokens.joinToString(" ").trim()

        return QuickAddResult(amount, explicit, description, date, payment, isIncome, tags)
    }

    private fun currencyOf(token: String): CurrencyCode? {
        val t = token.trim()
        symbols[t.lowercase()]?.let { return CurrencyCode.of(it) }
        val upper = t.uppercase()
        return if (upper in isoCodes) CurrencyCode.of(upper) else null
    }

    private fun previous(today: LocalDate, day: DayOfWeek, strictlyBefore: Boolean): LocalDate {
        var d = if (strictlyBefore) today.minusDays(1) else today
        while (d.dayOfWeek != day) d = d.minusDays(1)
        return d
    }
}
