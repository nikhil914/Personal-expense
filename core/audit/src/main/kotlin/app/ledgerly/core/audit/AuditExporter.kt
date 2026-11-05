package app.ledgerly.core.audit

import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction

data class CsvExport(val transactionsCsv: String, val historyCsv: String)

/**
 * Hand-written CSV (RFC 4180) and JSON export of transactions plus their full audit history.
 * No external dependencies.
 */
class AuditExporter(
    /** Prefix text cells starting with = + - @ (or tab/CR) with ' to defuse spreadsheet formula injection. */
    private val protectFormulas: Boolean = true,
) {
    private val txnHeader = listOf(
        "id", "account_id", "type", "status", "occurred_at", "original_amount", "original_currency",
        "account_amount", "account_currency", "base_amount", "base_currency", "fx_rate_applied", "market_rate",
        "fx_fee", "merchant", "merchant_raw", "category_id", "splits", "tags", "note", "reference",
        "transfer_group_id", "refund_of_id", "trip_id", "source_event_ids", "source_channels", "reviewed", "deleted_at",
    )
    private val eventHeader = listOf(
        "event_id", "transaction_id", "at", "actor", "device_id", "action", "field", "old_value", "new_value",
        "lock_override", "related_ids", "note",
    )

    fun exportCsv(transactions: List<Transaction>, log: AuditLog): CsvExport =
        CsvExport(transactionsCsv(transactions), historyCsv(log.all()))

    fun transactionsCsv(transactions: List<Transaction>): String = buildString {
        appendRow(txnHeader.map { Cell(it, false) })
        for (t in transactions) {
            appendRow(listOf(
                text(t.id), text(t.accountId), text(t.type.name), text(t.status.name), text(t.occurredAt.toString()),
                num(t.original.toDecimal().toPlainString()), text(t.original.currency.code),
                num(t.account.toDecimal().toPlainString()), text(t.account.currency.code),
                num(t.base.toDecimal().toPlainString()), text(t.base.currency.code),
                num(MoneyFormat.decimal(t.fxRateApplied)), num(MoneyFormat.decimal(t.marketRate)),
                text(MoneyFormat.stable(t.fxFee)), text(t.merchant), text(t.merchantRaw), text(t.categoryId),
                text(TransactionDiffer.field("splits")!!.render(t)), text(TransactionDiffer.field("tags")!!.render(t)),
                text(t.note), text(t.reference), text(t.transferGroupId), text(t.refundOfId), text(t.tripId),
                text(t.sourceEventIds.joinToString(";")), text(t.sourceChannels.map { it.name }.sorted().joinToString(";")),
                num(t.isReviewed.toString()), text(t.deletedAt?.toString()),
            ))
        }
    }

    /** One row per field change (events without a diff get a single row with empty field columns). */
    fun historyCsv(events: List<TransactionEvent>): String = buildString {
        appendRow(eventHeader.map { Cell(it, false) })
        for (e in events) {
            val changes: List<FieldChange?> = e.diff.ifEmpty { listOf(null) }
            for (c in changes) {
                appendRow(listOf(
                    text(e.id), text(e.transactionId), text(e.at.toString()), text(e.actor.name), text(e.deviceId),
                    text(e.action.name), text(c?.field), text(c?.old), text(c?.new), num(e.lockOverride.toString()),
                    text(e.relatedIds.joinToString(";")), text(e.note),
                ))
            }
        }
    }

    /** {"exportVersion":1,"transactions":[{..., "history":[...]}], "orphanHistory":[...]} */
    fun exportJson(transactions: List<Transaction>, log: AuditLog): String {
        val events = log.all()
        val byTxn = events.groupBy { it.transactionId }
        val ids = transactions.map { it.id }.toSet()
        val orphans = events.filter { it.transactionId !in ids } // e.g. purged / merged-away transactions
        val sb = StringBuilder()
        sb.append("{\"exportVersion\":1,\"transactions\":[")
        transactions.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append(txnJson(t, byTxn[t.id].orEmpty()))
        }
        sb.append("],\"orphanHistory\":[")
        orphans.forEachIndexed { i, e -> if (i > 0) sb.append(','); sb.append(eventJson(e)) }
        sb.append("]}")
        return sb.toString()
    }

    private fun txnJson(t: Transaction, history: List<TransactionEvent>): String = obj(
        "id" to str(t.id), "accountId" to str(t.accountId), "type" to str(t.type.name), "status" to str(t.status.name),
        "occurredAt" to str(t.occurredAt.toString()), "original" to money(t.original), "account" to money(t.account),
        "base" to money(t.base), "fxRateApplied" to str(MoneyFormat.decimal(t.fxRateApplied)),
        "marketRate" to str(MoneyFormat.decimal(t.marketRate)), "rateSource" to str(t.rateSource),
        "rateDate" to str(t.rateDate?.toString()), "fxFee" to money(t.fxFee), "merchant" to str(t.merchant),
        "merchantRaw" to str(t.merchantRaw), "categoryId" to str(t.categoryId),
        "splits" to arr(t.splits.map { obj("categoryId" to str(it.categoryId), "amount" to money(it.amount), "note" to str(it.note)) }),
        "tags" to arr(t.tags.sorted().map { str(it) }), "note" to str(t.note), "reference" to str(t.reference),
        "accountLast4" to str(t.accountLast4), "transferGroupId" to str(t.transferGroupId),
        "refundOfId" to str(t.refundOfId), "tripId" to str(t.tripId),
        "sourceEventIds" to arr(t.sourceEventIds.map { str(it) }),
        "sourceChannels" to arr(t.sourceChannels.map { it.name }.sorted().map { str(it) }),
        "isReviewed" to t.isReviewed.toString(), "deletedAt" to str(t.deletedAt?.toString()),
        "history" to arr(history.map(::eventJson)),
    )

    private fun eventJson(e: TransactionEvent): String = obj(
        "id" to str(e.id), "transactionId" to str(e.transactionId), "actor" to str(e.actor.name),
        "deviceId" to str(e.deviceId), "at" to str(e.at.toString()), "action" to str(e.action.name),
        "diff" to arr(e.diff.map { obj("field" to str(it.field), "old" to str(it.old), "new" to str(it.new)) }),
        "lockOverride" to e.lockOverride.toString(), "relatedIds" to arr(e.relatedIds.map { str(it) }),
        "note" to str(e.note),
    )

    private fun money(m: Money?): String = if (m == null) "null" else
        obj("minor" to m.minor.toString(), "currency" to str(m.currency.code), "amount" to str(m.toDecimal().toPlainString()))

    private fun obj(vararg kv: Pair<String, String>): String =
        kv.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:$v" }

    private fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    private class Cell(val value: String?, val isText: Boolean)
    private fun text(v: String?) = Cell(v, true)
    private fun num(v: String?) = Cell(v, false)

    private fun StringBuilder.appendRow(cells: List<Cell>) {
        cells.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append(csvCell(c.value, c.isText && protectFormulas))
        }
        append("\r\n")
    }

    companion object {
        /** RFC 4180 escaping: quote when containing comma, quote, CR or LF (or leading/trailing space); double quotes. */
        fun csvCell(value: String?, protectFormula: Boolean = false): String {
            if (value == null) return ""
            var v = value
            if (protectFormula && v.isNotEmpty() && v[0] in "=+-@\t\r") v = "'$v"
            val needsQuote = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
                (v.isNotEmpty() && (v.first() == ' ' || v.last() == ' '))
            return if (needsQuote) "\"" + v.replace("\"", "\"\"") + "\"" else v
        }

        /** JSON string literal (with quotes), or `null`. Escapes quotes, backslash, control chars, U+2028/9. */
        fun str(value: String?): String {
            if (value == null) return "null"
            val sb = StringBuilder(value.length + 2).append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    ' ', ' ' -> sb.append(String.format("\\u%04x", ch.code))
                    else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
            return sb.append('"').toString()
        }
    }
}
