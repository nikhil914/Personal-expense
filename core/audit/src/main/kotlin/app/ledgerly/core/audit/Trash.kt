package app.ledgerly.core.audit

import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Transaction
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class TrashItem(val transaction: Transaction, val deletedAt: Instant, val purgeAt: Instant, val daysLeft: Long)

/** Soft-deleted transactions: list, restore, and auto-purge after [retention] (default 30 days). */
class Trash(
    private val ledger: TransactionLedger,
    private val clock: Clock,
    val retention: Duration = Duration.ofDays(30),
) {
    init { require(!retention.isNegative) }

    /** Newest deletions first. */
    fun list(): List<TrashItem> {
        val now = clock.instant()
        return ledger.deleted().map { t ->
            val purgeAt = t.deletedAt!!.plus(retention)
            TrashItem(t, t.deletedAt!!, purgeAt, maxOf(0L, Duration.between(now, purgeAt).toDays()))
        }.sortedByDescending { it.deletedAt }
    }

    fun restore(id: Id, actor: Actor = Actor.USER, overrideLock: Boolean = false): Transaction {
        val t = ledger.get(id) ?: throw NoSuchElementException("No transaction $id")
        check(t.isDeleted) { "$id is not in the trash" }
        return ledger.restore(id, actor, overrideLock)
    }

    /**
     * Purge every item whose retention has elapsed. Items in locked periods are skipped unless
     * [overrideLock]. Returns purged ids. Meant to run from a daily WorkManager job.
     */
    fun purgeExpired(actor: Actor = Actor.SYSTEM_PARSER, overrideLock: Boolean = false): List<Id> {
        val now = clock.instant()
        return list().filter { !now.isBefore(it.purgeAt) }.mapNotNull { item ->
            try { ledger.purge(item.transaction.id, retention, actor, overrideLock).id } catch (_: PeriodLockedException) { null }
        }
    }

    /** "Empty trash now" (user action) ignores the retention window. */
    fun emptyNow(actor: Actor = Actor.USER, overrideLock: Boolean = false): List<Id> =
        list().map { ledger.purge(it.transaction.id, Duration.ZERO, actor, overrideLock).id }
}
