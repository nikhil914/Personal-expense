package app.ledgerly.core.parser

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.ParseStatus
import app.ledgerly.core.model.ParsedTransaction
import app.ledgerly.core.model.SourceEvent

/** Detailed parse output: the model-level transaction plus parser diagnostics. */
data class ParseResult(
    val transaction: ParsedTransaction,
    val classification: Classification?,
    val sender: SenderMatch?,
    val merchant: NormalizedMerchant?,
    /** Text actually parsed (normalised, title + body). */
    val text: String,
    /** Which stages ran, e.g. ["sender", "classifier", "template:hdfc_card_spend"]. */
    val trace: List<String>,
)

/**
 * Facade: sender allow-list -> classifier -> templates -> LLM (optional, when confidence is low)
 * -> heuristic -> status. Never throws.
 *
 * Status rules:
 * - OTP / promo -> IGNORED_OTP / IGNORED_PROMO; non-financial or disallowed source -> IGNORED_NOT_FINANCIAL
 * - confidence >= [ParserConfig.parsedThreshold]: PARSED for ledger kinds, INFO_ONLY for
 *   informational kinds (bill due, statement, balance, mandate, failed transaction)
 * - otherwise NEEDS_REVIEW; unexpected internal errors -> FAILED
 */
class TransactionParser(
    val config: ParserConfig = ParserConfig(),
    val senderRegistry: SenderRegistry = SenderRegistry(),
    val templateEngine: TemplateEngine = TemplateEngine(config = config),
    val classifier: MessageClassifier = MessageClassifier(),
    val heuristic: HeuristicExtractor = HeuristicExtractor(config),
    val llm: LlmExtractor? = null,
    val merchantNormalizer: MerchantNormalizer = MerchantNormalizer(),
) {

    fun parse(event: SourceEvent): ParsedTransaction = parseDetailed(event).transaction

    fun parseDetailed(event: SourceEvent): ParseResult = try {
        parseInternal(event)
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        val text = runCatching { TextNormalizer.combine(event.title, event.body) }.getOrDefault("")
        ParseResult(empty(event, ParseStatus.FAILED, MessageKind.UNKNOWN, "error"), null, null, null, text, listOf("error:${e.javaClass.simpleName}"))
    }

    private fun empty(event: SourceEvent, status: ParseStatus, kind: MessageKind, parserId: String, institution: String? = null) =
        ParsedTransaction(
            sourceEventId = event.id, channel = event.channel, kind = kind, direction = null, amount = null,
            institution = institution, occurredAt = event.receivedAt, confidence = 0.0, parserId = parserId, status = status,
        )

    private fun parseInternal(event: SourceEvent): ParseResult {
        val trace = mutableListOf<String>()
        val text = TextNormalizer.combine(event.title, event.body)
        if (text.isBlank()) {
            return ParseResult(empty(event, ParseStatus.IGNORED_NOT_FINANCIAL, MessageKind.UNKNOWN, "empty"), null, null, null, text, listOf("empty"))
        }

        // 1. Sender / package allow-list
        val sender = senderRegistry.resolve(event)
        trace += "sender:${sender.key ?: "-"}:${if (sender.allowed) "allowed" else "unknown"}"
        var confidenceCap = 1.0
        if (!sender.allowed && needsSenderCheck(event)) {
            val policy = if (event.packageName != null) config.unknownPackagePolicy else config.unknownSmsSenderPolicy
            when (policy) {
                UnknownSenderPolicy.IGNORE -> return ParseResult(
                    empty(event, ParseStatus.IGNORED_NOT_FINANCIAL, MessageKind.UNKNOWN, "sender-filter"), null, sender, null, text, trace + "ignored:sender",
                )
                UnknownSenderPolicy.REVIEW -> confidenceCap = config.unknownSenderConfidenceCap
            }
        }

        // 2. Classifier
        val cls = classifier.classify(text)
        trace += "classifier:${cls.kind}"
        when (cls.kind) {
            MessageKind.OTP -> return ParseResult(empty(event, ParseStatus.IGNORED_OTP, MessageKind.OTP, "classifier", sender.institution), cls, sender, null, text, trace)
            MessageKind.PROMO -> return ParseResult(empty(event, ParseStatus.IGNORED_PROMO, MessageKind.PROMO, "classifier", sender.institution), cls, sender, null, text, trace)
            else -> {}
        }

        // 3. Templates
        val tm = templateEngine.match(text, sender, event.receivedAt, cls, event.channel)
        var best: Extraction? = tm?.extraction
        if (tm != null) trace += "template:${tm.template.id}"

        if (best == null && cls.kind == MessageKind.UNKNOWN && AmountParser.findAll(text, config.dollarCurrency).isEmpty()) {
            return ParseResult(empty(event, ParseStatus.IGNORED_NOT_FINANCIAL, MessageKind.UNKNOWN, "classifier", sender.institution), cls, sender, null, text, trace + "not-financial")
        }

        // 4. LLM fallback
        val llm = this.llm
        if (llm != null && (best == null || best.confidence < config.llmThreshold)) {
            val ml = runCatching { llm.extract(LlmRequest(text, event.receivedAt, sender.institution, cls)) }.getOrNull()
            trace += "llm:${if (ml != null) "ok" else "none"}"
            if (ml != null) {
                // OTP/promo verdicts from the model are respected only if they agree with no amount being booked.
                best = when {
                    best == null -> ml
                    ml.confidence > best.confidence -> ml.fillFrom(best)
                    else -> best.fillFrom(ml)
                }
            }
        }

        // 5. Heuristic
        val heur = heuristic.extract(text, event.receivedAt, cls, sender.institution)
        trace += "heuristic:${"%.2f".format(heur.confidence)}"
        best = when {
            best == null -> heur
            heur.confidence > best.confidence && best.amount == null -> heur.fillFrom(best)
            else -> best.fillFrom(heur.takeIf { compatible(best!!, it) })
        }
        var result: Extraction = best

        if (result.kind == MessageKind.OTP || result.kind == MessageKind.PROMO) {
            val st = if (result.kind == MessageKind.OTP) ParseStatus.IGNORED_OTP else ParseStatus.IGNORED_PROMO
            return ParseResult(empty(event, st, result.kind, result.parserId, sender.institution), cls, sender, null, text, trace)
        }
        if (result.kind == MessageKind.UNKNOWN && result.direction != null) {
            result = result.copy(kind = if (result.direction == app.ledgerly.core.model.TxnDirection.DEBIT) MessageKind.DEBIT else MessageKind.CREDIT)
        }

        // 6. Status
        val confidence = minOf(result.confidence, confidenceCap).coerceIn(0.0, 1.0)
        val institution = result.institution ?: sender.institution ?: SenderRegistry.institutionFromText(text)
        val status = when {
            confidence < config.parsedThreshold -> ParseStatus.NEEDS_REVIEW
            result.kind in Kinds.INFO -> ParseStatus.INFO_ONLY
            result.kind in Kinds.LEDGER && result.amount == null -> ParseStatus.NEEDS_REVIEW
            result.kind == MessageKind.UNKNOWN -> ParseStatus.NEEDS_REVIEW
            else -> ParseStatus.PARSED
        }
        val txn = ParsedTransaction(
            sourceEventId = event.id,
            channel = event.channel,
            kind = result.kind,
            direction = result.direction ?: Kinds.defaultDirection(result.kind),
            amount = result.amount,
            accountAmount = result.accountAmount,
            accountLast4 = result.accountLast4,
            institution = institution,
            merchantRaw = result.merchantRaw,
            reference = result.reference,
            balanceAfter = result.balanceAfter,
            occurredAt = result.occurredAt ?: event.receivedAt,
            dueDate = result.dueDate,
            minimumDue = result.minimumDue,
            confidence = confidence,
            parserId = result.parserId,
            status = status,
        )
        val merchant = merchantNormalizer.normalize(result.merchantRaw)
        return ParseResult(txn, cls, sender, merchant, text, trace)
    }

    /** Heuristic fields are merged only when they don't contradict the primary amount. */
    private fun compatible(primary: Extraction, other: Extraction): Boolean =
        primary.amount == null || other.amount == null || primary.amount == other.amount || primary.amount.currency != other.amount.currency

    private fun needsSenderCheck(event: SourceEvent): Boolean = when (event.channel) {
        Channel.SMS, Channel.NOTIFICATION, Channel.EMAIL -> true
        else -> false
    }

    companion object {
        /** A parser with built-in templates and default configuration. */
        fun default(llm: LlmExtractor? = null): TransactionParser = TransactionParser(llm = llm)
    }
}
