package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Money
import java.time.Instant

/**
 * Fallback extractor for unknown formats. Finds amounts (with currency), account/card last4,
 * merchant, reference, balance and date using generic patterns, and scores its confidence.
 */
class HeuristicExtractor(private val config: ParserConfig = ParserConfig()) {

    private val dates = DateResolver(config.zone, config.dayFirstDates)
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val balanceLabel = rx("(avl\\.?\\s*bal(?:ance)?|avbl\\.?\\s*bal|available\\s+bal(?:ance)?|a/c\\s+bal(?:ance)?|account\\s+balance|clear\\s+bal|closing\\s+bal(?:ance)?|\\bbal(?:ance)?)[\\s:.-]*(?:is\\s*)?(?:of\\s*)?$")
    private val limitLabel = rx("(avl\\.?\\s*l(?:i)?m(?:i)?t|available\\s+(?:credit\\s+)?limit|credit\\s+limit|\\blimit|\\blmt)[\\s:.-]*(?:is\\s*)?$")
    private val minDueLabel = rx("(min(?:imum)?\\.?\\s*(?:amount|amt\\.?)?\\s*due)[\\s:.-]*(?:is\\s*)?(?:of\\s*)?$")
    private val totalDueLabel = rx("(total\\s*(?:amount|amt\\.?)?\\s*due|bill\\s+(?:amount\\s+)?(?:of|is)|outstanding|statement\\s+balance)[\\s:.-]*(?:is\\s*)?(?:of\\s*)?$")
    private val bareAmount = rx("\\b(?:debited|credited|withdrawn|spent|paid|sent|received|deducted|transferred)\\s+(?:by|for|of|with)?\\s*(${AmountParser.NUMBER})(?![\\d%])")
    private val bareAmount2 = rx("\\b(?:for|of)\\s+(${AmountParser.NUMBER})\\s+(?:on|at|to|from)\\b")

    private val last4Patterns = listOf(
        rx("(?:card|a/?c|acct|account|ac)\\s*(?:no\\.?|number|num)?\\s*(?:ending|ending\\s+(?:in|with))?\\s*[:#-]?\\s*[xX*•.]{1,12}\\s?(\\d{3,6})\\b"),
        rx("ending\\s*(?:in|with)?\\s*[:#-]?\\s*[xX*•]*\\s?(\\d{4,5})\\b"),
        rx("(?<![\\w])[xX*•]{2,}\\s?(\\d{3,6})\\b"),
        rx("(?:card|a/?c|acct|account)\\s*(?:no\\.?|number)?\\s*[:#-]?\\s*[xX](\\d{3,6})\\b"),
        rx("(?:a/c|acct|account|card)\\s+(?:no\\.?\\s*)?(\\d{4})\\b(?![\\d,.])"),
    )

    private val refPatterns = listOf(
        rx("UPI\\s*(?:Ref(?:erence)?|Txn|Transaction)\\.?\\s*(?:No\\.?|Number|ID|#)?\\s*(?:is)?[\\s:.-]*([A-Za-z0-9]*\\d[A-Za-z0-9]{5,})"),
        rx("\\b(?:RRN|UTR|Ref(?:erence)?\\.?\\s*(?:No\\.?|Number|ID|#)?|Txn\\s*(?:ID|No\\.?|#)|Transaction\\s*(?:ID|No\\.?|reference(?:\\s+number)?|number))\\s*(?:is)?[\\s:.-]*([A-Za-z0-9]*\\d[A-Za-z0-9]{5,})"),
        rx("\\bUPI[:/-]\\s*(?:P2[MA]/)?(\\d{9,})"),
        rx("\\bAuth(?:orization)?\\s*(?:code|ID)[\\s:.-]*([A-Za-z0-9]{6})\\b"),
    )

