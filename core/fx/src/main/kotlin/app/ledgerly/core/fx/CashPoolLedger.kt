package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class CostMethod { FIFO, AVERAGE }

/** A foreign-cash purchase, e.g. "bought €300 for ₹27,600". */
data class CashLot(
    val id: Id,
    val date: LocalDate,
    val bought: Money,
    val cost: Money,
) {
    /** Home units per 1 foreign unit. */
    val rate: BigDecimal get() = cost.toDecimal().divide(bought.toDecimal(), 10, RoundingMode.HALF_EVEN)
}

data class LotDraw(val lotId: Id, val foreign: Money, val homeCost: Money)

data class CashSpend(
    val ref: Id?,
    val date: LocalDate,
    val foreign: Money,
    /** Real home-currency cost (covered part from lots + estimate for any overdrawn part). */
    val homeCost: Money,
    val draws: List<LotDraw>,
    /** Foreign amount not covered by any lot (0 when fully covered). */
    val overdrawn: Money,
    /** Home estimate for [overdrawn], valued at the last known pool rate (zero if no history). */
    val overdrawnHomeEstimate: Money,
) {
    val isOverdrawn: Boolean get() = overdrawn.isPositive
}

data class Revaluation(
    val currency: CurrencyCode,
    val date: LocalDate,
    val foreignBalance: Money,
    val costBasis: Money,
    val marketValue: Money,
    val unrealisedGain: Money,
)

data class Reconversion(
    val date: LocalDate,
    val foreign: Money,
    val costBasis: Money,
    val proceeds: Money,
    val realisedGain: Money,
    val draws: List<LotDraw>,
    val overdrawn: Money,
)

/**
 * Foreign cash pools. Each foreign currency has its own pool of lots. Spends draw down FIFO (or at the
 * moving average cost), giving the real home-currency cost of cash spending. Not thread-safe.
 */
class CashPoolLedger(val home: CurrencyCode, val method: CostMethod = CostMethod.FIFO) {

    private class OpenLot(val lot: CashLot, var foreignLeft: Long, var costLeft: Long)

    private val lots = LinkedHashMap<CurrencyCode, ArrayDeque<OpenLot>>()
    private val history = ArrayList<CashLot>()
    private val lastRate = HashMap<CurrencyCode, BigDecimal>()
    private var seq = 0

    fun buy(date: LocalDate, foreign: Money, homeCost: Money, id: Id? = null): CashLot {
        require(foreign.currency != home) { "Foreign cash must not be in home currency" }
        require(homeCost.currency == home) { "Cost must be in $home" }
        require(foreign.isPositive && homeCost.isPositive) { "Amounts must be positive" }
        val lot = CashLot(id ?: "lot-${++seq}", date, foreign, homeCost)
        history += lot
        lots.getOrPut(foreign.currency) { ArrayDeque() }.addLast(OpenLot(lot, foreign.minor, homeCost.minor))
        lastRate[foreign.currency] = lot.rate
        return lot
    }

    fun lots(): List<CashLot> = history.toList()

    fun balance(currency: CurrencyCode): Money = Money(open(currency).sumOf { it.foreignLeft }, currency)

    fun costBasis(currency: CurrencyCode): Money = Money(open(currency).sumOf { it.costLeft }, home)

    fun currencies(): Set<CurrencyCode> = lots.filterValues { q -> q.any { it.foreignLeft > 0 } }.keys

    /** Remaining open lots: (lot, foreignLeft, costLeft). */
    fun openLots(currency: CurrencyCode): List<Triple<CashLot, Money, Money>> =
        open(currency).filter { it.foreignLeft > 0 }.map { Triple(it.lot, Money(it.foreignLeft, currency), Money(it.costLeft, home)) }

    private fun open(c: CurrencyCode) = lots[c] ?: ArrayDeque()

