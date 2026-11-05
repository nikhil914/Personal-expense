package app.ledgerly.core.insights

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Money flow direction derived from [TxnType] (the model carries no explicit direction on Transaction). */
enum class Flow { OUT, IN, NEUTRAL }

internal fun Transaction.flow(): Flow = when (type) {
    TxnType.EXPENSE, TxnType.ATM_WITHDRAWAL -> Flow.OUT
    TxnType.INCOME, TxnType.REFUND -> Flow.IN
    TxnType.TRANSFER, TxnType.ADJUSTMENT -> when {
        base.isNegative -> Flow.OUT
        else -> Flow.NEUTRAL
    }
}

/** Not deleted, not failed/void. */
internal fun Transaction.isActive(): Boolean =
    !isDeleted && status != TxnStatus.FAILED && status != TxnStatus.VOID

/** Counts as "spend" for analytics (expenses only; ATM withdrawals are cash movements). */
internal fun Transaction.isSpend(): Boolean = isActive() && type == TxnType.EXPENSE

internal fun Transaction.isIncome(): Boolean = isActive() && type == TxnType.INCOME

internal fun Transaction.localDate(zone: ZoneId): LocalDate = occurredAt.atZone(zone).toLocalDate()

/** Absolute base amount. */
internal val Transaction.baseAbs: Money get() = base.abs()

internal fun Transaction.merchantKey(): String? =
    MerchantNormalizer.normalize(merchant ?: merchantRaw)

/** Normalises noisy merchant strings ("UBER *TRIP 4521 BLR", "NETFLIX.COM") to a stable lowercase key. */
object MerchantNormalizer {
    private val prefixes = listOf(
        "pos ", "upi-", "upi/", "upi ", "ecom ", "vps ", "vin ", "nfs ", "ach ", "nach ", "imps ", "neft ",
        "pur ", "purchase ", "payment to ", "paid to ", "sq *", "sq*", "tst*", "tst *", "pp*", "paypal *",
        "paypal*", "razorpay*", "rzp*",
    )
    private val suffixNoise = Regex(
        "\\b(pvt|private|ltd|limited|inc|llc|corp|co|technologies|technology|india|internet|services|" +
            "online|payments|retail|bv|gmbh|plc|com|www)\\b",
    )

    private val noiseWords = setOf("trip", "ride", "rides", "order", "bill", "payment", "autopay", "subscription")
    private val cityCodes = setOf(
        "blr", "bom", "del", "mum", "hyd", "maa", "ccu", "pnq", "ncr", "ggn", "nyc", "lon", "sfo", "sgp", "dxb", "lis",
        "in", "us", "uk", "gb", "pt", "ae", "sg",
    )

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.lowercase(Locale.ROOT).trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) if (s.startsWith(p)) { s = s.removePrefix(p).trim(); changed = true }
        }
        s = s.replace(Regex("[*#]"), " ")
            .replace(Regex("\\.(com|in|net|co|io|org)\\b"), " ")
            .replace(Regex("[^a-z0-9& ]"), " ")
            .replace(Regex("\\b\\d{3,}\\b"), " ") // reference numbers / store ids
            .replace(suffixNoise, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (s.isEmpty()) return raw.lowercase(Locale.ROOT).trim().ifEmpty { null }
        // Drop trailing 2-3 letter city codes after a multi-word name (e.g. "uber trip blr").
        val parts = s.split(' ')
        val cleaned = parts.filterIndexed { i, it ->
            it !in noiseWords && !(i > 0 && (it.length == 1 || it in cityCodes))
        }
        val result = (if (cleaned.isEmpty()) parts else cleaned).take(3).joinToString(" ")
        return result
    }

    /** Title-cased display name from a key. */
    fun display(key: String): String =
        key.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}

/** Human-friendly money formatting: "₹5,000", "₹1,00,000", "€12.50", "JPY 1,200" -> "¥1,200". */
object MoneyFormat {
    private val symbols = mapOf(
        "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥", "AED" to "AED ",
        "SGD" to "S$", "AUD" to "A$", "CAD" to "C$", "CNY" to "CN¥", "KRW" to "₩", "THB" to "฿",
    )

    fun format(m: Money, alwaysDecimals: Boolean = false): String {
        val sign = if (m.isNegative) "-" else ""
        val dec = m.abs().toDecimal()
        val whole = dec.setScale(0, RoundingMode.DOWN)
        val frac = dec.subtract(whole)
        val showDecimals = alwaysDecimals || frac.signum() != 0
        val intStr = group(whole.toBigInteger().toString(), m.currency.code == "INR")
        val fracStr = if (showDecimals && m.currency.exponent > 0)
            "." + frac.movePointRight(m.currency.exponent).setScale(0, RoundingMode.HALF_EVEN).toPlainString()
                .padStart(m.currency.exponent, '0')
        else ""
        val sym = symbols[m.currency.code] ?: "${m.currency.code} "
        return "$sign$sym$intStr$fracStr"
    }

    private fun group(digits: String, indian: Boolean): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val size = if (indian) 2 else 3
        val groups = rest.reversed().chunked(size).map { it.reversed() }.reversed()
        return groups.joinToString(",") + "," + last3
    }
}

internal fun LocalDate.shortLabel(): String =
    "$dayOfMonth ${month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

internal fun LocalDate.longLabel(): String =
    "$dayOfMonth ${month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $year"

internal fun pct(new: Long, old: Long): Double? =
    if (old == 0L) null else (new - old).toDouble() * 100.0 / kotlin.math.abs(old).toDouble()

internal fun fmtPct(p: Double): String = "${BigDecimal.valueOf(kotlin.math.abs(p)).setScale(0, RoundingMode.HALF_UP)}%"

internal fun moneyOfMinor(minor: Double, currency: CurrencyCode): Money =
    Money(BigDecimal.valueOf(minor).setScale(0, RoundingMode.HALF_EVEN).toLong(), currency)

internal fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

internal fun stddev(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val m = mean(xs)
    return kotlin.math.sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
}

internal fun median(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

/** Most common base currency in a set of transactions (falls back to [fallback]). */
internal fun dominantBaseCurrency(txns: Collection<Transaction>, fallback: CurrencyCode = CurrencyCode.INR): CurrencyCode =
    txns.groupingBy { it.base.currency }.eachCount().maxByOrNull { it.value }?.key ?: fallback

/** Sum base amounts, skipping transactions whose base currency differs from [currency]. */
internal fun sumBase(txns: Iterable<Transaction>, currency: CurrencyCode): Money =
    Money(txns.filter { it.base.currency == currency }.sumOf { it.base.abs().minor }, currency)

/** Locale for an ISO 3166 alpha-2 region code (no deprecated constructors). */
internal fun regionLocale(code: String): Locale =
    runCatching { Locale.Builder().setRegion(code.uppercase(Locale.ROOT)).build() }.getOrDefault(Locale.ROOT)
