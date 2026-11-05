package app.ledgerly.core.dedup

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnStatus

/** Field names accepted in the locked set (user-edited fields are never overwritten). */
object Fields {
    const val OCCURRED_AT = "occurredAt"
    const val ORIGINAL = "original"
    const val ACCOUNT = "account"
    const val MERCHANT = "merchant"
    const val MERCHANT_RAW = "merchantRaw"
    const val REFERENCE = "reference"
    const val ACCOUNT_ID = "accountId"
    const val ACCOUNT_LAST4 = "accountLast4"
    const val STATUS = "status"
    const val CATEGORY = "categoryId"
    const val NOTE = "note"
}

data class MergeResult(
    val transaction: Transaction,
    /** Names of fields that changed (see [Fields]). */
    val changedFields: Set<String>,
    /** Balance reported by the candidate (SMS usually), not storable on Transaction. */
    val balanceAfter: Money?,
    /** Line items from an email receipt. */
    val lineItems: List<LineItem>,
    /** True when original/account amounts changed, so FX fields should be re-enriched. */
    val needsFxReenrich: Boolean,
)

/**
 * Merges a [Candidate] into an existing [Transaction], keeping the richest data from each channel:
 * merchant from email (then notification, then SMS), reference from SMS, earliest occurredAt,
 * cross-currency amounts when the candidate shows both. Never touches category/note/splits/tags,
 * and never overwrites fields named in `locked`.
 */
object Merger {
    private val MERCHANT_PRIORITY = mapOf(
        Channel.MANUAL to 10, Channel.EMAIL to 4, Channel.OCR to 3, Channel.NOTIFICATION to 2,
        Channel.SMS to 1, Channel.BANK_API to 1, Channel.IMPORT to 0,
    )
    private val REFERENCE_PRIORITY = mapOf(
        Channel.MANUAL to 10, Channel.BANK_API to 5, Channel.SMS to 4, Channel.NOTIFICATION to 3,
        Channel.IMPORT to 2, Channel.EMAIL to 1, Channel.OCR to 0,
    )

    private fun prio(map: Map<Channel, Int>, chs: Set<Channel>) = chs.maxOfOrNull { map[it] ?: 0 } ?: -1

    fun merge(existing: Transaction, candidate: Candidate, locked: Set<String> = emptySet()): MergeResult {
        val changed = LinkedHashSet<String>()
        var t = existing
        fun free(f: String) = f !in locked

        if (free(Fields.OCCURRED_AT) && candidate.occurredAt.isBefore(t.occurredAt)) {
            t = t.copy(occurredAt = candidate.occurredAt); changed += Fields.OCCURRED_AT
        }

        val mPrioC = MERCHANT_PRIORITY[candidate.channel] ?: 0
        val mPrioT = prio(MERCHANT_PRIORITY, t.sourceChannels)
        if (candidate.merchantRaw != null && free(Fields.MERCHANT_RAW) &&
            (t.merchantRaw == null || mPrioC > mPrioT) && candidate.merchantRaw != t.merchantRaw
        ) { t = t.copy(merchantRaw = candidate.merchantRaw); changed += Fields.MERCHANT_RAW }

        val rPrioC = REFERENCE_PRIORITY[candidate.channel] ?: 0
        val rPrioT = prio(REFERENCE_PRIORITY, t.sourceChannels)
        if (candidate.reference != null && free(Fields.REFERENCE) &&
            (t.reference == null || rPrioC > rPrioT) && candidate.reference != t.reference
        ) { t = t.copy(reference = candidate.reference); changed += Fields.REFERENCE }

        if (t.accountId == null && candidate.accountId != null && free(Fields.ACCOUNT_ID)) {
            t = t.copy(accountId = candidate.accountId); changed += Fields.ACCOUNT_ID
        }
        if (t.accountLast4 == null && candidate.accountLast4 != null && free(Fields.ACCOUNT_LAST4)) {
            t = t.copy(accountLast4 = candidate.accountLast4); changed += Fields.ACCOUNT_LAST4
        }

        // Cross-currency enrichment: candidate shows "USD 45.00 (INR 4,212.30)" while existing knows only one side.
        var fx = false
        val ca = candidate.accountAmount
        if (ca != null && ca.currency != candidate.amount.currency) {
            val sign = if (t.original.isNegative) -1 else 1
            val newOriginal = Money(candidate.amount.abs().minor * sign, candidate.amount.currency)
            val newAccount = Money(ca.abs().minor * sign, ca.currency)
            if (free(Fields.ORIGINAL) && t.original != newOriginal && t.original.currency != newOriginal.currency) {
                t = t.copy(original = newOriginal); changed += Fields.ORIGINAL; fx = true
            }
            if (free(Fields.ACCOUNT) && t.account != newAccount) {
                t = t.copy(account = newAccount); changed += Fields.ACCOUNT; fx = true
            }
            if (fx && t.base.currency == t.account.currency) t = t.copy(base = t.account)
        }

        if (candidate.kind == MessageKind.CARD_SETTLEMENT && t.status == TxnStatus.PENDING && free(Fields.STATUS)) {
            t = t.copy(status = TxnStatus.POSTED); changed += Fields.STATUS
        }

        val ids = (t.sourceEventIds + candidate.sourceEventId).distinct()
        val chs = t.sourceChannels + candidate.channel
        if (ids != t.sourceEventIds) changed += "sourceEventIds"
        if (chs != t.sourceChannels) changed += "sourceChannels"
        t = t.copy(sourceEventIds = ids, sourceChannels = chs)

        return MergeResult(t, changed, candidate.balanceAfter, candidate.lineItems, fx)
    }
}
