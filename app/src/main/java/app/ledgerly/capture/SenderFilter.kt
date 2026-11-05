package app.ledgerly.capture

/**
 * Pure, in-memory pre-filter applied BEFORE anything is stored. Personal messages and obvious
 * non-financial texts are discarded immediately and never written to disk.
 */
object SenderFilter {

    // Indian DLT headers look like "AD-HDFCBK", "VM-ICICIT-S", "JX-SBIUPI-T". Global banks use short codes.
    private val dltHeader = Regex("^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$")
    private val alphaSender = Regex("^[A-Z0-9]{3,11}$")
    private val shortCode = Regex("^\\d{3,6}$")
    private val phoneNumber = Regex("^\\+?\\d{8,15}$")

    private val financialKeywords = listOf(
        "debited", "credited", "spent", "withdrawn", "withdrawal", "debit", "credit", "txn", "transaction",
        "upi", "a/c", "acct", "account", "card", "paid", "payment", "received", "refund", "reversed",
        "balance", "bal", "purchase", "charged", "emi", "autopay", "mandate", "due", "statement", "imps",
        "neft", "rtgs", "pos", "atm", "rs.", "rs ", "inr", "₹", "$", "€", "£", "usd", "eur", "gbp", "aed", "sgd",
    )

    private val otpPattern = Regex("\\b(otp|one[- ]time password|verification code)\\b", RegexOption.IGNORE_CASE)
    private val amountPattern = Regex("(?i)(rs\\.?|inr|₹|usd|eur|gbp|aed|sgd|\\$|€|£)\\s?\\d")

    /** True when [sender] looks like a bank / business sender id, or the user allow-listed it. */
    fun isLikelyFinancialSender(sender: String?, userAllowList: Set<String> = emptySet()): Boolean {
        if (sender.isNullOrBlank()) return false
        val s = sender.trim().uppercase()
        if (s in userAllowList) return true
        if (phoneNumber.matches(s)) return false // personal numbers are never captured
        return dltHeader.matches(s) || shortCode.matches(s) || (alphaSender.matches(s) && s.any { it.isLetter() })
    }

    /** Cheap keyword gate. Over-inclusive on purpose: the parser makes the real decision. */
    fun looksFinancial(body: String): Boolean {
        val lower = body.lowercase()
        if (otpPattern.containsMatchIn(lower) && !amountPattern.containsMatchIn(body)) return false
        return amountPattern.containsMatchIn(body) || financialKeywords.count { lower.contains(it) } >= 2
    }
}
