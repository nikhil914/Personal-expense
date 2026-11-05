package app.ledgerly.core.insights

import app.ledgerly.core.model.Category
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import java.time.Clock
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

data class CategoryChange(val categoryId: Id?, val name: String, val current: Money, val previous: Money, val changePct: Double?)

data class UnusualSpend(val transaction: Transaction, val zScore: Double, val categoryName: String)

data class CreepSignal(val scope: String, val recentAvg: Money, val earlierAvg: Money, val changePct: Double)

data class MonthlyReview(
    val month: YearMonth,
    val bullets: List<String>,
    val totalSpend: Money,
    val previousSpend: Money,
    val changePct: Double?,
    val income: Money,
    val savingsRate: Double?,
    val categoryChanges: List<CategoryChange>,
    val newMerchants: List<String>,
    val renewedSubscriptions: List<String>,
    val fxFees: Money?,
    val unusual: List<UnusualSpend>,
    val lifestyleCreep: List<CreepSignal>,
)

data class MonthlyReviewConfig(
    val topCategoryChanges: Int = 3,
    /** Ignore category changes smaller than this (minor units). */
    val minCategoryDeltaMinor: Long = 50_000,
    val zScoreThreshold: Double = 2.0,
    val historyMonths: Int = 6,
    val minHistoryPoints: Int = 5,
    val creepThresholdPct: Double = 10.0,
)

