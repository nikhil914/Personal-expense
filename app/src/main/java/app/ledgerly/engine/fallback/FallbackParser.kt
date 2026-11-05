package app.ledgerly.engine.fallback

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.ParseStatus
import app.ledgerly.core.model.ParsedTransaction
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.engine.LearnedTemplate
import app.ledgerly.engine.TransactionParser
import java.math.BigDecimal
import java.util.regex.Pattern

/**
 * Minimal heuristic parser used until core:parser is wired (TODO(core-parser)). Handles the common
 * "Rs 850.00 debited from A/c XX1234 at ZOMATO on ..." shapes plus user-learned templates.
 */
class FallbackParser : TransactionParser {

    private val otp = Regex("(?i)\\b(otp|one[- ]time password|verification code|do not share)\\b")
    private val promo = Regex("(?i)\\b(offer|discount|win|congratulations|pre-?approved|apply now|click|limited time|voucher|coupon)\\b")
    private val amountRx = Regex("(?i)(rs\\.?|inr|₹|usd|us\\$|\\$|eur|€|gbp|£|aed|sgd|jpy|¥|chf|aud|cad|thb)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,3})?)")
    private val debitRx = Regex("(?i)\\b(debited|spent|paid|withdrawn|sent|purchase|charged|debit|txn of|payment of)\\b")
    private val creditRx = Regex("(?i)\\b(credited|received|deposited|credit(?:ed)? to)\\b")
    private val refundRx = Regex("(?i)\\b(refund(?:ed)?|reversed|reversal|cashback)\\b")
    private val atmRx = Regex("(?i)\\b(atm|cash withdrawal|withdrawn at)\\b")
    private val upiRx = Regex("(?i)\\bupi\\b")
    private val balanceRx = Regex("(?i)(?:avl\\.?\\s*bal|available balance|avail bal|bal)[:\\s.]*(?:is\\s*)?(rs\\.?|inr|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val last4Rx = Regex("(?i)(?:a/c|ac|acct|account|card)\\s*(?:no\\.?)?\\s*(?:ending(?: with)?|xx+|x+|\\*+)?\\s*[x*]*([0-9]{4})\\b")
    private val refRx = Regex("(?i)(?:upi ref(?:\\.|erence)?(?: no\\.?)?|ref(?:erence)?(?: no\\.?)?|rrn|txn id|utr)[:\\s#.]*([A-Za-z0-9]{6,})")
    private val merchantRx = Regex("(?i)\\b(?:at|to|towards|info[:\\-]?|vpa)\\s+([A-Za-z0-9&@._\\-*' ]{2,40}?)(?=\\s+on\\b|\\s+ref|\\s+via|\\s+using|\\s+upi|\\s+avl|[.,;]|\\s*$)")

    private val symbolToCode = mapOf(
        "rs" to "INR", "rs." to "INR", "inr" to "INR", "₹" to "INR", "usd" to "USD", "us$" to "USD", "$" to "USD",
        "eur" to "EUR", "€" to "EUR", "gbp" to "GBP", "£" to "GBP", "aed" to "AED", "sgd" to "SGD", "jpy" to "JPY",
        "¥" to "JPY", "chf" to "CHF", "aud" to "AUD", "cad" to "CAD", "thb" to "THB",
    )

    override fun parse(event: SourceEvent, learned: List<LearnedTemplate>): ParsedTransaction {
        val text = listOfNotNull(event.title, event.body).joinToString(" ").replace(Regex("\\s+"), " ").trim()
        learned.firstNotNullOfOrNull { tryTemplate(it, event, text) }?.let { return it }

        if (otp.containsMatchIn(text) && !debitRx.containsMatchIn(text)) return status(event, MessageKind.OTP, ParseStatus.IGNORED_OTP)

        val amounts = amountRx.findAll(text).mapNotNull { m -> toMoney(m.groupValues[1], m.groupValues[2])?.let { it to m.range } }.toList()
        val balanceMatch = balanceRx.find(text)
        val txnAmounts = amounts.filter { (_, range) -> balanceMatch == null || range.first !in balanceMatch.range }
        val isDebit = debitRx.containsMatchIn(text)
        val isCredit = creditRx.containsMatchIn(text)
        val isRefund = refundRx.containsMatchIn(text)

        if (txnAmounts.isEmpty()) {
            return if (balanceMatch != null) status(event, MessageKind.BALANCE, ParseStatus.INFO_ONLY)
            else status(event, MessageKind.UNKNOWN, ParseStatus.IGNORED_NOT_FINANCIAL)
        }
        if (promo.containsMatchIn(text) && !isDebit && !isCredit) return status(event, MessageKind.PROMO, ParseStatus.IGNORED_PROMO)

        val direction = when {
            isRefund -> TxnDirection.CREDIT
            isDebit && !isCredit -> TxnDirection.DEBIT
            isCredit && !isDebit -> TxnDirection.CREDIT
            isDebit && isCredit -> if ((debitRx.find(text)?.range?.first ?: Int.MAX_VALUE) < (creditRx.find(text)?.range?.first ?: Int.MAX_VALUE)) TxnDirection.DEBIT else TxnDirection.CREDIT
            else -> null
        }
        val kind = when {
            isRefund -> MessageKind.REFUND
            atmRx.containsMatchIn(text) && direction == TxnDirection.DEBIT -> MessageKind.ATM_WITHDRAWAL
            upiRx.containsMatchIn(text) && direction == TxnDirection.DEBIT -> MessageKind.UPI_SENT
            upiRx.containsMatchIn(text) && direction == TxnDirection.CREDIT -> MessageKind.UPI_RECEIVED
            direction == TxnDirection.DEBIT -> MessageKind.DEBIT
            direction == TxnDirection.CREDIT -> MessageKind.CREDIT
            else -> MessageKind.UNKNOWN
        }
        val amount = txnAmounts.first().first
        // "USD 45.00 (INR 3,912.30)": a second amount in another currency is what hit the account.
        val accountAmount = txnAmounts.drop(1).map { it.first }.firstOrNull { it.currency != amount.currency }
        val merchant = merchantRx.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 && !it.all(Char::isDigit) }
        val last4 = last4Rx.find(text)?.groupValues?.get(1)
        val ref = refRx.find(text)?.groupValues?.get(1)
        val balance = balanceMatch?.let { toMoney(it.groupValues[1].ifEmpty { "inr" }, it.groupValues[2]) }

        var confidence = 0.35
        if (direction != null) confidence += 0.3
        if (merchant != null) confidence += 0.15
        if (last4 != null) confidence += 0.1
        if (ref != null) confidence += 0.05
        if (event.channel == Channel.SMS) confidence += 0.05
        confidence = confidence.coerceAtMost(0.95)

        return ParsedTransaction(
            sourceEventId = event.id,
            channel = event.channel,
            kind = kind,
            direction = direction,
            amount = amount,
            accountAmount = accountAmount,
            accountLast4 = last4,
            institution = event.sender ?: event.packageName,
            merchantRaw = merchant,
            reference = ref,
            balanceAfter = balance,
            occurredAt = event.receivedAt,
            confidence = confidence,
            parserId = "fallback-heuristic",
            status = if (direction != null && confidence >= 0.7) ParseStatus.PARSED else ParseStatus.NEEDS_REVIEW,
        )
    }

