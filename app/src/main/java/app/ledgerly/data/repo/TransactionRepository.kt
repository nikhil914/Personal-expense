package app.ledgerly.data.repo

import androidx.room.withTransaction
import app.ledgerly.core.model.Transaction
import app.ledgerly.data.db.AuditActor
import app.ledgerly.data.db.AuditEventType
import app.ledgerly.data.db.LedgerlyDatabase
import app.ledgerly.data.db.ReviewState
import app.ledgerly.data.db.SourceEventEntity
import app.ledgerly.data.db.TransactionEntity
import app.ledgerly.data.db.TransactionEventEntity
import app.ledgerly.data.mapper.toEntity
import app.ledgerly.data.mapper.toModel
import app.ledgerly.engine.AuditService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant

/** List row: the core model plus app-only review metadata. */
data class TxnRow(val txn: Transaction, val reviewState: ReviewState, val duplicateOfId: String?, val confidence: Double?)

data class TxnDetail(
    val row: TxnRow,
    val sourceEvents: List<SourceEventEntity>,
    val history: List<TransactionEventEntity>,
)

fun TransactionEntity.toRow(): TxnRow = TxnRow(toModel(), reviewState, duplicateOfId, parseConfidence)

/**
 * Every write goes through here so each change produces audit events in the same DB transaction.
 */
class TransactionRepository(
    private val db: LedgerlyDatabase,
    private val audit: AuditService,
) {
    private val txnDao = db.transactionDao()
    private val splitDao = db.splitDao()
    private val eventDao = db.transactionEventDao()
    private val sourceDao = db.sourceEventDao()

    fun observeActive(): Flow<List<TxnRow>> = txnDao.observeActive().map { list -> list.map { it.toRow() } }

    fun observeInRange(from: Instant, to: Instant): Flow<List<TxnRow>> =
        txnDao.observeInRange(from, to).map { list -> list.map { it.toRow() } }

    fun observeNeedingReview(): Flow<List<TxnRow>> = txnDao.observeNeedingReview().map { list -> list.map { it.toRow() } }

    fun observeReviewCount(): Flow<Int> = txnDao.observeReviewCount()

    fun observeTrash(): Flow<List<TxnRow>> = txnDao.observeTrash().map { list -> list.map { it.toRow() } }

    fun observeDetail(id: String): Flow<TxnDetail?> = combine(
        txnDao.observeById(id),
        splitDao.observeForTxn(id),
        sourceDao.observeForTxn(id),
        eventDao.observeForTxn(id),
    ) { entity, splits, sources, history ->
        entity?.let {
            TxnDetail(
                row = TxnRow(it.toModel(splits, sources.map { s -> s.id }), it.reviewState, it.duplicateOfId, it.parseConfidence),
                sourceEvents = sources,
                history = history,
            )
        }
    }

    suspend fun get(id: String): Transaction? {
        val e = txnDao.getById(id) ?: return null
        return e.toModel(splitDao.getForTxn(id), sourceDao.idsForTxn(id))
    }

    suspend fun getRow(id: String): TxnRow? = txnDao.getById(id)?.toRow()

    suspend fun inRange(from: Instant, to: Instant): List<Transaction> = txnDao.getInRange(from, to).map { it.toModel() }

    suspend fun allActive(): List<Transaction> = txnDao.getAllActive().map { it.toModel() }

    suspend fun insertNew(
        txn: Transaction,
        actor: AuditActor,
        reviewState: ReviewState = ReviewState.OK,
        duplicateOfId: String? = null,
        confidence: Double? = null,
        extraEvents: List<TransactionEventEntity> = emptyList(),
    ) {
        db.withTransaction {
            txnDao.insert(txn.toEntity(reviewState = reviewState, duplicateOfId = duplicateOfId, parseConfidence = confidence))
            splitDao.replaceForTxn(txn.id, txn.splits.map { it.toEntity(txn.id) })
            val changes = audit.diff(null, txn).map { c ->
                TransactionEventEntity(transactionId = txn.id, type = c.type, field = c.field, oldValue = c.oldValue, newValue = c.newValue, actor = actor)
            }
            eventDao.insertAll(changes + extraEvents)
        }
    }

    /** Persists [after], writing one audit event per changed field. Returns false if nothing changed. */
    suspend fun update(after: Transaction, actor: AuditActor, note: String? = null, reviewState: ReviewState? = null): Boolean {
        return db.withTransaction {
            val existing = txnDao.getById(after.id) ?: return@withTransaction false
            val before = existing.toModel(splitDao.getForTxn(after.id), sourceDao.idsForTxn(after.id))
            val changes = audit.diff(before, after)
            val newReview = reviewState ?: existing.reviewState
            if (changes.isEmpty() && newReview == existing.reviewState) return@withTransaction false
            txnDao.update(after.toEntity(existing = existing, reviewState = newReview, duplicateOfId = if (newReview == ReviewState.OK) null else existing.duplicateOfId))
            if (before.splits != after.splits) splitDao.replaceForTxn(after.id, after.splits.map { it.toEntity(after.id) })
            eventDao.insertAll(changes.map { c ->
                TransactionEventEntity(transactionId = after.id, type = c.type, field = c.field, oldValue = c.oldValue, newValue = c.newValue, actor = actor, note = note)
            })
            true
        }
    }

    suspend fun softDelete(id: String, actor: AuditActor = AuditActor.USER) {
        val now = Instant.now()
        db.withTransaction {
            txnDao.softDelete(id, now)
            eventDao.insert(TransactionEventEntity(transactionId = id, type = AuditEventType.DELETED, actor = actor, at = now))
        }
    }

    suspend fun restore(id: String, actor: AuditActor = AuditActor.USER) {
        val now = Instant.now()
        db.withTransaction {
            txnDao.restore(id, now)
            eventDao.insert(TransactionEventEntity(transactionId = id, type = AuditEventType.RESTORED, actor = actor, at = now))
        }
    }

    suspend fun markReviewed(id: String) {
        val t = get(id) ?: return
        update(t.copy(isReviewed = true), AuditActor.USER, reviewState = ReviewState.OK)
    }

    /** Review inbox "Merge": fold [duplicateId] into [keepId]; the duplicate goes to trash. */
    suspend fun mergeDuplicate(duplicateId: String, keepId: String, merge: (Transaction, Transaction) -> Transaction) {
        val dup = get(duplicateId) ?: return
        val keep = get(keepId) ?: return
        val merged = merge(keep, dup)
        db.withTransaction {
            sourceDao.relink(duplicateId, keepId)
            update(merged, AuditActor.USER, note = "merged $duplicateId")
            val now = Instant.now()
            txnDao.softDelete(duplicateId, now)
            eventDao.insert(TransactionEventEntity(transactionId = duplicateId, type = AuditEventType.MERGED, newValue = keepId, actor = AuditActor.USER, at = now, note = "merged into $keepId"))
        }
    }

    /** Review inbox "Keep both". */
    suspend fun keepBoth(id: String) {
        val t = get(id) ?: return
        update(t.copy(isReviewed = true), AuditActor.USER, note = "kept as separate payment", reviewState = ReviewState.OK)
    }
}
