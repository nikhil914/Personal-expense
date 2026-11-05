package app.ledgerly.engine

import app.ledgerly.core.model.Category
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.ParsedTransaction
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import app.ledgerly.data.db.AuditEventType
import app.ledgerly.data.db.Cadence
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.ZoneId

/*
 * Adapter contracts between the Android app and the pure-Kotlin engine modules (core:parser, core:fx,
 * core:dedup, core:audit, core:budget, core:insights). The app depends only on these interfaces.
 * Today each one is backed by a small in-app fallback (engine/fallback/); wiring the real engines is a
 * change confined to EngineFactory.create() plus one adapter class per module.
 *
 * Search for "TODO(core-" to find every wiring point.
 */

// ---------------------------------------------------------------- core:parser

/** A user-learned template (from ParserTemplateEntity), passed to the parser's template engine. */
data class LearnedTemplate(
    val id: String,
    val senderPattern: String?,
    val packageName: String?,
    /** Java regex with named groups: amount, currency, merchant, last4, ref, balance. */
    val regex: String,
    val fieldMap: Map<String, String>,
)

/** TODO(core-parser): adapt app.ledgerly.core.parser (classifier + bank templates + heuristic fallback). */
interface TransactionParser {
    /** Never throws; unparseable input yields status FAILED / IGNORED_*. */
    fun parse(event: SourceEvent, learned: List<LearnedTemplate> = emptyList()): ParsedTransaction

    /** Canonical merchant name for display ("ZOMATO*ORDER 1234" -> "Zomato"). */
    fun normaliseMerchant(raw: String?): String?
}

// ---------------------------------------------------------------- core:dedup

sealed interface DedupDecision {
    data object New : DedupDecision
    /** Same payment seen on another channel: merge into [existingId] (one payment, one entry). */
    data class Merge(val existingId: String, val confidence: Double, val reason: String) : DedupDecision
    /** Looks similar but not certain: save, flag for the review inbox (Merge / Keep both). */
    data class PossibleDuplicate(val existingId: String, val confidence: Double, val reason: String) : DedupDecision
}

/** TODO(core-dedup): adapt app.ledgerly.core.dedup (cross-channel merge, transfer/refund linking, auth->settlement). */
interface Deduper {
    fun decide(candidate: Transaction, recent: List<Transaction>): DedupDecision

    /** Field-level merge when [DedupDecision.Merge] is chosen. */
    fun merge(existing: Transaction, incoming: Transaction): Transaction
}

// ---------------------------------------------------------------- core:fx

data class RateQuote(val rate: BigDecimal, val date: LocalDate, val source: String)

/** Pre-fetched reference rates for the currencies a transaction touches (keeps FxService pure/sync). */
class RateTable(private val quotes: Map<Pair<String, String>, RateQuote>) {
    fun get(from: CurrencyCode, to: CurrencyCode): RateQuote? {
        if (from == to) return null
        quotes[from.code to to.code]?.let { return it }
        val inverse = quotes[to.code to from.code] ?: return null
        if (inverse.rate.signum() == 0) return null
        return inverse.copy(rate = BigDecimal.ONE.divide(inverse.rate, MathContext.DECIMAL64))
    }

    companion object {
        val EMPTY = RateTable(emptyMap())
    }
}

/** TODO(core-fx): adapt app.ledgerly.core.fx (MultiCurrencyEnricher, FX fee exposer, cash pools, trip detection). */
interface FxService {
    /**
     * Fills account/base amounts, fxRateApplied, marketRate, rateSource, rateDate and fxFee.
     * [accountCurrency] is the resolved account's currency (may differ from what the message showed).
     */
    fun enrich(txn: Transaction, accountCurrency: CurrencyCode, baseCurrency: CurrencyCode, rates: RateTable): Transaction
}

// ---------------------------------------------------------------- core:audit

data class AuditChange(val type: AuditEventType, val field: String?, val oldValue: String?, val newValue: String?)

