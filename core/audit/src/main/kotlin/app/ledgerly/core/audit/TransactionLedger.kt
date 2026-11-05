package app.ledgerly.core.audit

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Transaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Snapshot taken at merge time so the merge can be undone exactly. */
data class MergeRecord(
    val id: Id,
    val survivorId: Id,
    val survivorBefore: Transaction,
    val survivorAfter: Transaction,
    val absorbed: List<Transaction>,
    val at: Instant,
    val actor: Actor,
    val undone: Boolean = false,
)

/** Undoable operations emitted by the ledger (consumed by [UndoManager]). */
sealed class LedgerOperation {
    abstract val transactionId: Id
    data class Created(val transaction: Transaction) : LedgerOperation() { override val transactionId get() = transaction.id }
    data class Updated(val before: Transaction, val after: Transaction, val changes: List<FieldChange>) : LedgerOperation() {
        override val transactionId get() = after.id
    }
    data class Deleted(override val transactionId: Id) : LedgerOperation()
    data class Restored(override val transactionId: Id) : LedgerOperation()
    data class Merged(val record: MergeRecord) : LedgerOperation() { override val transactionId get() = record.survivorId }
    data class Unmerged(val record: MergeRecord) : LedgerOperation() { override val transactionId get() = record.survivorId }
}

/** Default merge combine: keep survivor values, union provenance + tags, fill blanks from the others. */
val DEFAULT_MERGE: (Transaction, List<Transaction>) -> Transaction = { s, others ->
    s.copy(
        sourceEventIds = (s.sourceEventIds + others.flatMap { it.sourceEventIds }).distinct(),
        sourceChannels = s.sourceChannels + others.flatMap { it.sourceChannels },
        tags = s.tags + others.flatMap { it.tags },
        merchant = s.merchant ?: others.firstNotNullOfOrNull { it.merchant },
        merchantRaw = s.merchantRaw ?: others.firstNotNullOfOrNull { it.merchantRaw },
        categoryId = s.categoryId ?: others.firstNotNullOfOrNull { it.categoryId },
        reference = s.reference ?: others.firstNotNullOfOrNull { it.reference },
        accountId = s.accountId ?: others.firstNotNullOfOrNull { it.accountId },
        accountLast4 = s.accountLast4 ?: others.firstNotNullOfOrNull { it.accountLast4 },
        note = s.note ?: others.firstNotNullOfOrNull { it.note },
    )
}

/**
 * The only write path for transactions: every mutation goes to the [store] and produces
 * [TransactionEvent]s in the [log]. Period locks are enforced here.
 */
