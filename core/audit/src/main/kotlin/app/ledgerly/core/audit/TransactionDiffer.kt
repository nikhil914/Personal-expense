package app.ledgerly.core.audit

import app.ledgerly.core.model.Split
import app.ledgerly.core.model.Transaction

/**
 * A diffable transaction field: how to render it stably and how to copy it from one snapshot to another.
 * The copy function is what lets undo "revert a diff" without parsing rendered strings back.
 */
class TxnField internal constructor(
    val name: String,
    val render: (Transaction) -> String?,
    val copy: (target: Transaction, source: Transaction) -> Transaction,
)

/** Computes field-level diffs between two [Transaction] snapshots. */
object TransactionDiffer {

    private fun renderSplits(splits: List<Split>): String? =
        if (splits.isEmpty()) null else splits.joinToString("; ") { s ->
            buildString {
                append(s.categoryId).append('=').append(MoneyFormat.stable(s.amount))
                if (s.note != null) append(" (").append(s.note).append(')')
            }
        }

    /** All diffable fields, in display order. `id` is identity and never diffed. */
    val FIELDS: List<TxnField> = listOf(
        TxnField("accountId", { it.accountId }) { t, s -> t.copy(accountId = s.accountId) },
        TxnField("type", { it.type.name }) { t, s -> t.copy(type = s.type) },
        TxnField("status", { it.status.name }) { t, s -> t.copy(status = s.status) },
        TxnField("occurredAt", { it.occurredAt.toString() }) { t, s -> t.copy(occurredAt = s.occurredAt) },
        TxnField("original", { MoneyFormat.stable(it.original) }) { t, s -> t.copy(original = s.original) },
        TxnField("account", { MoneyFormat.stable(it.account) }) { t, s -> t.copy(account = s.account) },
        TxnField("base", { MoneyFormat.stable(it.base) }) { t, s -> t.copy(base = s.base) },
        TxnField("fxRateApplied", { MoneyFormat.decimal(it.fxRateApplied) }) { t, s -> t.copy(fxRateApplied = s.fxRateApplied) },
        TxnField("marketRate", { MoneyFormat.decimal(it.marketRate) }) { t, s -> t.copy(marketRate = s.marketRate) },
        TxnField("rateSource", { it.rateSource }) { t, s -> t.copy(rateSource = s.rateSource) },
        TxnField("rateDate", { it.rateDate?.toString() }) { t, s -> t.copy(rateDate = s.rateDate) },
        TxnField("fxFee", { MoneyFormat.stable(it.fxFee) }) { t, s -> t.copy(fxFee = s.fxFee) },
        TxnField("merchant", { it.merchant }) { t, s -> t.copy(merchant = s.merchant) },
        TxnField("merchantRaw", { it.merchantRaw }) { t, s -> t.copy(merchantRaw = s.merchantRaw) },
        TxnField("categoryId", { it.categoryId }) { t, s -> t.copy(categoryId = s.categoryId) },
        TxnField("splits", { renderSplits(it.splits) }) { t, s -> t.copy(splits = s.splits) },
        TxnField("tags", { if (it.tags.isEmpty()) null else it.tags.sorted().joinToString(",") }) { t, s -> t.copy(tags = s.tags) },
        TxnField("note", { it.note }) { t, s -> t.copy(note = s.note) },
        TxnField("reference", { it.reference }) { t, s -> t.copy(reference = s.reference) },
        TxnField("accountLast4", { it.accountLast4 }) { t, s -> t.copy(accountLast4 = s.accountLast4) },
        TxnField("transferGroupId", { it.transferGroupId }) { t, s -> t.copy(transferGroupId = s.transferGroupId) },
        TxnField("refundOfId", { it.refundOfId }) { t, s -> t.copy(refundOfId = s.refundOfId) },
        TxnField("tripId", { it.tripId }) { t, s -> t.copy(tripId = s.tripId) },
        TxnField("sourceEventIds", { if (it.sourceEventIds.isEmpty()) null else it.sourceEventIds.joinToString(",") }) { t, s -> t.copy(sourceEventIds = s.sourceEventIds) },
        TxnField("sourceChannels", { if (it.sourceChannels.isEmpty()) null else it.sourceChannels.map { c -> c.name }.sorted().joinToString(",") }) { t, s -> t.copy(sourceChannels = s.sourceChannels) },
        TxnField("isReviewed", { it.isReviewed.toString() }) { t, s -> t.copy(isReviewed = s.isReviewed) },
        TxnField("deletedAt", { it.deletedAt?.toString() }) { t, s -> t.copy(deletedAt = s.deletedAt) },
    )

    private val byName = FIELDS.associateBy { it.name }

    fun field(name: String): TxnField? = byName[name]

    /** Diff [before] → [after]. A null side means "did not exist" (creation / purge). */
    fun diff(before: Transaction?, after: Transaction?): List<FieldChange> {
        if (before != null && after != null) require(before.id == after.id) { "Diffing different transactions" }
        return FIELDS.mapNotNull { f ->
            val o = before?.let(f.render)
            val n = after?.let(f.render)
            if (o == n) null else FieldChange(f.name, o, n)
        }
    }

    /**
     * Copy the fields named in [changes] from [source] onto [target]. Fields not in the diff are untouched,
     * so concurrent edits to other fields survive an undo.
     */
    fun applyFields(target: Transaction, source: Transaction, changes: List<FieldChange>): Transaction =
        changes.fold(target) { acc, c -> byName[c.field]?.copy?.invoke(acc, source) ?: acc }
}
