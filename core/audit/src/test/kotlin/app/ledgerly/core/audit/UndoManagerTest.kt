package app.ledgerly.core.audit

import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoManagerTest {
    @Test fun undoDeleteRestores() {
        val f = Fixture(); val u = UndoManager(f.ledger)
        f.ledger.create(txn("t1"))
        f.ledger.softDelete("t1")
        u.undo()
        assertFalse(f.ledger.get("t1")!!.isDeleted)
        assertEquals(AuditAction.RESTORED, f.log.history("t1").last().action)
    }

    @Test fun undoUpdateRevertsDiffAndRedoReapplies() {
        val f = Fixture(); val u = UndoManager(f.ledger)
        f.ledger.create(txn("t1", category = "food"))
        f.ledger.update(f.ledger.get("t1")!!.copy(categoryId = "groceries", note = "weekly"))
        u.undo()
        assertEquals("food", f.ledger.get("t1")!!.categoryId)
        assertNull(f.ledger.get("t1")!!.note)
        u.redo()
        assertEquals("groceries", f.ledger.get("t1")!!.categoryId)
        assertEquals("weekly", f.ledger.get("t1")!!.note)
    }

    @Test fun undoCreateDeletesAndRedoRestores() {
        val f = Fixture(); val u = UndoManager(f.ledger)
        f.ledger.create(txn("t1"))
        u.undo()
        assertTrue(f.ledger.get("t1")!!.isDeleted)
        u.redo()
        assertFalse(f.ledger.get("t1")!!.isDeleted)
    }

    @Test fun multiStepUndoRedoSequence() {
        val f = Fixture(); val u = UndoManager(f.ledger)
        f.ledger.create(txn("t1", "100"))
        f.ledger.update(f.ledger.get("t1")!!.copy(original = inr("200")))
        f.ledger.update(f.ledger.get("t1")!!.copy(original = inr("300")))
        f.ledger.softDelete("t1")
        u.undo(); u.undo()
        assertEquals(inr("200"), f.ledger.get("t1")!!.original)
        assertFalse(f.ledger.get("t1")!!.isDeleted)
        u.redo()
        assertEquals(inr("300"), f.ledger.get("t1")!!.original)
        assertEquals(1, u.redoSize)
        u.undo(); u.undo(); u.undo()
        assertTrue(f.ledger.get("t1")!!.isDeleted) // undo of create
        assertEquals(inr("100"), f.ledger.get("t1")!!.original)
        assertNull(u.undo())
        assertEquals(4, u.redoSize)
    }

    @Test fun newOperationClearsRedoStack() {
        val f = Fixture(); val u = UndoManager(f.ledger)
        f.ledger.create(txn("t1"))
        f.ledger.update(f.ledger.get("t1")!!.copy(note = "a"))
        u.undo()
        assertTrue(u.canRedo)
        f.ledger.update(f.ledger.get("t1")!!.copy(note = "b"))
        assertFalse(u.canRedo)
        assertNull(u.redo())
    }

    @Test fun capacityDropsOldest() {
        val f = Fixture(); val u = UndoManager(f.ledger, capacity = 3)
        f.ledger.create(txn("t1"))
        repeat(5) { i -> f.ledger.update(f.ledger.get("t1")!!.copy(note = "n$i")) }
        assertEquals(3, u.undoSize)
        repeat(3) { u.undo() }
        assertEquals("n1", f.ledger.get("t1")!!.note)
        assertFalse(u.canUndo)
    }

    @Test fun undoMergeUnmergesAndRedoMergesAgain() {
        val f = Fixture(); val u = UndoManager(f.ledger)
        val a = txn("a", merchant = null).copy(sourceEventIds = listOf("s1"))
        val b = txn("b", merchant = "Uber").copy(sourceEventIds = listOf("n1"))
        f.ledger.create(a); f.ledger.create(b)
        f.ledger.merge("a", listOf("b"))
        u.undo()
        assertEquals(a, f.ledger.get("a"))
        assertEquals(b, f.ledger.get("b"))
        u.redo()
        assertNull(f.ledger.get("b"))
        assertEquals("Uber", f.ledger.get("a")!!.merchant)
        u.undo()
        assertEquals(b, f.ledger.get("b"))
        assertEquals(2, f.ledger.mergeRecords().size)
    }

    @Test fun undoIntoLockedPeriodFailsAndKeepsStack() {
        val f = Fixture(); val u = UndoManager(f.ledger)
        f.ledger.create(txn("t1", whenAt = at(2026, 10, 10)))
        f.ledger.update(f.ledger.get("t1")!!.copy(note = "x"))
        f.ledger.lockPeriod(YearMonth.of(2026, 10))
        assertFailsWith<PeriodLockedException> { u.undo() }
        assertEquals(2, u.undoSize)
        u.undo(overrideLock = true)
        assertNull(f.ledger.get("t1")!!.note)
        assertTrue(f.log.history("t1").last().lockOverride)
    }

    @Test fun undoRevertKeepsUnrelatedLaterEdits() {
        val f = Fixture(); val u = UndoManager(f.ledger)
        f.ledger.create(txn("t1"))
        f.ledger.update(f.ledger.get("t1")!!.copy(categoryId = "travel"))
        u.detach()
        f.ledger.update(f.ledger.get("t1")!!.copy(note = "sync edit"), Actor.SYNC)
        u.undo()
        assertEquals("food", f.ledger.get("t1")!!.categoryId)
        assertEquals("sync edit", f.ledger.get("t1")!!.note)
    }
}