/** TODO(core-audit): adapt app.ledgerly.core.audit (TransactionDiffer / AuditLog). */
interface AuditService {
    /** before == null means creation. Returns an empty list when nothing changed. */
    fun diff(before: Transaction?, after: Transaction): List<AuditChange>
}

// ---------------------------------------------------------------- core:budget

data class SafeToSpendInput(
    val today: LocalDate,
    val periodStart: LocalDate,
    /** Exclusive. */
    val periodEnd: LocalDate,
    val budgetTotal: Money?,
    /** Base-currency expenses in the period before today. */
    val spentBeforeToday: Money,
    val spentToday: Money,
    /** Bills / subscriptions still due before [periodEnd]. */
    val upcomingCommitted: Money,
)

data class SafeToSpend(val today: Money, val remainingInPeriod: Money, val daysLeft: Int)

/** TODO(core-budget): adapt app.ledgerly.core.budget (envelopes, 50/30/20, rollover, safe-to-spend). */
interface BudgetService {
    fun safeToSpend(input: SafeToSpendInput): SafeToSpend?

    /** Highest alert threshold (percent, e.g. 80 or 100) crossed by moving from [before] to [after], else null. */
    fun crossedThreshold(limit: Money, before: Money, after: Money): Int?
}

// ---------------------------------------------------------------- core:insights

data class RecurringCandidate(
    val name: String,
    val merchant: String?,
    val categoryId: String?,
    val amount: Money,
    val cadence: Cadence,
    val intervalDays: Int,
    val lastSeen: LocalDate,
    val nextDue: LocalDate,
    val occurrences: Int,
    val isSubscription: Boolean,
)

data class CashGapPrompt(val withdrawalTxnId: String, val withdrawn: Money, val unaccounted: Money)

data class TripSuggestion(val currency: CurrencyCode, val firstSeen: LocalDate, val suggestedName: String)

/** TODO(core-insights): adapt app.ledgerly.core.insights (RecurringDetector, forecast, ATM cash gap). */
interface InsightsService {
    fun detectRecurring(history: List<Transaction>, today: LocalDate, zone: ZoneId): List<RecurringCandidate>

    fun cashGap(history: List<Transaction>, cashAccountId: String?, today: LocalDate, zone: ZoneId): CashGapPrompt?

    fun suggestTrip(txn: Transaction, baseCurrency: CurrencyCode, activeTrips: List<Trip>, priorCountInCurrency: Int, zone: ZoneId): TripSuggestion?
}

// ---------------------------------------------------------------- chat with your money

data class AskContext(
    val today: LocalDate,
    val zone: ZoneId,
    val baseCurrency: CurrencyCode,
    val monthStartDay: Int,
    val transactions: List<Transaction>,
    val categories: List<Category>,
)

data class AskAnswer(val text: String, val transactionIds: List<String> = emptyList(), val usedLlm: Boolean = false)

/** TODO(core-insights): adapt the on-device natural-language query engine from core:insights. */
interface AskService {
    suspend fun ask(question: String, context: AskContext): AskAnswer
}

/** Optional on-device LLM (Gemini Nano). Every caller must cope with null / unavailable. */
interface LlmBackend {
    suspend fun isAvailable(): Boolean
    suspend fun generate(prompt: String): String?
}

// ---------------------------------------------------------------- app-side categorisation

data class CategoryResult(
    val categoryId: String,
    val merchantName: String? = null,
    val addTags: Set<String> = emptySet(),
    val ruleId: Long? = null,
    /** "rule", "merchant", "type-default", "learned". */
    val source: String,
)

interface Categoriser {
    suspend fun categorise(txn: Transaction, event: SourceEvent?): CategoryResult?
}

/** Bundle handed to the pipeline / repositories / UI. */
data class Engines(
    val parser: TransactionParser,
    val deduper: Deduper,
    val fx: FxService,
    val audit: AuditService,
    val budget: BudgetService,
    val insights: InsightsService,
    val ask: AskService,
    val llm: LlmBackend,
)
