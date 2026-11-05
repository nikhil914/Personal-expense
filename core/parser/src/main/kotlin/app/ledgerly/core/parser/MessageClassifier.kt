package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.TxnDirection

/**
 * Rule-based classifier for SMS / notification / email-notification text.
 *
 * Order of precedence: OTP, promo, reversal, refund, failed, statement / bill due, mandate,
 * FX markup, salary, ATM, EMI, card auth, UPI, debit / credit, balance. OTPs and promos are
 * detected first so that amounts inside them are never booked.
 */
class MessageClassifier {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val otpKeyword = rx("\\b(otp|one[- ]time[- ]password|verification code|security code|auth(?:entication)? code|passcode|secure code|cvv2? code)\\b")
    private val otpContext = rx("\\b(is your|is the|for (?:your )?(?:txn|trxn|transaction|login|payment|purchase|registration|online)|to (?:complete|authenticate|verify|proceed|confirm|approve|validate)|valid (?:for|till|upto|until)|expires? in|use (?:otp )?\\d{4,8}|\\d{4,8} is|do not share|never share|don'?t share)\\b")

    /** Verbs proving money already moved; their presence vetoes OTP / promo classification. */
    private val strongCompleted = rx("\\b(debited|credited|spent|withdrawn|deducted|deposited|refunded|reversed|has been paid|have paid|you paid|paid to|sent to|received from|money received|amount received|payment received|charged|done on your|done at|was made|has been made|has been used|used for|spent using|you made a|thank you for (?:your )?payment)\\b")

    private val promoStrong = rx(
        "(pre-?approved|apply now|t\\s?&\\s?c|tnc apply|limited (?:period|time)|hurry|congratulations|you(?:'ve| have) won|\\bwin\\b|" +
            "\\bget\\b[^.]{0,25}(?:cashback|off|discount|reward)|\\bclaim\\b|lucky draw|instant loan|personal loan of|loan of up ?to|" +
            "credit limit (?:increase|enhancement)|upgrade (?:now|your)|shop now|book now|click (?:here|on|the)|download (?:the|our|now)|" +
            "\\boffer\\b|\\bvoucher\\b|\\bcoupon\\b|% off|\\bsale\\b|exclusive(?:ly)? for you|best price)",
    )
    private val promoWeak = rx("(cashback|discount|reward|bonus|free|eligible|exclusive|earn|save up ?to|up ?to (?:rs|inr|₹)|\\bupto\\b|https?://|bit\\.ly|\\bvisit\\b)")

    private val futureClauses = rx("(will|would|shall|may|should) (?:be |get )?(?:reversed|refunded|credited|debited)[^.|]*|refund (?:will|would|may|shall)[^.|]*|reversal (?:will|would|may)[^.|]*|if (?:any|debited)[^.|]*|in case[^.|]*")
    private val reversal = rx("\\b(reversed|reversal|reverted)\\b")
    private val refund = rx("\\b(refund(?:ed)?|refund of|money back)\\b")
    private val failed = rx("\\b(failed|declined|unsuccessful|could not be (?:processed|completed)|not (?:been )?(?:processed|successful)|(?:was|is|has been) rejected|insufficient (?:funds|balance|bal)|has been cancelled|txn (?:has )?failed|transaction (?:has )?failed)\\b")
    private val statement = rx("\\b(statement|e-?statement|stmt)\\b")
    private val dueWords = rx("(total (?:amount |amt\\.? )?due|min(?:imum)?\\.? (?:amount |amt\\.? )?due|(?:payment|bill|amount|amt) (?:is )?due|due (?:date|on|by)|is due\\b|overdue|pay by)")
    private val mandate = rx("\\b(mandate|auto-?pay|autodebit|auto debit|standing instruction|e-?nach|\\bsi\\b registered|recurring payment|subscription.{0,20}(?:registered|set up|created))")
    private val fxMarkup = rx("(mark-?up|cross[- ]currency|forex (?:fee|charge|markup)|foreign (?:currency )?(?:transaction|txn) (?:fee|charges?)|\\bdcc\\b|currency conversion (?:fee|charge))")
    private val salary = rx("\\b(salary|sal cr|sal for|\\bsal\\b|payroll)\\b")
    private val atm = rx("(\\batm\\b|cash withdrawal|cash wdl|\\bwdl\\b|withdrawn)")
    private val emi = rx("\\b(emi|e\\.m\\.i\\.?)\\b")
    private val cardAuth = rx("\\b(authori[sz]ed|authori[sz]ation|pre-?auth(?:ori[sz]ation)?|amount (?:has been )?blocked|on hold)\\b")
    private val upi = rx("(\\bupi\\b|\\bvpa\\b|\\b[\\w.-]{2,}@(?:ok)?[a-z]{2,}(?![\\w.@]))")
    private val balanceWords = rx("(avl\\.? ?bal|available balance|avbl bal|account balance|a/c balance|\\bbal(?:ance)?\\b|clear bal)")

