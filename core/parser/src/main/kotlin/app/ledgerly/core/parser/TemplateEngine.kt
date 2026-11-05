package app.ledgerly.core.parser

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.MessageKind
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A successful template match. */
data class TemplateMatch(val template: ParserTemplate, val extraction: Extraction, val matchedText: String)

/**
 * Applies [ParserTemplate]s to message text. Templates can be added/replaced at runtime (remote
 * updates, learned templates). Invalid regexes are rejected on [add] and skipped on load.
 */
class TemplateEngine(
    templates: List<ParserTemplate> = BuiltInTemplates.library.templates,
    private val config: ParserConfig = ParserConfig(),
) {
    private val list = CopyOnWriteArrayList<ParserTemplate>()
    private val compiled = ConcurrentHashMap<String, Regex>()
    private val dates = DateResolver(config.zone, config.dayFirstDates)

    @Volatile
    var libraryVersion: Int = BuiltInTemplates.library.version
        private set

    init {
        templates.forEach { runCatching { add(it) } }
    }

    fun templates(): List<ParserTemplate> = list.toList()

    /** Adds or replaces (same id) a template. Throws IllegalArgumentException on an invalid regex. */
    fun add(template: ParserTemplate) {
        val regex = compile(template)
        compiled[template.id] = regex
        list.removeIf { it.id == template.id }
        list.add(template)
    }

    fun remove(id: String): Boolean {
        compiled.remove(id)
        return list.removeIf { it.id == id }
    }

    /**
     * Replaces all non-learned templates with [library] if its version is newer. Learned templates
     * are kept. Returns true when applied.
     */
    fun applyLibrary(library: TemplateLibrary, force: Boolean = false): Boolean {
        if (!force && library.version <= libraryVersion) return false
        val learned = list.filter { it.learned }
        list.clear(); compiled.clear()
        (library.templates + learned).forEach { runCatching { add(it) } }
        libraryVersion = library.version
        return true
    }

    fun exportLibrary(): TemplateLibrary = TemplateLibrary(libraryVersion, templates())

    /**
     * Finds the best matching template for [text] (already normalised / title-combined).
     * @param classification used when a template leaves kind open, and to override plain
     *   debit/credit kinds with refund / reversal / failure / salary signals.
     */
    fun match(
        text: String,
        sender: SenderMatch?,
        receivedAt: Instant,
        classification: Classification? = null,
        channel: Channel? = null,
    ): TemplateMatch? {
        var best: TemplateMatch? = null
        var bestScore = -1.0
        for (t in list) {
            if (t.channels.isNotEmpty() && channel != null && channel !in t.channels) continue
            val senderFit = senderFit(t, sender)
            if (senderFit < 0) continue
            val regex = compiled[t.id] ?: continue
            val m = runCatching { regex.find(text) }.getOrNull() ?: continue
            val ex = runCatching { extract(t, m, receivedAt, classification, senderFit) }.getOrNull() ?: continue
            val filled = listOfNotNull(ex.amount, ex.accountLast4, ex.merchantRaw, ex.reference, ex.balanceAfter, ex.occurredAt, ex.dueDate).size
            val score = ex.confidence + filled * 0.01 + (if (senderFit > 0) 0.02 else 0.0) + m.value.length / 100000.0
            if (score > bestScore) {
                bestScore = score
                best = TemplateMatch(t, ex, m.value)
            }
        }
        return best
    }

    /** 1 = sender matched an explicit pattern, 0 = template is sender-agnostic or sender unknown, -1 = mismatch. */
    private fun senderFit(t: ParserTemplate, sender: SenderMatch?): Int {
        if (t.senders.isEmpty()) return 0
        if (sender == null || (sender.key == null && sender.institution == null)) return 0
        val targets = listOfNotNull(sender.key, sender.institution)
        val hit = t.senders.any { p ->
            val r = runCatching { Regex(p, RegexOption.IGNORE_CASE) }.getOrNull()
            r != null && targets.any { r.containsMatchIn(it) }
        }
        return when {
            hit -> 1
            sender.aggregator -> 0
            else -> -1
        }
    }

    private fun group(m: MatchResult, name: String): String? =
        runCatching { m.groups[name]?.value }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun extract(t: ParserTemplate, m: MatchResult, receivedAt: Instant, cls: Classification?, senderFit: Int): Extraction {
        val defaultCur = t.currency?.let { runCatching { CurrencyCode.of(it) }.getOrNull() } ?: config.defaultCurrency
        val currencyToken = group(m, "currency")
        val amount = AmountParser.toMoney(group(m, "amount"), currencyToken, defaultCur, config.dollarCurrency)
        val amountCur = amount?.currency ?: defaultCur
        val accountAmount = AmountParser.toMoney(group(m, "accountAmount"), group(m, "acur"), config.defaultCurrency, config.dollarCurrency)
            ?.takeIf { amount == null || it.currency != amount.currency }
        val balance = AmountParser.toMoney(group(m, "balance"), group(m, "balcur"), amountCur, config.dollarCurrency)
        val minDue = AmountParser.toMoney(group(m, "minDue"), group(m, "mincur"), amountCur, config.dollarCurrency)
        val dateHit = dates.parse(group(m, "date"), receivedAt)
        val dueHit = dates.parse(group(m, "dueDate"), receivedAt, preferFuture = true)

        val kind = resolveKind(t.kind, cls)
        val direction = t.direction ?: Kinds.defaultDirection(kind) ?: cls?.direction
        var confidence = t.confidence
        if (amount == null && kind in Kinds.LEDGER) confidence -= 0.25
        if (t.senders.isNotEmpty() && senderFit == 0) confidence -= 0.03
        if (kind == MessageKind.UNKNOWN) confidence -= 0.2

        return Extraction(
            kind = kind,
            direction = direction,
            amount = amount,
            accountAmount = accountAmount,
            accountLast4 = group(m, "last4"),
            merchantRaw = cleanMerchant(group(m, "merchant")),
            reference = group(m, "ref"),
            balanceAfter = balance,
            occurredAt = dateHit?.let { dates.toInstant(it, receivedAt) },
            dueDate = dueHit?.date,
            minimumDue = minDue,
            institution = t.institution,
            confidence = confidence.coerceIn(0.0, 1.0),
            parserId = t.id,
        )
    }

    companion object {
        private val OVERRIDABLE = setOf(MessageKind.DEBIT, MessageKind.CREDIT, MessageKind.UPI_SENT, MessageKind.UPI_RECEIVED)
        private val OVERRIDES = setOf(MessageKind.FAILED_TXN, MessageKind.REVERSAL, MessageKind.REFUND, MessageKind.SALARY)

        internal fun resolveKind(templateKind: MessageKind?, cls: Classification?): MessageKind {
            val c = cls?.kind
            if (templateKind == null) return c?.takeIf { it != MessageKind.UNKNOWN && it != MessageKind.OTP && it != MessageKind.PROMO } ?: MessageKind.UNKNOWN
            if (templateKind in OVERRIDABLE && c in OVERRIDES) return c!!
            return templateKind
        }

        private val TRAILING_JUNK = Regex("[\\s.,;:|\\-*/#]+$|[^\\p{L}\\p{N})&'.]+$")

        /** Light clean-up of a raw merchant span (trailing punctuation/emoji, "VPA" prefix). */
        fun cleanMerchant(raw: String?): String? {
            var s = raw?.trim() ?: return null
            s = s.removePrefix("VPA").removePrefix("vpa").trim()
            repeat(3) { s = TRAILING_JUNK.replace(s, "").trim() }
            s = s.trimStart('-', ':', '*', '/', ' ', '+')
            if (s.length > 80) s = s.take(80).trim()
            return s.takeIf { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } }
        }
    }

    private fun compile(t: ParserTemplate): Regex {
        try {
            return Regex(TemplateMacros.expand(t.pattern), RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid pattern for template ${t.id}: ${e.message}", e)
        }
    }
}
