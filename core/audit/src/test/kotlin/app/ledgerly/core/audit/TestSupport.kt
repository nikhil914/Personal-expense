package app.ledgerly.core.audit

import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

class MutableClock(var now: Instant, private val zone: ZoneId = IST) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Instant = LocalDateTime.of(y, m, d, h, min).atZone(IST).toInstant()

fun inr(s: String) = Money.of(s, "INR")

fun txn(
    id: String,
    amount: String = "100",
    type: TxnType = TxnType.EXPENSE,
    whenAt: Instant = at(2026, 11, 1),
    category: String? = "food",
    account: String? = "acc1",
    merchant: String? = "Cafe",
) = Transaction(id = id, accountId = account, type = type, occurredAt = whenAt, original = inr(amount), merchant = merchant, categoryId = category)

class Fixture(start: Instant = at(2026, 11, 3, 10)) {
    val clock = MutableClock(start)
    val store = InMemoryTransactionStore()
    val log = InMemoryAuditLog()
    private var n = 0
    val ledger = TransactionLedger(store, log, clock, deviceId = "pixel", idGenerator = { "e${++n}" })
}