class TransactionLedger(
    private val store: TransactionStore,
    val log: AuditLog,
    private val clock: Clock,
    private val deviceId: String = "local",
    val periodLock: PeriodLock = PeriodLock(clock.zone),
    private val idGenerator: () -> Id = defaultIds(),
) {
    private val listeners = ArrayList<(LedgerOperation) -> Unit>()
    private val merges = LinkedHashMap<Id, MergeRecord>()

    fun addListener(l: (LedgerOperation) -> Unit) { listeners += l }
    fun removeListener(l: (LedgerOperation) -> Unit) { listeners -= l }

    fun get(id: Id): Transaction? = store.get(id)
    fun active(): List<Transaction> = store.all().filter { !it.isDeleted }
    fun deleted(): List<Transaction> = store.all().filter { it.isDeleted }
    fun mergeRecord(id: Id): MergeRecord? = merges[id]
    fun mergeRecords(): List<MergeRecord> = merges.values.toList()
    fun history(id: Id): List<TransactionEvent> = log.history(id)

    private fun now(): Instant = clock.instant()

    private fun checkLock(vararg txns: Transaction?, override: Boolean): Boolean {
        val locked = txns.filterNotNull().map { periodLock.monthOf(it.occurredAt) }.filter { periodLock.isLocked(it) }
        if (locked.isEmpty()) return false
        if (!override) throw PeriodLockedException("Period ${locked.first()} is locked; pass override=true to edit")
        return true
    }

    private fun record(
        txnId: Id, actor: Actor, action: AuditAction, diff: List<FieldChange>,
        override: Boolean = false, related: List<Id> = emptyList(), note: String? = null,
    ): TransactionEvent = log.append(
        TransactionEvent(idGenerator(), txnId, actor, deviceId, now(), action, diff, override, related, note)
    )

    private fun emit(op: LedgerOperation) = listeners.toList().forEach { it(op) }

    private fun load(id: Id): Transaction = store.get(id) ?: throw NoSuchElementException("No transaction $id")

    fun create(txn: Transaction, actor: Actor = Actor.USER, overrideLock: Boolean = false): Transaction {
        check(store.get(txn.id) == null) { "Transaction ${txn.id} already exists" }
        val ov = checkLock(txn, override = overrideLock)
        store.put(txn)
        record(txn.id, actor, AuditAction.CREATED, TransactionDiffer.diff(null, txn), ov)
        emit(LedgerOperation.Created(txn))
        return txn
    }

    /** Update; picks CATEGORISED / SPLIT / REVIEWED when only those fields changed. No-op edits log nothing. */
    fun update(txn: Transaction, actor: Actor = Actor.USER, overrideLock: Boolean = false): Transaction {
        val before = load(txn.id)
        check(!before.isDeleted) { "Transaction ${txn.id} is in the trash; restore it first" }
        check(txn.deletedAt == null) { "Use softDelete to delete" }
        val diff = TransactionDiffer.diff(before, txn)
        if (diff.isEmpty()) return before
        val ov = checkLock(before, txn, override = overrideLock)
        store.put(txn)
        val fields = diff.map { it.field }.toSet()
        val action = when {
            fields == setOf("categoryId") -> AuditAction.CATEGORISED
            "splits" in fields && fields.all { it == "splits" || it == "categoryId" } -> AuditAction.SPLIT
            fields == setOf("isReviewed") -> AuditAction.REVIEWED
            else -> AuditAction.UPDATED
        }
        record(txn.id, actor, action, diff, ov)
        emit(LedgerOperation.Updated(before, txn, diff))
        return txn
    }

    fun softDelete(id: Id, actor: Actor = Actor.USER, overrideLock: Boolean = false): Transaction {
        val before = load(id)
        if (before.isDeleted) return before
        val ov = checkLock(before, override = overrideLock)
        val after = before.copy(deletedAt = now())
        store.put(after)
        record(id, actor, AuditAction.DELETED, TransactionDiffer.diff(before, after), ov)
        emit(LedgerOperation.Deleted(id))
        return after
    }

    fun restore(id: Id, actor: Actor = Actor.USER, overrideLock: Boolean = false): Transaction {
        val before = load(id)
        if (!before.isDeleted) return before
        val ov = checkLock(before, override = overrideLock)
        val after = before.copy(deletedAt = null)
        store.put(after)
        record(id, actor, AuditAction.RESTORED, TransactionDiffer.diff(before, after), ov)
        emit(LedgerOperation.Restored(id))
        return after
    }

    /**
     * Permanently remove a soft-deleted transaction once [retention] has elapsed since deletion.
     * The audit history is kept (a PURGED event is appended). Purge is not undoable.
     */
    fun purge(id: Id, retention: Duration, actor: Actor = Actor.SYSTEM_PARSER, overrideLock: Boolean = false): Transaction {
        val before = load(id)
        val deletedAt = before.deletedAt ?: throw IllegalStateException("Only trashed transactions can be purged")
        check(!now().isBefore(deletedAt.plus(retention))) { "Retention for $id has not elapsed" }
        val ov = checkLock(before, override = overrideLock)
        store.remove(id)
        record(id, actor, AuditAction.PURGED, TransactionDiffer.diff(before, null), ov)
        return before
    }

    /**
     * Merge [absorbedIds] into [survivorId] (one payment, one entry). Absorbed transactions leave the store;
     * full snapshots are kept in a [MergeRecord] so [unmerge] can re-split them.
     */
    fun merge(
        survivorId: Id,
        absorbedIds: List<Id>,
        actor: Actor = Actor.SYSTEM_DEDUP,
        overrideLock: Boolean = false,
        combine: (Transaction, List<Transaction>) -> Transaction = DEFAULT_MERGE,
    ): MergeRecord {
        require(absorbedIds.isNotEmpty() && survivorId !in absorbedIds) { "Need distinct transactions to merge" }
        val survivor = load(survivorId)
        val absorbed = absorbedIds.distinct().map { load(it) }
        check(!survivor.isDeleted && absorbed.none { it.isDeleted }) { "Cannot merge trashed transactions" }
        val ov = checkLock(survivor, *absorbed.toTypedArray(), override = overrideLock)
        val after = combine(survivor, absorbed).also { check(it.id == survivorId) { "combine must keep survivor id" } }
        val rec = MergeRecord(idGenerator(), survivorId, survivor, after, absorbed, now(), actor)
        store.put(after)
        absorbed.forEach { store.remove(it.id) }
        merges[rec.id] = rec
        record(survivorId, actor, AuditAction.MERGED, TransactionDiffer.diff(survivor, after), ov, absorbed.map { it.id }, "merge ${rec.id}")
        absorbed.forEach {
            record(it.id, actor, AuditAction.MERGED, listOf(FieldChange("mergedInto", null, survivorId)), ov, listOf(survivorId), "merge ${rec.id}")
        }
        emit(LedgerOperation.Merged(rec))
        return rec
    }

    /** Undo a merge: revert the fields the merge changed on the survivor and put the absorbed snapshots back. */
    fun unmerge(mergeId: Id, actor: Actor = Actor.USER, overrideLock: Boolean = false): MergeRecord {
        val rec = merges[mergeId] ?: throw NoSuchElementException("No merge $mergeId")
        check(!rec.undone) { "Merge $mergeId already undone" }
        val current = load(rec.survivorId)
        rec.absorbed.forEach { check(store.get(it.id) == null) { "Transaction ${it.id} exists again; cannot unmerge" } }
        val ov = checkLock(current, *rec.absorbed.toTypedArray(), override = overrideLock)
        val mergeDiff = TransactionDiffer.diff(rec.survivorBefore, rec.survivorAfter)
        val reverted = TransactionDiffer.applyFields(current, rec.survivorBefore, mergeDiff)
        store.put(reverted)
        rec.absorbed.forEach { store.put(it) }
        val done = rec.copy(undone = true)
        merges[mergeId] = done
        record(rec.survivorId, actor, AuditAction.UNMERGED, TransactionDiffer.diff(current, reverted), ov, rec.absorbed.map { it.id }, "unmerge $mergeId")
        rec.absorbed.forEach {
            record(it.id, actor, AuditAction.UNMERGED, listOf(FieldChange("mergedInto", rec.survivorId, null)), ov, listOf(rec.survivorId), "unmerge $mergeId")
        }
        emit(LedgerOperation.Unmerged(done))
        return done
    }

    /** Lock a month and log a LOCKED event for each active transaction in it. */
    fun lockPeriod(month: YearMonth, actor: Actor = Actor.USER, reason: String? = null): LockedPeriod {
        val lp = periodLock.lock(month, now(), actor, reason)
        active().filter { periodLock.monthOf(it.occurredAt) == month }.forEach {
            record(it.id, actor, AuditAction.LOCKED, emptyList(), note = "Period $month locked" + (reason?.let { r -> ": $r" } ?: ""))
        }
        return lp
    }

    fun unlockPeriod(month: YearMonth): Boolean = periodLock.unlock(month)

    companion object {
        fun defaultIds(prefix: String = "evt"): () -> Id {
            val c = AtomicLong(); val salt = java.util.UUID.randomUUID().toString().take(8)
            return { "$prefix-$salt-${c.incrementAndGet()}" }
        }
    }
}

