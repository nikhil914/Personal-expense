package app.ledgerly.core.insights

import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ForecastConfig(
    /** History window used to estimate discretionary spend by weekday. */
    val lookbackDays: Long = 90,
    /** Warn when the projected balance drops below this many minor units of the balance currency. */
    val lowBalanceThresholdMinor: Long = 0,
    /** Recurring series below this confidence are ignored. */
    val minSeriesConfidence: Double = 0.5,
)

/** A one-off future cash event (e.g. a planned purchase). Positive = inflow, negative = outflow. */
data class PlannedEvent(val date: LocalDate, val amount: Money, val label: String)

data class ForecastEvent(val label: String, val amount: Money, val recurring: Boolean)

data class ForecastDay(
    val date: LocalDate,
    val openingBalance: Money,
    val inflow: Money,
    val outflow: Money,
    val closingBalance: Money,
    val events: List<ForecastEvent>,
)

data class CashFlowForecast(
    val startDate: LocalDate,
    val startBalance: Money,
    val days: List<ForecastDay>,
    val lowestDay: ForecastDay?,
    /** Average discretionary spend per weekday (base minor units -> Money). */
    val discretionaryByWeekday: Map<DayOfWeek, Money>,
    val warning: String?,
) {
    val endBalance: Money get() = days.lastOrNull()?.closingBalance ?: startBalance
    val lowestBalance: Money get() = lowestDay?.closingBalance ?: startBalance
    fun balanceOn(date: LocalDate): Money? = days.firstOrNull { it.date == date }?.closingBalance
}

/**
 * Projects a daily balance for 30/60/90 days from current balances, detected recurring income/expenses, and
 * average discretionary spend per weekday.
 */
class CashFlowForecaster(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val config: ForecastConfig = ForecastConfig(),
    private val detector: RecurringDetector = RecurringDetector(clock),
) {
    private val zone get() = clock.zone

    fun forecast(
        currentBalances: Collection<Money>,
        history: Collection<Transaction>,
        horizonDays: Int = 30,
        recurring: List<RecurringSeries>? = null,
        planned: List<PlannedEvent> = emptyList(),
    ): CashFlowForecast {
        require(currentBalances.isNotEmpty()) { "At least one balance is required" }
        val currency = currentBalances.first().currency
        return forecast(currentBalances.fold(Money.zero(currency)) { a, b -> a + b }, history, horizonDays, recurring, planned)
    }

    fun forecast(
        currentBalance: Money,
        history: Collection<Transaction>,
        horizonDays: Int = 30,
        recurring: List<RecurringSeries>? = null,
        planned: List<PlannedEvent> = emptyList(),
    ): CashFlowForecast {
        require(horizonDays in 1..366) { "horizonDays must be 1..366" }
        val currency = currentBalance.currency
        val today = LocalDate.now(clock)
        val end = today.plusDays(horizonDays.toLong())
        val series = (recurring ?: detector.detect(history))
            .filter { it.active && it.confidence >= config.minSeriesConfidence && it.nextExpectedAmount.currency == currency }
        val recurringIds = series.flatMapTo(mutableSetOf()) { it.transactionIds }

        val discretionary = discretionaryByWeekday(history, recurringIds, today, currency)

        // Recurring occurrences (catch up an overdue-but-active charge on the first day).
        val events = mutableMapOf<LocalDate, MutableList<ForecastEvent>>()
        for (s in series) {
            val sign = if (s.flow == Flow.IN) 1 else -1
            val first = today.plusDays(1)
            val occ = s.occurrencesUntil(end)
            val overdue = occ.filter { it.isBefore(first) }
            val dates = (if (overdue.isNotEmpty()) listOf(first) else emptyList()) + occ.filterNot { it.isBefore(first) }
            for (day in dates) {
                events.getOrPut(day) { mutableListOf() } += ForecastEvent(s.merchant, s.nextExpectedAmount * sign, true)
            }
        }
        for (p in planned) {
            if (p.date.isAfter(today) && !p.date.isAfter(end) || p.date == today) {
                val day = if (p.date == today) today.plusDays(1) else p.date
                events.getOrPut(day) { mutableListOf() } += ForecastEvent(p.label, p.amount, false)
            }
        }

        var bal = currentBalance
        val days = mutableListOf<ForecastDay>()
        for (i in 1..horizonDays) {
            val date = today.plusDays(i.toLong())
            val evs = events[date].orEmpty().toMutableList()
            val disc = discretionary.getValue(date.dayOfWeek)
            if (disc.isPositive) evs += ForecastEvent("Everyday spending", -disc, false)
            val inflow = Money(evs.filter { it.amount.isPositive }.sumOf { it.amount.minor }, currency)
            val outflow = Money(evs.filter { it.amount.isNegative }.sumOf { -it.amount.minor }, currency)
            val close = bal + inflow - outflow
            days += ForecastDay(date, bal, inflow, outflow, close, evs)
            bal = close
        }
        val lowest = days.minByOrNull { it.closingBalance.minor }
        val warning = lowest?.takeIf { it.closingBalance.minor < config.lowBalanceThresholdMinor }?.let {
            "Balance is projected to drop to ${MoneyFormat.format(it.closingBalance)} on ${it.date.shortLabel()}" +
                (if (it.closingBalance.isNegative) " (overdrawn)." else ".")
        }
        return CashFlowForecast(today, currentBalance, days, lowest, discretionary, warning)
    }

    /** Average non-recurring outflow per weekday over the lookback window. */
    fun discretionaryByWeekday(
        history: Collection<Transaction>,
        recurringIds: Set<String>,
        today: LocalDate,
        currency: app.ledgerly.core.model.CurrencyCode,
    ): Map<DayOfWeek, Money> {
        val start = today.minusDays(config.lookbackDays)
        val window = history.filter {
            it.isActive() && it.id !in recurringIds && it.base.currency == currency &&
                (it.type == TxnType.EXPENSE || it.type == TxnType.ATM_WITHDRAWAL)
        }.filter { val d = it.localDate(zone); !d.isBefore(start) && d.isBefore(today) }
        if (window.isEmpty()) return DayOfWeek.entries.associateWith { Money.zero(currency) }
        // Count weekdays from the first observed spend (or window start) to yesterday, so short histories aren't diluted.
        val firstDay = window.minOf { it.localDate(zone) }.let { if (it.isBefore(start)) start else it }
        val totalDays = ChronoUnit.DAYS.between(firstDay, today).toInt()
        val counts = mutableMapOf<DayOfWeek, Int>()
        for (i in 0 until totalDays) counts.merge(firstDay.plusDays(i.toLong()).dayOfWeek, 1, Int::plus)
        val sums = window.groupBy { it.localDate(zone).dayOfWeek }.mapValues { (_, ts) -> ts.sumOf { it.baseAbs.minor } }
        return DayOfWeek.entries.associateWith { dow ->
            val n = counts[dow] ?: 0
            if (n == 0) Money.zero(currency) else moneyOfMinor((sums[dow] ?: 0L).toDouble() / n, currency)
        }
    }
}
