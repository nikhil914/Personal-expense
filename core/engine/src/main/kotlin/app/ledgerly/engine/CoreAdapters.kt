package app.ledgerly.engine

import app.ledgerly.core.dedup.Candidate
import app.ledgerly.core.dedup.DedupEngine
import app.ledgerly.core.dedup.MatchResult
import app.ledgerly.core.dedup.Merger
import app.ledgerly.core.insights.AskEngine
import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.ParseStatus
import app.ledgerly.core.model.ParsedTransaction
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnType
import app.ledgerly.core.parser.MerchantNormalizer
import app.ledgerly.engine.fallback.FallbackParser
import java.time.Clock
import app.ledgerly.core.parser.TransactionParser as CoreParser

/** core:parser behind the app contract. User-learned templates still go through the fallback matcher. */
class CoreParserAdapter(
    private val core: CoreParser = CoreParser(),
    private val normalizer: MerchantNormalizer = MerchantNormalizer(),
    private val learnedMatcher: TransactionParser = FallbackParser(),
) : TransactionParser {

    override fun parse(event: SourceEvent, learned: List<LearnedTemplate>): ParsedTransaction {
        val result = core.parse(event)
        if (learned.isEmpty() || result.status == ParseStatus.PARSED || result.status == ParseStatus.INFO_ONLY) return result
        val viaLearned = learnedMatcher.parse(event, learned)
        return if (viaLearned.status == ParseStatus.PARSED) viaLearned else result
    }

    override fun normaliseMerchant(raw: String?): String? = normalizer.normalize(raw)?.name ?: raw
}

/** core:dedup ("one payment, one entry") behind the app contract. */
class CoreDeduperAdapter(private val engine: DedupEngine = DedupEngine()) : Deduper {

    override fun decide(candidate: Transaction, recent: List<Transaction>): DedupDecision =
        when (val r = engine.match(candidate.toCandidate(), recent.filter { it.id != candidate.id && !it.isDeleted })) {
            MatchResult.NewTransaction -> DedupDecision.New
            is MatchResult.MergeInto -> DedupDecision.Merge(r.txnId, r.confidence, r.reasons.joinToString())
            is MatchResult.SettleAuth -> DedupDecision.Merge(r.txnId, r.confidence, "auth settled: " + r.reasons.joinToString())
            is MatchResult.PossibleDuplicate -> DedupDecision.PossibleDuplicate(r.txnId, r.confidence, r.reasons.joinToString())
        }

    override fun merge(existing: Transaction, incoming: Transaction): Transaction {
        val merged = Merger.merge(existing, incoming.toCandidate()).transaction
        // Candidate carries only one source event; keep every source of the incoming entry.
        return merged.copy(
            sourceEventIds = (merged.sourceEventIds + incoming.sourceEventIds).distinct(),
            sourceChannels = merged.sourceChannels + incoming.sourceChannels,
        )
    }
}

internal fun Transaction.toCandidate(): Candidate {
    val (kind, direction) = when (type) {
        TxnType.EXPENSE -> MessageKind.DEBIT to TxnDirection.DEBIT
        TxnType.INCOME -> MessageKind.CREDIT to TxnDirection.CREDIT
        TxnType.REFUND -> MessageKind.REFUND to TxnDirection.CREDIT
        TxnType.ATM_WITHDRAWAL -> MessageKind.ATM_WITHDRAWAL to TxnDirection.DEBIT
        TxnType.TRANSFER, TxnType.ADJUSTMENT ->
            MessageKind.UNKNOWN to (if (original.isNegative) TxnDirection.DEBIT else TxnDirection.CREDIT)
    }
    return Candidate(
        sourceEventId = sourceEventIds.firstOrNull() ?: id,
        channel = sourceChannels.firstOrNull() ?: Channel.MANUAL,
        kind = kind,
        direction = direction,
        amount = original.abs(),
        accountAmount = account.takeIf { it.currency != original.currency }?.abs(),
        accountId = accountId,
        accountLast4 = accountLast4,
        merchantRaw = merchantRaw ?: merchant,
        reference = reference,
        occurredAt = occurredAt,
    )
}

/** core:insights AskEngine (on-device rule parser). Falls back to [fallback] when it can't answer. */
class CoreAskAdapter(private val fallback: AskService) : AskService {
    override suspend fun ask(question: String, context: AskContext): AskAnswer {
        val engine = AskEngine(clock = Clock.system(context.zone), categories = context.categories)
        val answer = runCatching { engine.ask(question, context.transactions) }.getOrNull()
            ?: return fallback.ask(question, context)
        return AskAnswer(answer.text, answer.rows.mapNotNull { it.transactionId })
    }
}
