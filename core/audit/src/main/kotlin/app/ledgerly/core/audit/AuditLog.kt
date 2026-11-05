package app.ledgerly.core.audit

import app.ledgerly.core.model.Id
import java.time.Instant

/**
 * Append-only audit log. There is intentionally no update/delete API: history can only grow.
 * Returned lists are read-only snapshots.
 */
interface AuditLog {
    /** Append an event. Fails if an event with the same id already exists. */
    fun append(event: TransactionEvent): TransactionEvent

    /** Events for a transaction, oldest first. */
    fun history(transactionId: Id): List<TransactionEvent>

    /** Events with `from <= at < to`, oldest first. */
    fun between(from: Instant, to: Instant): List<TransactionEvent>

    fun byActor(actor: Actor): List<TransactionEvent>

    fun all(): List<TransactionEvent>

    val size: Int
}

class InMemoryAuditLog : AuditLog {
    private val events = ArrayList<TransactionEvent>()
    private val ids = HashSet<Id>()
    private val lock = Any()

    override fun append(event: TransactionEvent): TransactionEvent = synchronized(lock) {
        require(ids.add(event.id)) { "Duplicate audit event id ${event.id}" }
        // Defensive copies so a caller-held mutable list cannot rewrite history later.
        val frozen = event.copy(diff = java.util.List.copyOf(event.diff), relatedIds = java.util.List.copyOf(event.relatedIds))
        events.add(frozen)
        frozen
    }

    override fun history(transactionId: Id): List<TransactionEvent> = snapshot { it.transactionId == transactionId }

    override fun between(from: Instant, to: Instant): List<TransactionEvent> =
        snapshot { !it.at.isBefore(from) && it.at.isBefore(to) }

    override fun byActor(actor: Actor): List<TransactionEvent> = snapshot { it.actor == actor }

    override fun all(): List<TransactionEvent> = snapshot { true }

    override val size: Int get() = synchronized(lock) { events.size }

    private inline fun snapshot(pred: (TransactionEvent) -> Boolean): List<TransactionEvent> =
        synchronized(lock) { java.util.List.copyOf(events.filter(pred)) }
}
