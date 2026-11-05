package app.ledgerly.data.repo

import androidx.room.withTransaction
import app.ledgerly.core.model.Category
import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Trip
import app.ledgerly.data.db.CategoryEntity
import app.ledgerly.data.db.FxRateEntity
import app.ledgerly.data.db.IgnoredSenderEntity
import app.ledgerly.data.db.LedgerlyDatabase
import app.ledgerly.data.db.RecurringEntity
import app.ledgerly.data.db.RuleEntity
import app.ledgerly.data.db.RuleField
import app.ledgerly.data.db.SeedData
import app.ledgerly.data.db.SourceEventEntity
import app.ledgerly.data.db.TripEntity
import app.ledgerly.data.mapper.toModel
import app.ledgerly.engine.InsightsService
import app.ledgerly.engine.RateQuote
import app.ledgerly.engine.RateTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class CategoryRepository(private val db: LedgerlyDatabase) {
    private val dao = db.categoryDao()

    fun observeEntities(): Flow<List<CategoryEntity>> = dao.observeAll()
    fun observeModels(): Flow<List<Category>> = dao.observeAll().map { list -> list.map { it.toModel() } }
    suspend fun all(): List<CategoryEntity> = dao.getAll()
    suspend fun get(id: String): CategoryEntity? = dao.getById(id)

    /** "Always categorise <merchant> as X" from the detail screen. */
    suspend fun addMerchantRule(merchant: String, categoryId: String, fromTxnId: String?) {
        db.ruleDao().insert(
            RuleEntity(name = "$merchant -> $categoryId", field = RuleField.MERCHANT, pattern = merchant.lowercase(), categoryId = categoryId, priority = 10, createdFromTxnId = fromTxnId),
        )
    }
}

/** Seeds defaults on first run. Idempotent. */
class Seeder(private val db: LedgerlyDatabase) {
    suspend fun seedIfEmpty(baseCurrency: CurrencyCode) {
        db.withTransaction {
            if (db.categoryDao().count() == 0) db.categoryDao().insertAll(SeedData.categories)
            db.merchantDao().insertAll(SeedData.merchants)
            if (db.accountDao().count() == 0) db.accountDao().upsert(SeedData.cashAccount(baseCurrency.code))
            if (db.budgetDao().activeMonthly() == null) db.budgetDao().upsertBudget(SeedData.monthlyBudget(baseCurrency.code))
        }
    }
}

/**
 * FX reference rates. ECB publishes EUR-based rates; any cross rate is derived as
 * rate(A->B) = rate(EUR->B) / rate(EUR->A), using the latest fixing on or before the date.
 */
class FxRateRepository(private val db: LedgerlyDatabase) {
    private val dao = db.fxRateDao()

    suspend fun saveEcb(date: LocalDate, eurRates: Map<String, BigDecimal>) {
        dao.insertAll(eurRates.map { (quote, rate) -> FxRateEntity(date = date, base = "EUR", quote = quote, rate = rate, source = SOURCE_ECB) })
    }

    suspend fun latestDate(): LocalDate? = dao.latestDate()

    suspend fun quote(from: CurrencyCode, to: CurrencyCode, date: LocalDate): RateQuote? {
        if (from == to) return RateQuote(BigDecimal.ONE, date, "identity")
        val fromEur = eurRate(from, date) ?: return null
        val toEur = eurRate(to, date) ?: return null
        val rate = toEur.first.divide(fromEur.first, MathContext.DECIMAL64)
        return RateQuote(rate, minOf(fromEur.second, toEur.second), SOURCE_ECB)
    }

    /** Builds the rate table for all pairs among [currencies]. */
    suspend fun table(currencies: Collection<CurrencyCode>, date: LocalDate): RateTable {
        val list = currencies.distinct()
        val quotes = HashMap<Pair<String, String>, RateQuote>()
        for (a in list) for (b in list) if (a != b) quote(a, b, date)?.let { quotes[a.code to b.code] = it }
        return RateTable(quotes)
    }

    private suspend fun eurRate(ccy: CurrencyCode, date: LocalDate): Pair<BigDecimal, LocalDate>? {
        if (ccy.code == "EUR") return BigDecimal.ONE to date
        val row = dao.latestOnOrBefore("EUR", ccy.code, date) ?: return null
        return row.rate to row.date
    }

    companion object {
        const val SOURCE_ECB = "ECB"
    }
}

data class TripSummary(
    val trip: TripEntity,
    val totalBase: Money?,
    val byCurrency: Map<CurrencyCode, Money>,
    val fxFeesBase: Money?,
    val count: Int,
)