    private val vpa = rx("\\b([a-z0-9][a-z0-9._-]{1,}@(?:ok)?[a-z]{2,})(?![\\w.@])")
    private val merchantCue = rx("(?:\\bat\\b|\\bto\\b|\\btowards\\b|\\bVPA\\b|Info\\s*:|\\bfrom\\b|\\bby\\b|trf to|\\bfor\\b|merchant\\s*(?:name)?\\s*:|\\bwith\\b)\\s*:?\\s*")
    private val merchantStop = rx("\\s+(?:on|via|using|with|from|ref(?:no)?|upi|avl|dated|vpa|is|has|was|for|in|thru|through|ending|card|a/c)\\b|\\s+\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\s+\\d{1,2}[- ]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)|[.;,|!(]\\s|[.;,|!(]?$|\\s+-\\s")
    private val notMerchant = rx("^(?:your|you|the|a/?c|account|acct|card|ur|vpa|upi|rs\\.?|inr|bank|beneficiary|a|an|xx\\d*|\\*+\\d*|x\\d+|\\d+|mobile|ending|date|txn|transaction|credit|debit|sbi|us|our|this)\\b|^[\\d\\s.,:/-]+$")
    private val bankWords = rx("\\b(bank|a/?c|account|card)\\b")

    /** Extracts fields from normalised [text]. Never throws; returns low confidence when unsure. */
    fun extract(text: String, receivedAt: Instant, classification: Classification? = null, institution: String? = null): Extraction {
        val kind = classification?.kind ?: MessageKind.UNKNOWN
        return runCatching { extractInternal(TextNormalizer.normalize(text), receivedAt, kind, classification, institution) }
            .getOrElse { Extraction(kind, classification?.direction, null, confidence = 0.0, parserId = PARSER_ID) }
    }

    private fun extractInternal(text: String, receivedAt: Instant, kind: MessageKind, cls: Classification?, institution: String?): Extraction {
        val hits = AmountParser.findAll(text, config.dollarCurrency)
        val roles = hits.map { it to roleOf(text, it) }
        val balance = roles.firstOrNull { it.second == Role.BALANCE }?.first?.money
        val minDue = roles.firstOrNull { it.second == Role.MIN_DUE }?.first?.money
        val totalDue = roles.firstOrNull { it.second == Role.TOTAL_DUE }?.first

        var amountHit: AmountHit? = if (kind == MessageKind.BILL_DUE || kind == MessageKind.STATEMENT) {
            totalDue ?: roles.firstOrNull { it.second == Role.TXN }?.first
        } else roles.firstOrNull { it.second == Role.TXN || it.second == Role.TOTAL_DUE }?.first
        if (kind == MessageKind.BALANCE && amountHit == null) amountHit = hits.firstOrNull()

        var amount: Money? = amountHit?.money
        var explicitCurrency = amountHit != null
        if (amount == null) {
            val m = bareAmount.find(text) ?: bareAmount2.find(text)
            if (m != null) {
                amount = AmountParser.toMoney(m.groupValues[1], null, config.defaultCurrency)
                explicitCurrency = false
            }
        }

        // Multi-currency: "USD 45.00 (INR 3,912.30)" or "USD 45.00 ... i.e. INR 3,912.30".
        var accountAmount: Money? = null
        if (amountHit != null) {
            val idx = hits.indexOf(amountHit)
            val next = hits.getOrNull(idx + 1)
            if (next != null && next.money.currency != amountHit.money.currency && roleOf(text, next) == Role.TXN) {
                val gap = text.substring(amountHit.range.last + 1, next.range.first)
                if (gap.length <= 25 && Regex("^[\\s(\\[:=~-]*(?:i\\.?e\\.?|approx\\.?|equivalent(?: to)?|eq\\.?|converted to)?[\\s(\\[:=~-]*$", RegexOption.IGNORE_CASE).matches(gap)) {
                    accountAmount = next.money
                }
            }
        }

        val last4 = findLast4(text, amountHit)
        val reference = refPatterns.firstNotNullOfOrNull { p -> p.find(text)?.groupValues?.get(1) }
            ?.takeIf { it != last4 && it.length >= 6 }
        val merchant = findMerchant(text, kind)
        val preferFuture = kind == MessageKind.BILL_DUE || kind == MessageKind.STATEMENT
        val dateHit = dates.findFirst(text, receivedAt, preferFuture)
        val occurredAt = if (!preferFuture) dateHit?.let { dates.toInstant(it, receivedAt) } else null
        val dueDate = if (preferFuture) dateHit?.date else null

        var c = 0.2
        if (kind != MessageKind.UNKNOWN) c += 0.2
        if (amount != null) c += if (explicitCurrency) 0.25 else 0.12
        if (last4 != null) c += 0.1
        if (merchant != null) c += 0.06
        if (reference != null) c += 0.04
        if (dateHit != null) c += 0.04
        if (institution != null) c += 0.03
        if (kind in Kinds.LEDGER && amount == null) c = minOf(c, 0.3)
        if (kind == MessageKind.UNKNOWN) c = minOf(c, 0.5)

        return Extraction(
            kind = kind,
            direction = cls?.direction ?: Kinds.defaultDirection(kind),
            amount = amount,
            accountAmount = accountAmount,
            accountLast4 = last4,
            merchantRaw = merchant,
            reference = reference,
            balanceAfter = balance,
            occurredAt = occurredAt,
            dueDate = dueDate,
            minimumDue = minDue,
            institution = institution,
            confidence = c.coerceIn(0.0, 0.85),
            parserId = PARSER_ID,
        )
    }