    private val debitVerbs = rx(
        "\\b(debited|debit(?:ed)? (?:of|for|by|with)|spent|withdrawn|deducted|charged|sent|you (?:have )?paid|paid (?:to|rs|inr|at|for)|" +
            "has been paid|successfully paid|purchase|used (?:for|at)|(?:txn|trxn|transaction|trxn\\.) of|a charge of|made a (?:\\S+ )?(?:transaction|payment|purchase)|" +
            "transferred to|trf to|\\bdr\\b|paid\\b)",
    )
    private val creditVerbs = rx("\\b(credited|deposited|received|added to|\\bcr\\b|credit of|you got|has been added|transferred (?:in|into) your|money in|thank you for (?:your )?payment|payment (?:of [^.]{0,30})?received)\\b")

    /** Classifies one already-normalised line of text. Never throws. */
    fun classify(text: String): Classification = runCatching { classifyInternal(text) }
        .getOrElse { Classification(MessageKind.UNKNOWN, null, 0.0, listOf("error")) }

    private fun classifyInternal(raw: String): Classification {
        val text = TextNormalizer.normalize(raw)
        if (text.isEmpty()) return Classification(MessageKind.UNKNOWN, null, 0.0, listOf("empty"))
        val signals = mutableListOf<String>()
        val completed = strongCompleted.containsMatchIn(text)

        // 1. OTP
        if (otpKeyword.containsMatchIn(text) && otpContext.containsMatchIn(text) && !completed) {
            return Classification(MessageKind.OTP, null, 0.95, listOf("otp"))
        }
        if (otpKeyword.containsMatchIn(text) && Regex("\\b\\d{4,8}\\b").containsMatchIn(text) && !completed &&
            !debitVerbs.containsMatchIn(text) && !creditVerbs.containsMatchIn(text)
        ) return Classification(MessageKind.OTP, null, 0.85, listOf("otp-weak"))

        // 2. Promo
        val strongPromo = promoStrong.findAll(text).count()
        val weakPromo = promoWeak.findAll(text).count()
        if (!completed && (strongPromo >= 1 || weakPromo >= 2)) {
            return Classification(MessageKind.PROMO, null, if (strongPromo >= 1) 0.9 else 0.75, listOf("promo:$strongPromo/$weakPromo"))
        }

        val cleaned = futureClauses.replace(text, " ")
        val debitPos = debitVerbs.find(cleaned)?.range?.first
        val creditPos = creditVerbs.find(cleaned)?.range?.first
        val dir: TxnDirection? = when {
            debitPos != null && creditPos != null -> if (debitPos <= creditPos) TxnDirection.DEBIT else TxnDirection.CREDIT
            debitPos != null -> TxnDirection.DEBIT
            creditPos != null -> TxnDirection.CREDIT
            else -> null
        }
        dir?.let { signals += "dir:$it" }

        // 3. Reversal / refund / failure
        if (reversal.containsMatchIn(cleaned)) return Classification(MessageKind.REVERSAL, TxnDirection.CREDIT, 0.9, signals + "reversal")
        if (refund.containsMatchIn(cleaned) && !failed.containsMatchIn(text)) {
            return Classification(MessageKind.REFUND, TxnDirection.CREDIT, 0.9, signals + "refund")
        }
        if (failed.containsMatchIn(text)) return Classification(MessageKind.FAILED_TXN, null, 0.9, signals + "failed")

        // 4. Statement / bill due (only when no money moved)
        val moved = completed || (dir != null && Regex("\\b(debited|credited|spent|received|withdrawn)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned))
        if (!moved && dueWords.containsMatchIn(text)) {
            return if (statement.containsMatchIn(text)) Classification(MessageKind.STATEMENT, null, 0.9, signals + "statement")
            else Classification(MessageKind.BILL_DUE, null, 0.85, signals + "due")
        }
        if (!moved && statement.containsMatchIn(text) && Regex("generated|is ready|available|sent", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return Classification(MessageKind.STATEMENT, null, 0.8, signals + "statement")
        }

        // 5. Mandate / autopay
        if (mandate.containsMatchIn(text)) {
            val debited = Regex("\\b(debited|deducted|paid|charged|spent)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
            return if (debited && dir == TxnDirection.DEBIT) {
                Classification(if (upi.containsMatchIn(text)) MessageKind.UPI_SENT else MessageKind.DEBIT, TxnDirection.DEBIT, 0.85, signals + "mandate-debit")
            } else Classification(MessageKind.AUTOPAY_MANDATE, TxnDirection.DEBIT, 0.85, signals + "mandate")
        }

        // 6. FX markup fee
        if (fxMarkup.containsMatchIn(text)) return Classification(MessageKind.FX_MARKUP, TxnDirection.DEBIT, 0.85, signals + "fx-markup")

        // 7. Salary
        if (dir == TxnDirection.CREDIT && salary.containsMatchIn(text)) return Classification(MessageKind.SALARY, TxnDirection.CREDIT, 0.9, signals + "salary")

        // 8. ATM
        if (atm.containsMatchIn(text) && dir != TxnDirection.CREDIT &&
            (dir == TxnDirection.DEBIT || Regex("withdrawn|withdrawal", RegexOption.IGNORE_CASE).containsMatchIn(text))
        ) return Classification(MessageKind.ATM_WITHDRAWAL, TxnDirection.DEBIT, 0.9, signals + "atm")

        // 9. EMI
        if (emi.containsMatchIn(text) && dir == TxnDirection.DEBIT) return Classification(MessageKind.EMI, TxnDirection.DEBIT, 0.85, signals + "emi")
        if (emi.containsMatchIn(text) && dir == null && Regex("convert|booked", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return Classification(MessageKind.EMI, TxnDirection.DEBIT, 0.7, signals + "emi-conversion")
        }

        // 10. Card authorisation
        if (cardAuth.containsMatchIn(text) && !Regex("\\b(debited|spent)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return Classification(MessageKind.CARD_AUTH, TxnDirection.DEBIT, 0.75, signals + "auth")
        }

        val isUpi = upi.containsMatchIn(text)
        // 11. Debit / credit
        when (dir) {
            TxnDirection.DEBIT -> return Classification(if (isUpi) MessageKind.UPI_SENT else MessageKind.DEBIT, dir, 0.85, signals + if (isUpi) "upi" else "debit")
            TxnDirection.CREDIT -> return Classification(if (isUpi) MessageKind.UPI_RECEIVED else MessageKind.CREDIT, dir, 0.85, signals + if (isUpi) "upi" else "credit")
            null -> {}
        }

        // 12. Balance enquiry
        if (balanceWords.containsMatchIn(text) && AmountParser.findAll(text).isNotEmpty()) {
            return Classification(MessageKind.BALANCE, null, 0.75, signals + "balance")
        }
        return Classification(MessageKind.UNKNOWN, null, 0.2, signals)
    }
}
