package app.ledgerly.core.budget

import app.ledgerly.core.model.Category
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val IST: ZoneId = ZoneId.of("Asia/Kolkata")
val INR = CurrencyCode.INR

fun inr(s: String) = Money.of(s, "INR")
fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)
fun instant(date: LocalDate, hour: Int = 12): Instant = date.atTime(hour, 0).atZone(IST).toInstant()
fun clockAt(date: LocalDate, hour: Int = 9): Clock = Clock.fixed(instant(date, hour), IST)

val CATEGORIES = listOf(
    Category("food", "Food"),
    Category("groceries", "Groceries", parentId = "food"),
    Category("dining", "Dining", parentId = "food"),
    Category("transport", "Transport"),
    Category("rent", "Rent"),
    Category("shopping", "Shopping"),
    Category("insurance", "Insurance"),
    Category("salary", "Salary", isIncome = true),
)

fun aggregator() = SpendAggregator(CATEGORIES, IST, INR)

private var seq = 0
fun spend(amount: String, cat: String?, date: LocalDate, type: TxnType = TxnType.EXPENSE, id: String = "t${++seq}") =
    Transaction(id = id, accountId = "acc", type = type, occurredAt = instant(date), original = inr(amount), categoryId = cat)
