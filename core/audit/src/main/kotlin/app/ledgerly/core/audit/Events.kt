package app.ledgerly.core.audit

import app.ledgerly.core.model.Id
import java.time.Instant

/** Who caused a change. */
enum class Actor { USER, SYSTEM_PARSER, SYSTEM_DEDUP, SYNC, IMPORT }

/** What kind of change happened to a transaction. */
enum class AuditAction {
    CREATED, UPDATED, CATEGORISED, SPLIT, MERGED, UNMERGED, DELETED, RESTORED, PURGED, REVIEWED, LOCKED
}

/** One field-level change. Values are stable string renderings (see [TransactionDiffer]); null = absent. */
data class FieldChange(val field: String, val old: String?, val new: String?)

/**
 * Immutable change-log entry. [lockOverride] is true when the change touched a locked period and the
 * caller passed an explicit override. [relatedIds] links e.g. merge partners. [note] is free text.
 */
data class TransactionEvent(
    val id: Id,
    val transactionId: Id,
    val actor: Actor,
    val deviceId: String,
    val at: Instant,
    val action: AuditAction,
    val diff: List<FieldChange> = emptyList(),
    val lockOverride: Boolean = false,
    val relatedIds: List<Id> = emptyList(),
    val note: String? = null,
)

/** Thrown when an edit touches a locked period without an explicit override. */
class PeriodLockedException(message: String) : IllegalStateException(message)