/** Plain-language monthly review bullets. */
class MonthlyReviewGenerator(
    private val clock: Clock = Clock.systemDefaultZone(),
    categories: Collection<Category> = emptyList(),
    private val config: MonthlyReviewConfig = MonthlyReviewConfig(),
    private val detector: RecurringDetector = RecurringDetector(clock),
) {
    private val zone get() = clock.zone
    private val cats = categories.associateBy { it.id }

    private fun catName(id: Id?) = id?.let { cats[it]?.name } ?: "Uncategorised"

    /** @param fxFeesTotal FX fees for the month if known (e.g. from core:fx); otherwise summed from Transaction.fxFee. */
    fun generate(month: YearMonth, transactions: Collection<Transaction>, fxFeesTotal: Money? = null): MonthlyReview {
        val cur = dominantBaseCurrency(transactions)
        fun ym(t: Transaction) = YearMonth.from(t.localDate(zone))
        val spend = transactions.filter { it.isSpend() && it.base.currency == cur }
        val cur0 = spend.filter { ym(it) == month }
        val prev = spend.filter { ym(it) == month.minusMonths(1) }
        val total = sumBase(cur0, cur)
        val prevTotal = sumBase(prev, cur)
        val change = pct(total.minor, prevTotal.minor)
        val bullets = mutableListOf<String>()
        val mName = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

        bullets += when {
            prev.isEmpty() -> "You spent ${MoneyFormat.format(total)} in $mName."
            change == null || kotlin.math.abs(change) < 1 -> "You spent ${MoneyFormat.format(total)} in $mName, about the same as last month."
            change > 0 -> "You spent ${MoneyFormat.format(total)} in $mName, ${fmtPct(change)} more than last month (${MoneyFormat.format(prevTotal)})."
            else -> "You spent ${MoneyFormat.format(total)} in $mName, ${fmtPct(change)} less than last month (${MoneyFormat.format(prevTotal)})."
        }

        // Category changes
        val curByCat = cur0.groupBy { it.categoryId }.mapValues { sumBase(it.value, cur) }
        val prevByCat = prev.groupBy { it.categoryId }.mapValues { sumBase(it.value, cur) }
        val changes = (curByCat.keys + prevByCat.keys).map { id ->
            val a = curByCat[id] ?: Money.zero(cur); val b = prevByCat[id] ?: Money.zero(cur)
            CategoryChange(id, catName(id), a, b, pct(a.minor, b.minor))
        }.filter { kotlin.math.abs(it.current.minor - it.previous.minor) >= config.minCategoryDeltaMinor }
            .sortedByDescending { kotlin.math.abs(it.current.minor - it.previous.minor) }
            .take(config.topCategoryChanges)
        if (prev.isNotEmpty()) for (c in changes) {
            bullets += when {
                c.previous.isZero -> "${c.name}: ${MoneyFormat.format(c.current)} (nothing last month)."
                c.current.isZero -> "${c.name}: nothing this month (was ${MoneyFormat.format(c.previous)})."
                else -> "${c.name} ${if (c.changePct!! > 0) "up" else "down"} ${fmtPct(c.changePct)} to ${MoneyFormat.format(c.current)}."
            }
        }

        // New merchants
        val before = spend.filter { ym(it) < month }.mapNotNull { it.merchantKey() }.toSet()
        val newMerchants = cur0.filter { it.merchantKey() != null && it.merchantKey() !in before }
            .distinctBy { it.merchantKey() }.map { it.merchant ?: MerchantNormalizer.display(it.merchantKey()!!) }
        if (newMerchants.isNotEmpty() && before.isNotEmpty()) {
            bullets += "New places: ${newMerchants.take(5).joinToString(", ")}${if (newMerchants.size > 5) " and ${newMerchants.size - 5} more" else ""}."
        }

        // Subscriptions renewed this month
        val upToMonth = transactions.filter { ym(it) <= month }
        val subs = SubscriptionManager(clock, detector = detector).subscriptions(upToMonth, detector.detect(upToMonth).map { it.copy(active = true) })
        val renewed = subs.filter { s -> s.series.transactions.any { ym(it) == month } }
        if (renewed.isNotEmpty()) {
            val sum = Money(renewed.sumOf { s -> s.series.transactions.filter { ym(it) == month }.sumOf { it.baseAbs.minor } }, cur)
            bullets += "${renewed.size} subscription${if (renewed.size == 1) "" else "s"} renewed (${MoneyFormat.format(sum)}): ${renewed.joinToString(", ") { it.name }}."
        }

        // FX fees
        val fx = fxFeesTotal ?: transactions.filter { it.isActive() && ym(it) == month && it.fxFee != null }
            .mapNotNull { it.fxFee }.filter { it.currency == cur }
            .takeIf { it.isNotEmpty() }?.let { fees -> Money(fees.sumOf { it.abs().minor }, cur) }
        if (fx != null && fx.isPositive) bullets += "You paid ${MoneyFormat.format(fx)} in foreign-exchange fees."

        // Savings rate
        val income = sumBase(transactions.filter { it.isIncome() && it.base.currency == cur && ym(it) == month }, cur)
        val savingsRate = if (income.isPositive) (income.minor - total.minor).toDouble() * 100.0 / income.minor else null
        if (savingsRate != null) {
            bullets += if (savingsRate >= 0) "You saved ${fmtPct(savingsRate)} of your income (${MoneyFormat.format(income - total)})."
            else "You spent ${fmtPct(-savingsRate)} more than you earned."
        }

        // Unusual spends (z-score vs category history)
        val historyStart = month.minusMonths(config.historyMonths.toLong())
        val unusual = cur0.mapNotNull { t ->
            val hist = spend.filter { it.categoryId == t.categoryId && ym(it) >= historyStart && ym(it) < month }
                .map { it.baseAbs.minor.toDouble() }
            if (hist.size < config.minHistoryPoints) return@mapNotNull null
            val sd = stddev(hist)
            if (sd == 0.0) return@mapNotNull null
            val z = (t.baseAbs.minor - mean(hist)) / sd
            if (z > config.zScoreThreshold) UnusualSpend(t, z, catName(t.categoryId)) else null
        }.sortedByDescending { it.zScore }
        for (u in unusual.take(3)) {
            bullets += "Unusual: ${MoneyFormat.format(u.transaction.baseAbs)}${u.transaction.merchant?.let { " at $it" } ?: ""} " +
                "on ${u.transaction.localDate(zone).shortLabel()} is much higher than your typical ${u.categoryName} spend."
        }

        // Lifestyle creep
        val creep = mutableListOf<CreepSignal>()
        fun creepFor(scope: String, ts: List<Transaction>) {
            val monthly = (0 until 6).map { i -> sumBase(ts.filter { ym(it) == month.minusMonths(i.toLong()) }, cur).minor.toDouble() }
            val recent = monthly.subList(0, 3).average()
            val earlier = monthly.subList(3, 6).average()
            if (earlier <= 0) return
            if (monthly.subList(3, 6).any { it == 0.0 }) return
            val p = (recent - earlier) * 100.0 / earlier
            if (p > config.creepThresholdPct) creep += CreepSignal(scope, moneyOfMinor(recent, cur), moneyOfMinor(earlier, cur), p)
        }
        creepFor("Overall", spend)
        spend.groupBy { it.categoryId }.forEach { (id, ts) -> creepFor(catName(id), ts) }
        for (c in creep.sortedByDescending { it.changePct }.take(3)) {
            bullets += "Lifestyle creep: ${if (c.scope == "Overall") "your spending" else c.scope} averaged ${MoneyFormat.format(c.recentAvg)}/month " +
                "over the last 3 months, up ${fmtPct(c.changePct)} from ${MoneyFormat.format(c.earlierAvg)}."
        }

        return MonthlyReview(month, bullets, total, prevTotal, change, income, savingsRate, changes, newMerchants,
            renewed.map { it.name }, fx, unusual, creep)
    }
}
