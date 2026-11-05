package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.TxnDirection
import java.time.Instant

/** What the user confirmed for a NEEDS_REVIEW message. */
data class UserCorrection(
    val amount: Money,
    val merchant: String?,
    val kind: MessageKind,
    val direction: TxnDirection? = null,
    val last4: String? = null,
    val reference: String? = null,
)

sealed class LearnResult {
    data class Learned(val template: ParserTemplate) : LearnResult()
    data class Failed(val reason: String) : LearnResult()
}

/**
 * Derives a new [ParserTemplate] from a corrected message: literal amount / last4 / merchant /
 * reference / date / balance spans become named groups, the rest is escaped with digits
 * generalised, and the result must re-parse the sample to the corrected values.
 */
class TemplateLearner(
    private val config: ParserConfig = ParserConfig(),
    private val senderRegistry: SenderRegistry = SenderRegistry(),
) {
    private val heuristic = HeuristicExtractor(config)
    private val dates = DateResolver(config.zone, config.dayFirstDates)

    private data class Span(val start: Int, val end: Int, val regex: String, val priority: Int) // end exclusive

    fun learn(event: SourceEvent, correction: UserCorrection): LearnResult = try {
        val sender = senderRegistry.resolve(event)
        learn(TextNormalizer.combine(event.title, event.body), correction, sender, event.receivedAt)
    } catch (e: Exception) {
        LearnResult.Failed("error: ${e.message}")
    }

    fun learn(rawText: String, correction: UserCorrection, sender: SenderMatch?, receivedAt: Instant): LearnResult = try {
        learnInternal(TextNormalizer.normalize(rawText), correction, sender, receivedAt)
    } catch (e: Exception) {
        LearnResult.Failed("error: ${e.message}")
    }

    private fun learnInternal(text: String, c: UserCorrection, sender: SenderMatch?, receivedAt: Instant): LearnResult {
        if (text.isEmpty()) return LearnResult.Failed("empty message")
        val spans = mutableListOf<Span>()

        // Amount (+ adjacent currency marker).
        val target = c.amount.toDecimal()
        val numberRx = Regex("(?<![\\d,]|\\d\\.)${AmountParser.NUMBER}")
        val candidates = numberRx.findAll(text).filter { m -> AmountParser.parseNumber(m.value)?.compareTo(target) == 0 }.toList()
        if (candidates.isEmpty()) return LearnResult.Failed("amount ${target.toPlainString()} not found in message")
        val prefixRx = Regex("(?<![A-Za-z])(${Currencies.PREFIX_TOKEN})\\s*$")
        val suffixRx = Regex("^\\s?(${Currencies.SUFFIX_TOKEN})(?![A-Za-z])")
        val withCur = candidates.map { m ->
            val before = text.substring(maxOf(0, m.range.first - 6), m.range.first)
            val p = prefixRx.find(before)
            val s = suffixRx.find(text.substring(m.range.last + 1))
            Triple(m, p?.let { m.range.first - (before.length - it.range.first) }, s?.let { m.range.last + 1 + it.range.last + 1 })
        }
        val chosen = withCur.firstOrNull { it.second != null || it.third != null } ?: withCur.first()
        val (am, curStart, curEnd) = chosen
        when {
            curStart != null -> spans += Span(curStart, am.range.last + 1, "(?<currency>${Currencies.PREFIX_TOKEN})\\s*(?<amount>${AmountParser.NUMBER})", 0)
            curEnd != null -> spans += Span(am.range.first, curEnd, "(?<amount>${AmountParser.NUMBER})\\s?(?<currency>${Currencies.SUFFIX_TOKEN})", 0)
            else -> spans += Span(am.range.first, am.range.last + 1, "(?<amount>${AmountParser.NUMBER})", 0)
        }

        val h = heuristic.extract(text, receivedAt, Classification(c.kind, c.direction, 0.9), sender?.institution)

        // last4
        val last4 = c.last4 ?: h.accountLast4
        if (last4 != null) {
            Regex("(?<!\\d)\\d*?(${Regex.escape(last4)})(?!\\d)").findAll(text)
                .firstOrNull { m -> spans.none { overlaps(it, m.range.first, m.range.last + 1) } }
                ?.let { spans += Span(it.range.first, it.range.last + 1, "\\d*?(?<last4>\\d{3,4})(?!\\d)", 1) }
        }

        // merchant
        var merchantLearned = false
        c.merchant?.trim()?.takeIf { it.isNotEmpty() }?.let { m ->
            var idx = text.indexOf(m, ignoreCase = true)
            var len = m.length
            if (idx < 0 && h.merchantRaw != null) {
                // User typed a normalised name ("Swiggy") for a raw span the heuristic found ("SWIGGY*ORDER").
                val norm = MerchantNormalizer().normalize(h.merchantRaw)
                if (norm != null && norm.name.equals(m, ignoreCase = true)) {
                    idx = text.indexOf(h.merchantRaw, ignoreCase = true); len = h.merchantRaw.length
                }
            }
            if (idx >= 0 && spans.none { overlaps(it, idx, idx + len) }) {
                spans += Span(idx, idx + len, "(?<merchant>.+?)", 2)
                merchantLearned = true
            }
        }

        // reference
        (c.reference ?: h.reference)?.let { ref ->
            val idx = text.indexOf(ref)
            if (idx >= 0 && spans.none { overlaps(it, idx, idx + ref.length) }) {
                spans += Span(idx, idx + ref.length, "(?<ref>[A-Za-z0-9]*\\d[A-Za-z0-9]*)", 3)
            }
        }

        // date (shape-preserving)
        dates.findFirst(text, receivedAt)?.let { d ->
            val s = d.range.first
            val e = d.range.last + 1
            if (spans.none { overlaps(it, s, e) }) spans += Span(s, e, "(?<date>${shape(text.substring(s, e))})", 4)
        }

        // balance
        h.balanceAfter?.let { bal ->
            AmountParser.findAll(text, config.dollarCurrency).firstOrNull { it.money == bal && spans.none { s -> overlaps(s, it.range.first, it.range.last + 1) } }
                ?.let { hit ->
                    spans += Span(hit.range.first, hit.range.last + 1, "(?<balcur>${Currencies.PREFIX_TOKEN})?\\s*(?<balance>${AmountParser.NUMBER})(?:\\s?${Currencies.SUFFIX_TOKEN})?", 5)
                }
        }

        val ordered = spans.sortedBy { it.start }
        val sb = StringBuilder()
        val first = ordered.first()
        val prefixStart = wordStart(text, maxOf(0, first.start - 30))
        sb.append(literal(text.substring(prefixStart, first.start)))
        for ((i, span) in ordered.withIndex()) {
            sb.append(span.regex)
            val next = ordered.getOrNull(i + 1)
            if (next != null) {
                sb.append(literal(text.substring(span.end, next.start)))
            } else {
                var tailEnd = minOf(text.length, span.end + 20)
                tailEnd = wordEnd(text, tailEnd)
                val tail = text.substring(span.end, tailEnd)
                if (tail.isNotBlank()) sb.append(literal(tail))
                else if (span.regex.startsWith("(?<merchant>")) sb.append("(?=\\s*(?:[.!|,;]|$))")
            }
        }
        val pattern = sb.toString()
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrElse { return LearnResult.Failed("invalid regex: ${it.message}") }
        if (regex.find(text) == null) return LearnResult.Failed("derived pattern does not match sample")

        val senders = listOfNotNull(sender?.key?.takeIf { it.isNotBlank() && sender.aggregator.not() }?.let { Regex.escape(it) })
        val id = "learned_" + Integer.toHexString((senders.joinToString() + pattern).hashCode())
        val template = ParserTemplate(
            id = id,
            institution = sender?.institution,
            pattern = pattern,
            senders = senders,
            kind = c.kind,
            direction = c.direction ?: Kinds.defaultDirection(c.kind),
            currency = c.amount.currency.code,
            confidence = 0.9,
            learned = true,
            sample = text,
        )

        // Validate: the new template alone must reproduce the corrected values.
        val engine = TemplateEngine(emptyList(), config)
        engine.add(template)
        val match = engine.match(text, sender, receivedAt, Classification(c.kind, c.direction, 0.9))
            ?: return LearnResult.Failed("validation: template did not match")
        val ex = match.extraction
        if (ex.amount != c.amount) return LearnResult.Failed("validation: amount ${ex.amount} != ${c.amount}")
        if (merchantLearned && !ex.merchantRaw.equals(TemplateEngine.cleanMerchant(c.merchant), ignoreCase = true)) {
            return LearnResult.Failed("validation: merchant '${ex.merchantRaw}' != '${c.merchant}'")
        }
        if (last4 != null && ex.accountLast4 != null && ex.accountLast4 != last4) return LearnResult.Failed("validation: last4 mismatch")
        if (ex.kind != c.kind) return LearnResult.Failed("validation: kind mismatch")
        return LearnResult.Learned(template)
    }

    private fun overlaps(s: Span, start: Int, end: Int) = start < s.end && s.start < end

    private fun wordStart(text: String, i: Int): Int {
        var j = i
        while (j > 0 && text[j - 1] != ' ') j--
        return j
    }

    private fun wordEnd(text: String, i: Int): Int {
        var j = i
        while (j < text.length && text[j] != ' ') j++
        return j
    }

    /** Escapes literal text, generalising digit runs and whitespace. */
    private fun literal(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch.isDigit() -> { while (i < s.length && s[i].isDigit()) i++; sb.append("\\d+"); continue }
                ch == ' ' -> { while (i < s.length && s[i] == ' ') i++; sb.append("\\s*"); continue }
                ch.isLetter() -> sb.append(ch)
                ch.code in 33..126 -> sb.append('\\').append(ch)
                else -> sb.append('.') // non-ASCII symbol (emoji half, ₹ ...): match any char
            }
            i++
        }
        return sb.toString()
    }

    /** Converts a date literal into a shape regex: digits -> \d, letters -> [A-Za-z]. */
    private fun shape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) when {
            ch.isDigit() -> sb.append("\\d")
            ch.isLetter() -> sb.append("[A-Za-z]")
            ch == ' ' -> sb.append("\\s?")
            else -> sb.append("\\").append(ch)
        }
        return sb.toString()
    }
}