    /** Record a cash spend in a foreign currency. */
    fun spend(date: LocalDate, foreign: Money, ref: Id? = null): CashSpend {
        require(foreign.currency != home && foreign.isPositive) { "Spend must be a positive foreign amount" }
        val (draws, over) = draw(foreign)
        val covered = Money(draws.sumOf { it.homeCost.minor }, home)
        val est = estimate(over)
        return CashSpend(ref, date, foreign, covered + est, draws, over, est)
    }

    /** Value the remaining balance at [currentRate] (home per 1 foreign unit). */
    fun revalue(currency: CurrencyCode, currentRate: BigDecimal, date: LocalDate): Revaluation {
        val bal = balance(currency)
        val basis = costBasis(currency)
        val mv = Converter.applyRate(bal, currentRate, home)
        return Revaluation(currency, date, bal, basis, mv, mv - basis)
    }

    /** Revalue using a [RateStore] (market rate on [date]). */
    fun revalue(currency: CurrencyCode, rates: RateStore, date: LocalDate): Revaluation {
        val q = rates.lookup(currency, home, date) ?: throw RateUnavailableException(currency, home, date)
        return revalue(currency, q.rate, date)
    }

    /** Convert leftover foreign cash back to home currency, recording realised gain/loss. */
    fun reconvert(date: LocalDate, foreign: Money, homeReceived: Money): Reconversion {
        require(homeReceived.currency == home) { "Proceeds must be in $home" }
        require(foreign.currency != home && foreign.isPositive) { "Reconversion must be a positive foreign amount" }
        val (draws, over) = draw(foreign)
        val basis = Money(draws.sumOf { it.homeCost.minor }, home) + estimate(over)
        return Reconversion(date, foreign, basis, homeReceived, homeReceived - basis, draws, over)
    }

    private fun estimate(over: Money): Money =
        if (over.isZero) Money.zero(home)
        else lastRate[over.currency]?.let { Converter.applyRate(over, it, home) } ?: Money.zero(home)

    private fun draw(foreign: Money): Pair<List<LotDraw>, Money> {
        val q = open(foreign.currency)
        val draws = ArrayList<LotDraw>()
        var need = foreign.minor
        when (method) {
            CostMethod.FIFO -> {
                for (l in q) {
                    if (need == 0L) break
                    if (l.foreignLeft == 0L) continue
                    val take = minOf(need, l.foreignLeft)
                    val cost = proportional(l.costLeft, take, l.foreignLeft)
                    l.foreignLeft -= take; l.costLeft -= cost; need -= take
                    draws += LotDraw(l.lot.id, Money(take, foreign.currency), Money(cost, home))
                }
            }
            CostMethod.AVERAGE -> {
                val totalF = q.sumOf { it.foreignLeft }
                val totalC = q.sumOf { it.costLeft }
                val take = minOf(need, totalF)
                if (take > 0) {
                    val cost = proportional(totalC, take, totalF)
                    // Reduce lots FIFO for quantity, then re-spread remaining cost at the average rate.
                    var rem = take
                    for (l in q) { val t = minOf(rem, l.foreignLeft); l.foreignLeft -= t; rem -= t }
                    var costRemaining = totalC - cost
                    val leftF = totalF - take
                    val openLots = q.filter { it.foreignLeft > 0 }
                    openLots.forEachIndexed { i, l ->
                        val c = if (i == openLots.lastIndex) costRemaining else proportional(totalC - cost, l.foreignLeft, leftF)
                        l.costLeft = c; costRemaining -= c
                    }
                    q.filter { it.foreignLeft == 0L }.forEach { it.costLeft = 0 }
                    need -= take
                    draws += LotDraw("AVERAGE", Money(take, foreign.currency), Money(cost, home))
                }
            }
        }
        q.removeAll { it.foreignLeft == 0L }
        return draws to Money(need, foreign.currency)
    }

    /** total * part / whole with HALF_EVEN rounding; exact when part == whole. */
    private fun proportional(total: Long, part: Long, whole: Long): Long =
        if (part == whole) total
        else BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
            .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_EVEN).longValueExact()
}
