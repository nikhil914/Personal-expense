package app.ledgerly.engine

import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.ParseStatus
import app.ledgerly.core.model.ParsedTransaction
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import app.ledgerly.data.db.AccountEntity
import app.ledgerly.data.db.AuditActor
import app.ledgerly.data.db.AuditEventType
import app.ledgerly.data.db.LedgerlyDatabase
import app.ledgerly.data.db.ReviewState
import app.ledgerly.data.db.SourceEventEntity
import app.ledgerly.data.db.TransactionEventEntity
import app.ledgerly.data.mapper.SetCodec
import app.ledgerly.data.mapper.toModel
import app.ledgerly.data.repo.BudgetRepository
import app.ledgerly.data.repo.FxRateRepository
import app.ledgerly.data.repo.TransactionRepository
import app.ledgerly.data.repo.TripRepository
import app.ledgerly.data.settings.AppSettings
import app.ledgerly.data.settings.Retention
import app.ledgerly.data.settings.SettingsStore
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * raw event -> parser (+ optional on-device LLM fallback) -> dedup -> FX enrich -> categorise ->
 * save + audit -> post-ingest notifications.
 */
class IngestionPipeline(
    private val db: LedgerlyDatabase,
    private val engines: Engines,
    private val categoriser: Categoriser,
    private val transactions: TransactionRepository,
    private val rates: FxRateRepository,
    private val budgets: BudgetRepository,
    private val trips: TripRepository,
    private val settingsStore: SettingsStore,
    private val notifier: PostIngestNotifier,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    sealed interface Outcome {
        data class Created(val txnId: String, val reviewState: ReviewState) : Outcome
        data class Merged(val txnId: String) : Outcome
        data class Ignored(val status: ParseStatus) : Outcome
    }

    private val sourceDao = db.sourceEventDao()
    private val llmFallback = LlmParserFallback(engines.llm)

    /** Drains the queue. Returns the number of events that produced or updated a transaction. */
    suspend fun processPending(): Int {
        var count = 0
        val settings = settingsStore.current()
        val learned = loadTemplates()
        while (true) {
            val batch = sourceDao.pending(50)
            if (batch.isEmpty()) break
            for (e in batch) {
                val outcome = try {
                    processOne(e, settings, learned, notify = batch.size <= 3)
                } catch (t: Throwable) {
                    sourceDao.update(e.copy(processed = true, parseStatus = ParseStatus.FAILED, error = t.javaClass.simpleName + ": " + t.message))
                    null
                }
                if (outcome is Outcome.Created || outcome is Outcome.Merged) count++
            }
        }
        return count
    }

    suspend fun processOne(
        entity: SourceEventEntity,
        settings: AppSettings,
        learned: List<LearnedTemplate>,
        notify: Boolean,
    ): Outcome {
        val event = entity.toModel()
            ?: return markIgnored(entity, ParseStatus.FAILED, null, settings, "raw text purged")
        val sender = entity.sender?.trim()?.uppercase()
        if (sender != null && db.ignoredSenderDao().isIgnored(sender)) {
            return markIgnored(entity, ParseStatus.IGNORED_NOT_FINANCIAL, null, settings, "ignored sender")
        }

        var parsed = engines.parser.parse(event, learned)
        if (parsed.status == ParseStatus.FAILED || (parsed.status == ParseStatus.NEEDS_REVIEW && parsed.confidence < 0.5)) {
            llmFallback.extract(event)?.let { parsed = it }
        }
        val amount = parsed.amount
        if (parsed.status !in setOf(ParseStatus.PARSED, ParseStatus.NEEDS_REVIEW) || amount == null || amount.isZero) {
            return markIgnored(entity, if (amount == null && parsed.status == ParseStatus.PARSED) ParseStatus.FAILED else parsed.status, parsed, settings, null)
        }

        val base = settings.baseCurrency
        val account = resolveAccount(parsed, base)
        val candidate = Transaction(
            id = UUID.randomUUID().toString(),
            accountId = account?.id,
            type = typeFor(parsed),
            status = if (parsed.kind == MessageKind.CARD_AUTH) TxnStatus.PENDING else TxnStatus.POSTED,
            occurredAt = parsed.occurredAt,
            original = amount.abs(),
            account = (parsed.accountAmount ?: amount).abs(),
            base = amount.abs(),
            merchant = engines.parser.normaliseMerchant(parsed.merchantRaw),
            merchantRaw = parsed.merchantRaw,
            reference = parsed.reference,
            accountLast4 = parsed.accountLast4,
            sourceEventIds = listOf(event.id),
            sourceChannels = setOf(event.channel),
        )

        // ---- dedup (one payment, one entry)
        val window = Duration.ofDays(3)
        val recent = transactions.inRange(candidate.occurredAt.minus(window), candidate.occurredAt.plus(window))
        val decision = engines.deduper.decide(candidate, recent)
        if (decision is DedupDecision.Merge) {
            val existing = transactions.get(decision.existingId)
            if (existing != null) {
                val merged = engines.deduper.merge(existing, candidate)
                transactions.update(merged, AuditActor.DEDUP, note = "${event.channel} ${decision.reason}")
                linkEvent(entity, parsed, decision.existingId, settings)
                return Outcome.Merged(decision.existingId)
            }
        }

        // ---- FX enrich
        val accountCurrency = account?.currency?.let { CurrencyCode.of(it) } ?: candidate.account.currency
        val date = candidate.occurredAt.atZone(zone()).toLocalDate()
        val table = rates.table(listOf(candidate.original.currency, candidate.account.currency, accountCurrency, base), date)
        var txn = engines.fx.enrich(candidate, accountCurrency, base, table)

        // ---- categorise
        val category = categoriser.categorise(txn, event)
        if (category != null) {
            txn = txn.copy(categoryId = category.categoryId, merchant = category.merchantName ?: txn.merchant, tags = txn.tags + category.addTags)
        }

        // ---- trip tagging
        val activeTrips = trips.activeModels()
        activeTrips.firstOrNull { it.currency == txn.original.currency || (!date.isBefore(it.start) && (it.end == null || !date.isAfter(it.end))) }
            ?.let { txn = txn.copy(tripId = it.id) }

        // ---- save + audit
        val reviewState = when {
            decision is DedupDecision.PossibleDuplicate -> ReviewState.POSSIBLE_DUPLICATE
            parsed.status == ParseStatus.NEEDS_REVIEW -> ReviewState.NEEDS_REVIEW
            else -> ReviewState.OK
        }
        val extra = buildList {
            if (category != null) add(TransactionEventEntity(transactionId = txn.id, type = AuditEventType.CATEGORISED, field = "categoryId", newValue = category.categoryId, actor = if (category.source == "rule") AuditActor.RULE else AuditActor.PARSER, note = category.source))
            if (txn.fxFee != null || txn.marketRate != null) add(TransactionEventEntity(transactionId = txn.id, type = AuditEventType.FX_ENRICHED, field = "fx", newValue = "rate=${txn.marketRate} fee=${txn.fxFee}", actor = AuditActor.SYSTEM))
        }
        transactions.insertNew(
            txn,
            actor = AuditActor.PARSER,
            reviewState = reviewState,
            duplicateOfId = (decision as? DedupDecision.PossibleDuplicate)?.existingId,
            confidence = parsed.confidence,
            extraEvents = extra,
        )
        linkEvent(entity, parsed, txn.id, settings)

        if (notify) postIngest(txn, reviewState, category, settings, activeTrips.isEmpty())
        return Outcome.Created(txn.id, reviewState)
    }

    /** Called when the user starts a trip from a suggestion: tags the last week's txns in that currency. */
    suspend fun assignRecentToTrip(tripId: String, currency: CurrencyCode) {
        val now = Instant.now()
        transactions.inRange(now.minus(Duration.ofDays(7)), now.plus(Duration.ofDays(1)))
            .filter { it.original.currency == currency && it.tripId == null }
            .forEach { transactions.update(it.copy(tripId = tripId), AuditActor.SYSTEM, note = "trip mode") }
    }

    private suspend fun postIngest(txn: Transaction, reviewState: ReviewState, category: CategoryResult?, settings: AppSettings, noActiveTrip: Boolean) {
        val z = zone()
        val today = LocalDate.now(z)
        if (settings.postIngestPrompts && reviewState == ReviewState.OK && category != null && txn.type == TxnType.EXPENSE) {
            val name = db.categoryDao().getById(category.categoryId)?.name ?: category.categoryId
            notifier.categorised(txn, name)
        }
        if (settings.fxFeeNotices && txn.fxFee?.isPositive == true) notifier.fxFee(txn)
        if (settings.budgetAlerts) {
            val period = FinancialPeriod.current(today, settings.monthStartDay)
            budgets.crossingsFor(txn, period, z).forEach { notifier.budgetCrossed(it.label, it.threshold, it.spent, it.limit) }
        }
        if (settings.tripSuggestions && noActiveTrip) {
            val prior = db.transactionDao().countInCurrencySince(txn.original.currency.code, txn.occurredAt.minus(Duration.ofDays(60)), txn.id)
            engines.insights.suggestTrip(txn, settings.baseCurrency, emptyList(), prior, z)?.let { notifier.tripSuggestion(it) }
        }
        if (settings.cashGapPrompts) {
            if (txn.type == TxnType.ATM_WITHDRAWAL) {
                notifier.atmWithdrawal(txn)
            } else {
                val history = transactions.inRange(Instant.now().minus(Duration.ofDays(30)), Instant.now().plus(Duration.ofDays(1)))
                engines.insights.cashGap(history, db.accountDao().cashAccount()?.id, today, z)?.let { notifier.cashGap(it) }
            }
        }
    }

    private suspend fun linkEvent(entity: SourceEventEntity, parsed: ParsedTransaction, txnId: String, settings: AppSettings) {
        val discard = settings.retention == Retention.DISCARD_AFTER_PARSE
        sourceDao.update(
            entity.copy(
                processed = true,
                parseStatus = parsed.status,
                parserId = parsed.parserId,
                confidence = parsed.confidence,
                linkedTxnId = txnId,
                body = if (discard) null else entity.body,
                title = if (discard) null else entity.title,
                purgedAt = if (discard) Instant.now() else null,
                error = null,
            ),
        )
    }

    private suspend fun markIgnored(entity: SourceEventEntity, status: ParseStatus, parsed: ParsedTransaction?, settings: AppSettings, error: String?): Outcome {
        val discard = settings.retention == Retention.DISCARD_AFTER_PARSE &&
            status in setOf(ParseStatus.IGNORED_OTP, ParseStatus.IGNORED_PROMO, ParseStatus.IGNORED_NOT_FINANCIAL)
        sourceDao.update(
            entity.copy(
                processed = true,
                parseStatus = status,
                parserId = parsed?.parserId,
                confidence = parsed?.confidence,
                error = error,
                body = if (discard) null else entity.body,
                purgedAt = if (discard) Instant.now() else entity.purgedAt,
            ),
        )
        return Outcome.Ignored(status)
    }

    /** Matches the message's last-4 to an account; auto-creates one for a new card/account number. */
    private suspend fun resolveAccount(parsed: ParsedTransaction, base: CurrencyCode): AccountEntity? {
        val dao = db.accountDao()
        val last4 = parsed.accountLast4 ?: return null
        dao.getAll().firstOrNull { last4 in SetCodec.decode(it.last4) }?.let { return it }
        val isCard = parsed.kind in setOf(MessageKind.CARD_AUTH, MessageKind.CARD_SETTLEMENT) ||
            parsed.parserId.contains("card", ignoreCase = true)
        val currency = parsed.accountAmount?.currency ?: base
        val institution = parsed.institution?.substringAfter('-')?.take(12)
        val entity = AccountEntity(
            id = UUID.randomUUID().toString(),
            name = listOfNotNull(institution, "••$last4").joinToString(" "),
            type = if (isCard) AccountType.CREDIT_CARD else AccountType.BANK,
            currency = currency.code,
            institution = institution,
            last4 = SetCodec.encode(listOf(last4)),
        )
        dao.upsert(entity)
        return entity
    }

    private fun typeFor(p: ParsedTransaction): TxnType = when (p.kind) {
        MessageKind.ATM_WITHDRAWAL -> TxnType.ATM_WITHDRAWAL
        MessageKind.REFUND, MessageKind.REVERSAL -> TxnType.REFUND
        MessageKind.CREDIT, MessageKind.UPI_RECEIVED, MessageKind.SALARY -> TxnType.INCOME
        MessageKind.DEBIT, MessageKind.UPI_SENT, MessageKind.CARD_AUTH, MessageKind.CARD_SETTLEMENT,
        MessageKind.EMI, MessageKind.AUTOPAY_MANDATE, MessageKind.FX_MARKUP -> TxnType.EXPENSE
        else -> if (p.direction == TxnDirection.CREDIT) TxnType.INCOME else TxnType.EXPENSE
    }

    private suspend fun loadTemplates(): List<LearnedTemplate> = db.parserTemplateDao().enabled().map { t ->
        val map = runCatching {
            val o = JSONObject(t.fieldMap)
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
        LearnedTemplate(t.id, t.senderPattern, t.packageName, t.regex, map)
    }
}