/**
 * Renders events as human-readable history lines, e.g.
 * "Category changed from Food to Groceries by you · 3 Nov 2026".
 */
class HistoryFormatter(
    private val zone: ZoneId,
    private val categoryName: (Id) -> String? = { null },
    private val accountName: (Id) -> String? = { null },
    locale: Locale = Locale.ENGLISH,
) {
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", locale)

    fun actorLabel(a: Actor): String = when (a) {
        Actor.USER -> "you"
        Actor.SYSTEM_PARSER -> "auto-capture"
        Actor.SYSTEM_DEDUP -> "duplicate detection"
        Actor.SYNC -> "sync"
        Actor.IMPORT -> "import"
    }

    private val labels = mapOf(
        "categoryId" to "Category", "accountId" to "Account", "merchant" to "Merchant", "note" to "Note",
        "original" to "Amount", "account" to "Account amount", "base" to "Amount (base)", "occurredAt" to "Date",
        "tags" to "Tags", "splits" to "Splits", "type" to "Type", "status" to "Status", "isReviewed" to "Reviewed",
    )

    private fun value(field: String, v: String?): String = when {
        v == null -> "none"
        field == "categoryId" -> categoryName(v) ?: v
        field == "accountId" -> accountName(v) ?: v
        field in setOf("original", "account", "base", "fxFee") -> runCatching {
            val (amt, cur) = v.split(' ')
            MoneyFormat.human(app.ledgerly.core.model.Money.of(amt, cur))
        }.getOrDefault(v)
        else -> v
    }

    fun line(e: TransactionEvent): String {
        val suffix = " by ${actorLabel(e.actor)} · ${dateFmt.format(e.at.atZone(zone))}" +
            if (e.lockOverride) " (locked period override)" else ""
        val what = when (e.action) {
            AuditAction.CREATED -> "Added"
            AuditAction.DELETED -> "Moved to trash"
            AuditAction.RESTORED -> "Restored from trash"
            AuditAction.PURGED -> "Permanently deleted"
            AuditAction.MERGED -> if (e.diff.any { it.field == "mergedInto" }) "Merged into ${e.relatedIds.firstOrNull()}"
            else "Merged with ${e.relatedIds.joinToString(", ")}"
            AuditAction.UNMERGED -> if (e.diff.any { it.field == "mergedInto" }) "Split back out of ${e.relatedIds.firstOrNull()}"
            else "Unmerged ${e.relatedIds.joinToString(", ")}"
            AuditAction.REVIEWED -> "Marked as reviewed"
            AuditAction.LOCKED -> "Period locked"
            AuditAction.SPLIT -> "Split across categories"
            AuditAction.CATEGORISED, AuditAction.UPDATED -> e.diff.joinToString("; ") { c ->
                val label = labels[c.field] ?: c.field
                when {
                    c.old == null -> "$label set to ${value(c.field, c.new)}"
                    c.new == null -> "$label cleared (was ${value(c.field, c.old)})"
                    else -> "$label changed from ${value(c.field, c.old)} to ${value(c.field, c.new)}"
                }
            }.ifEmpty { "Updated" }
        }
        return what + suffix
    }

    fun lines(events: List<TransactionEvent>): List<String> = events.map(::line)
}
