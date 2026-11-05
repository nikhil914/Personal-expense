package app.ledgerly.core.audit

import app.ledgerly.core.model.Transaction

/**
 * Undo/redo of the last [capacity] ledger operations. Subscribes to the [ledger]; operations performed
 * while undoing/redoing are not re-recorded. A new user operation clears the redo stack.
 *
 * - undo create = soft delete, undo delete = restore, undo restore = delete
 * - undo update = revert exactly the changed fields (other fields edited since are kept)
 * - undo merge = unmerge (absorbed transactions come back from the merge snapshot)
 *
 * If an inverse fails (e.g. the period got locked), the exception propagates and stacks are unchanged.
 */
class UndoManager(
    private val ledger: TransactionLedger,
    val capacity: Int = 50,
    private val actor: Actor = Actor.USER,
) {
    private val undoStack = ArrayDeque<LedgerOperation>()
    private val redoStack = ArrayDeque<LedgerOperation>()
    private var replaying = false

    private val listener: (LedgerOperation) -> Unit = { op ->
        if (!replaying) {
            undoStack.addLast(op)
            while (undoStack.size > capacity) undoStack.removeFirst()
            redoStack.clear()
        }
    }

    init {
        require(capacity > 0)
        ledger.addListener(listener)
    }

    fun detach() = ledger.removeListener(listener)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoSize: Int get() = undoStack.size
    val redoSize: Int get() = redoStack.size

    /** Description of the operation [undo] would revert, for "Undo <x>" snackbars. */
    fun peekUndo(): LedgerOperation? = undoStack.lastOrNull()
    fun peekRedo(): LedgerOperation? = redoStack.lastOrNull()

    fun clear() { undoStack.clear(); redoStack.clear() }

    /** Undo the latest operation; returns it, or null when nothing to undo. */
    fun undo(overrideLock: Boolean = false): LedgerOperation? {
        val op = undoStack.lastOrNull() ?: return null
        val redoOp = replay { invert(op, overrideLock) }
        undoStack.removeLast()
        redoStack.addLast(redoOp)
        return op
    }

    fun redo(overrideLock: Boolean = false): LedgerOperation? {
        val op = redoStack.lastOrNull() ?: return null
        val undoOp = replay { reapply(op, overrideLock) }
        redoStack.removeLast()
        undoStack.addLast(undoOp)
        return undoOp
    }

    private inline fun <T> replay(block: () -> T): T {
        replaying = true
        try { return block() } finally { replaying = false }
    }

    private fun current(op: LedgerOperation): Transaction =
        ledger.get(op.transactionId) ?: throw NoSuchElementException("Transaction ${op.transactionId} no longer exists")

    /** Perform the inverse; returns the op to keep on the redo stack. */
    private fun invert(op: LedgerOperation, ov: Boolean): LedgerOperation = when (op) {
        is LedgerOperation.Created -> { ledger.softDelete(op.transactionId, actor, ov); op }
        is LedgerOperation.Deleted -> { ledger.restore(op.transactionId, actor, ov); op }
        is LedgerOperation.Restored -> { ledger.softDelete(op.transactionId, actor, ov); op }
        is LedgerOperation.Updated -> {
            ledger.update(TransactionDiffer.applyFields(current(op), op.before, op.changes), actor, ov); op
        }
        is LedgerOperation.Merged -> LedgerOperation.Merged(ledger.unmerge(op.record.id, actor, ov))
        is LedgerOperation.Unmerged -> LedgerOperation.Unmerged(remerge(op.record, ov))
    }

    /** Re-perform; returns the op to keep on the undo stack. */
    private fun reapply(op: LedgerOperation, ov: Boolean): LedgerOperation = when (op) {
        is LedgerOperation.Created -> { ledger.restore(op.transactionId, actor, ov); op }
        is LedgerOperation.Deleted -> { ledger.softDelete(op.transactionId, actor, ov); op }
        is LedgerOperation.Restored -> { ledger.restore(op.transactionId, actor, ov); op }
        is LedgerOperation.Updated -> {
            ledger.update(TransactionDiffer.applyFields(current(op), op.after, op.changes), actor, ov); op
        }
        is LedgerOperation.Merged -> LedgerOperation.Merged(remerge(op.record, ov))
        is LedgerOperation.Unmerged -> LedgerOperation.Unmerged(ledger.unmerge(op.record.id, actor, ov))
    }

    /** Merge again with the same participants, re-applying exactly what the original merge changed. */
    private fun remerge(rec: MergeRecord, ov: Boolean): MergeRecord {
        val diff = TransactionDiffer.diff(rec.survivorBefore, rec.survivorAfter)
        return ledger.merge(rec.survivorId, rec.absorbed.map { it.id }, rec.actor, ov) { s, _ ->
            TransactionDiffer.applyFields(s, rec.survivorAfter, diff)
        }
    }
}