    private enum class Role { TXN, BALANCE, LIMIT, MIN_DUE, TOTAL_DUE }

    private fun roleOf(text: String, hit: AmountHit): Role {
        val before = text.substring(maxOf(0, hit.range.first - 30), hit.range.first)
        return when {
            minDueLabel.containsMatchIn(before) -> Role.MIN_DUE
            limitLabel.containsMatchIn(before) -> Role.LIMIT
            balanceLabel.containsMatchIn(before) -> Role.BALANCE
            totalDueLabel.containsMatchIn(before) -> Role.TOTAL_DUE
            else -> Role.TXN
        }
    }

    private fun findLast4(text: String, amountHit: AmountHit?): String? {
        for (p in last4Patterns) {
            for (m in p.findAll(text)) {
                val g = m.groups[1] ?: continue
                if (amountHit != null && g.range.first in amountHit.range) continue
                val digits = g.value
                return if (digits.length > 4) digits.takeLast(4) else digits
            }
        }
        return null
    }

    private fun findMerchant(text: String, kind: MessageKind): String? {
        // "UPI/P2M/412345/BIGBASKET" style
        Regex("UPI/(?:P2[MA]/)?\\d{6,}/([A-Za-z][^/\\s.]*(?: [A-Za-z][^/\\s.]*)*)", RegexOption.IGNORE_CASE).find(text)?.let {
            return TemplateEngine.cleanMerchant(it.groupValues[1])
        }
        Regex("Info\\s*:?\\s*([^.|]{2,60})", RegexOption.IGNORE_CASE).find(text)?.let {
            return TemplateEngine.cleanMerchant(it.groupValues[1])
        }
        val credit = kind == MessageKind.CREDIT || kind == MessageKind.UPI_RECEIVED || kind == MessageKind.REFUND ||
            kind == MessageKind.SALARY || kind == MessageKind.REVERSAL
        val candidates = mutableListOf<Pair<Int, String>>()
        for (m in merchantCue.findAll(text)) {
            val cue = m.value.trim().lowercase()
            // For credits, "from"/"by" names the counterparty; for debits "at"/"to"/"towards"/"info".
            val rank = when {
                cue.startsWith("info") || cue.startsWith("merchant") -> 0
                cue.startsWith("vpa") -> 1
                credit && (cue.startsWith("from") || cue.startsWith("by")) -> 1
                !credit && (cue == "at" || cue.startsWith("towards") || cue.startsWith("trf")) -> 1
                !credit && cue.startsWith("to") -> 2
                cue.startsWith("with") -> 4
                cue.startsWith("for") -> 5
                else -> 3
            }
            val rest = text.substring(m.range.last + 1)
            val stop = merchantStop.find(rest)
            var cand = (if (stop != null) rest.substring(0, stop.range.first) else rest).trim()
            if (cand.length > 50) cand = cand.take(50)
            if (cand.isEmpty() || notMerchant.containsMatchIn(cand)) continue
            if (bankWords.containsMatchIn(cand) && !vpa.containsMatchIn(cand)) continue
            if (AmountParser.findAll(cand).isNotEmpty()) continue
            if (!cand.any { it.isLetter() }) continue
            candidates += rank to cand
        }
        val best = candidates.minByOrNull { it.first }?.second
        if (best != null) return TemplateEngine.cleanMerchant(best)
        return vpa.find(text)?.groupValues?.get(1)
    }

    companion object {
        const val PARSER_ID = "heuristic"
    }
}