    override fun normaliseMerchant(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.substringBefore('*').substringBefore('@').replace(Regex("[^A-Za-z0-9&' .-]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercaseChar() } }
    }

    private fun tryTemplate(t: LearnedTemplate, event: SourceEvent, text: String): ParsedTransaction? {
        if (t.senderPattern != null && event.sender?.contains(t.senderPattern, ignoreCase = true) != true) return null
        if (t.packageName != null && event.packageName != t.packageName) return null
        return try {
            val m = Pattern.compile(t.regex, Pattern.CASE_INSENSITIVE).matcher(text)
            if (!m.find()) return null
            fun g(name: String): String? = try { m.group(name) } catch (e: IllegalArgumentException) { null }
            val currency = g("currency") ?: t.fieldMap["currency"] ?: return null
            val amount = toMoney(currency, g("amount") ?: return null) ?: return null
            val direction = when (t.fieldMap["direction"]?.uppercase()) { "CREDIT" -> TxnDirection.CREDIT; else -> TxnDirection.DEBIT }
            ParsedTransaction(
                sourceEventId = event.id,
                channel = event.channel,
                kind = if (direction == TxnDirection.CREDIT) MessageKind.CREDIT else MessageKind.DEBIT,
                direction = direction,
                amount = amount,
                accountLast4 = g("last4"),
                institution = event.sender ?: event.packageName,
                merchantRaw = g("merchant"),
                reference = g("ref"),
                occurredAt = event.receivedAt,
                confidence = 0.9,
                parserId = "learned:${t.id}",
                status = ParseStatus.PARSED,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun toMoney(symbol: String, number: String): Money? {
        val code = symbolToCode[symbol.lowercase()] ?: symbol.uppercase().takeIf { it.length == 3 } ?: return null
        return try {
            Money.of(BigDecimal(number.replace(",", "")), CurrencyCode.of(code))
        } catch (e: Exception) {
            null
        }
    }

    private fun status(event: SourceEvent, kind: MessageKind, status: ParseStatus) = ParsedTransaction(
        sourceEventId = event.id,
        channel = event.channel,
        kind = kind,
        direction = null,
        amount = null,
        occurredAt = event.receivedAt,
        confidence = 0.9,
        parserId = "fallback-heuristic",
        status = status,
    )
}
