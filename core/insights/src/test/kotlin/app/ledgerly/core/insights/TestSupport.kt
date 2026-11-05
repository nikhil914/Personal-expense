package app.ledgerly.core.insights

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

val UTC: ZoneId = ZoneOffset.UTC
val TODAY: LocalDate = LocalDate.of(2026, 11, 5) // Thursday

fun clockAt(date: LocalDate, hour: Int = 10, minute: Int = 0, zone: ZoneId = UTC): Clock =
    Clock.fixed(LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant(), zone)

fun clockAt(dt: LocalDateTime, zone: ZoneId = UTC): Clock = Clock.fixed(dt.atZone(zone).toInstant(), zone)

val FIXED: Clock = clockAt(TODAY)

fun inr(amount: String) = Money.of(amount, CurrencyCode.INR)
fun inr(amount: Int) = Money.of(amount.toString(), CurrencyCode.INR)
fun eur(amount: String) = Money.of(amount, CurrencyCode.EUR)

private val seq = AtomicInteger()

fun d(s: String): LocalDate = LocalDate.parse(s)

fun txn(
    date: LocalDate,
    amount: Money,
    merchant: String? = null,
    type: TxnType = TxnType.EXPENSE,
    category: Id? = null,
    hour: Int = 12,
    minute: Int = 0,
    account: Id? = null,
    tags: Set<String> = emptySet(),
    trip: Id? = null,
    original: Money = amount,
    id: Id = "t${seq.incrementAndGet()}",
    merchantRaw: String? = null,
    fxFee: Money? = null,
    last4: String? = null,
    zone: ZoneId = UTC,
): Transaction = Transaction(
    id = id,
    accountId = account,
    type = type,
    occurredAt = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant(),
    original = original,
    account = amount,
    base = amount,
    merchant = merchant,
    merchantRaw = merchantRaw,
    categoryId = category,
    tags = tags,
    tripId = trip,
    fxFee = fxFee,
    accountLast4 = last4,
)

fun txn(date: String, amount: Int, merchant: String? = null, type: TxnType = TxnType.EXPENSE, category: Id? = null): Transaction =
    txn(d(date), inr(amount), merchant, type, category)

/** Monthly charges on [day] with per-month jitter offsets. */
fun monthly(merchant: String, amount: Money, startMonth: String, day: Int, jitter: List<Int>, type: TxnType = TxnType.EXPENSE, category: Id? = null): List<Transaction> {
    val ym = java.time.YearMonth.parse(startMonth)
    return jitter.mapIndexed { i, j -> txn(ym.plusMonths(i.toLong()).atDay(day).plusDays(j.toLong()), amount, merchant, type, category) }
}
