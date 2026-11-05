package app.ledgerly.core.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuditLogTest {
    private fun ev(id: String, txn: String, actor: Actor, h: Int) =
        TransactionEvent(id, txn, actor, "dev", at(2026, 11, 1, h), AuditAction.UPDATED)

    @Test fun queriesByTransactionActorAndTime() {
        val log = InMemoryAuditLog()
        log.append(ev("1", "t1", Actor.USER, 9))
        log.append(ev("2", "t2", Actor.SYSTEM_PARSER, 10))
        log.append(ev("3", "t1", Actor.SYSTEM_PARSER, 11))
        assertEquals(listOf("1", "3"), log.history("t1").map { it.id })
        assertEquals(listOf("2", "3"), log.byActor(Actor.SYSTEM_PARSER).map { it.id })
        assertEquals(listOf("2"), log.between(at(2026, 11, 1, 10), at(2026, 11, 1, 11)).map { it.id })
        assertEquals(3, log.size)
    }

    @Test fun duplicateIdsRejected() {
        val log = InMemoryAuditLog()
        log.append(ev("1", "t1", Actor.USER, 9))
        assertFailsWith<IllegalArgumentException> { log.append(ev("1", "t1", Actor.USER, 10)) }
    }

    @Test fun returnedListsCannotMutateHistory() {
        val log = InMemoryAuditLog()
        log.append(ev("1", "t1", Actor.USER, 9))
        val h = log.history("t1")
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (h as MutableList<TransactionEvent>).clear() }
        assertEquals(1, log.history("t1").size)
    }

    @Test fun callerHeldDiffListCannotRewriteHistory() {
        val log = InMemoryAuditLog()
        val diff = mutableListOf(FieldChange("note", null, "a"))
        log.append(TransactionEvent("1", "t1", Actor.USER, "d", at(2026, 11, 1), AuditAction.UPDATED, diff))
        diff[0] = FieldChange("note", null, "tampered")
        assertEquals("a", log.history("t1").single().diff.single().new)
        val stored = log.history("t1").single().diff
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (stored as MutableList<FieldChange>).add(FieldChange("x", null, null)) }
    }

    @Test fun apiHasNoMutationMethods() {
        val names = AuditLog::class.java.methods.map { it.name }.toSet()
        assertTrue(names.none { it.startsWith("remove") || it.startsWith("delete") || it.startsWith("update") || it.startsWith("clear") || it.startsWith("set") })
    }
}