class TripRepository(private val db: LedgerlyDatabase) {
    private val dao = db.tripDao()

    fun observeAll(): Flow<List<TripEntity>> = dao.observeAll()
    fun observeActive(): Flow<TripEntity?> = dao.observeActive()
    suspend fun activeModels(): List<Trip> = dao.active().map { it.toModel() }
    suspend fun active(): List<TripEntity> = dao.active()

    fun observeSummary(trip: TripEntity, baseCurrency: CurrencyCode): Flow<TripSummary> =
        db.transactionDao().observeForTrip(trip.id).map { rows ->
            val txns = rows.map { it.toModel() }
            val inBase = txns.filter { it.base.currency == baseCurrency }
            val byCcy = txns.groupBy { it.original.currency }.mapValues { (ccy, list) -> list.fold(Money.zero(ccy)) { a, t -> a + t.original } }
            // FX fees are in the account currency; report those already in base currency.
            val fees = txns.mapNotNull { it.fxFee }.filter { it.currency == baseCurrency }
            TripSummary(
                trip = trip,
                totalBase = inBase.fold(Money.zero(baseCurrency)) { a, t -> a + t.base },
                byCurrency = byCcy,
                fxFeesBase = fees.fold(Money.zero(baseCurrency)) { a, m -> a + m },
                count = txns.size,
            )
        }

    suspend fun start(name: String, currency: CurrencyCode, start: LocalDate, countryCode: String? = null, autoDetected: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(TripEntity(id = id, name = name, currency = currency.code, countryCode = countryCode, startDate = start, endDate = null, autoDetected = autoDetected, active = true))
        return id
    }

    suspend fun end(id: String, endDate: LocalDate) {
        val t = dao.getById(id) ?: return
        dao.upsert(t.copy(endDate = endDate, active = false))
    }
}

class RecurringRepository(private val db: LedgerlyDatabase) {
    private val dao = db.recurringDao()

    fun observeAll(): Flow<List<RecurringEntity>> = dao.observeAll()
    fun observeUpcoming(from: LocalDate, to: LocalDate): Flow<List<RecurringEntity>> = dao.observeUpcoming(from, to)
    suspend fun upcoming(from: LocalDate, to: LocalDate): List<RecurringEntity> = dao.upcoming(from, to)
    suspend fun setActive(item: RecurringEntity, active: Boolean) = dao.upsert(item.copy(active = active))

    /** Runs recurring detection over the last 13 months and upserts auto-detected items. */
    suspend fun detect(insights: InsightsService, today: LocalDate, zone: ZoneId): Int {
        val from = today.minusMonths(13).atStartOfDay(zone).toInstant()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()
        val history = db.transactionDao().getInRange(from, to).map { it.toModel() }
        val existing = dao.getAll().associateBy { it.merchant?.lowercase() }
        val found = insights.detectRecurring(history, today, zone)
        for (c in found) {
            val prev = existing[c.merchant?.lowercase()]
            dao.upsert(
                RecurringEntity(
                    id = prev?.id ?: UUID.randomUUID().toString(),
                    name = prev?.name ?: c.name,
                    merchant = c.merchant,
                    categoryId = prev?.categoryId ?: c.categoryId,
                    amountMinor = c.amount.minor,
                    currency = c.amount.currency.code,
                    cadence = c.cadence,
                    intervalDays = c.intervalDays,
                    nextDue = c.nextDue,
                    lastSeen = c.lastSeen,
                    isSubscription = c.isSubscription,
                    active = prev?.active ?: true,
                    autoDetected = true,
                ),
            )
        }
        return found.size
    }
}

/** Ignored inbox: captures the parser dropped, plus the ignored-sender list. */
class CaptureRepository(private val db: LedgerlyDatabase) {
    fun observeIgnored(): Flow<List<SourceEventEntity>> = db.sourceEventDao().observeIgnored()
    fun observeIgnoredSenders(): Flow<List<IgnoredSenderEntity>> = db.ignoredSenderDao().observeAll()

    suspend fun reprocess(eventId: String) = db.sourceEventDao().markForReprocess(eventId)
    suspend fun delete(eventId: String) = db.sourceEventDao().delete(eventId)

    suspend fun ignoreSender(sender: String, channel: Channel, reason: String?) =
        db.ignoredSenderDao().insert(IgnoredSenderEntity(sender = sender.trim().uppercase(), channel = channel, reason = reason))

    suspend fun unignoreSender(sender: String) = db.ignoredSenderDao().delete(sender)
}
