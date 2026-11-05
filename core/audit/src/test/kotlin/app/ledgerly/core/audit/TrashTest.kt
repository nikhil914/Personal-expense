package app.ledgerly.core.audit

import java.time.Duration
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrashTest {
    @Test fun listsNewestFirstWithDaysLeft() {
        val f = Fixture(); val trash = Trash(f.ledger, f.clock)
        f.ledger.create(txn("a")); f.ledger.create(txn("b"))
        f.ledger.softDelete("a")
        f.clock.advance(Duration.ofDays(2))
        f.ledger.softDelete("b")
        val items = trash.list()
        assertEquals(listOf("b", "a"), items.map { it.transaction.id })
        assertEquals(30, items[0].daysLeft)
        assertEquals(28, items[1].daysLeft)
    }

    @Test fun restoreFromTrash() {
        val f = Fixture(); val trash = Trash(f.ledger, f.clock)
        f.ledger.create(txn("a"))
        assertFailsWith<IllegalStateException> { trash.restore("a") }
        f.ledger.softDelete("a")
        trash.restore("a")
        assertTrue(trash.list().isEmpty())
    }

    @Test fun autoPurgeAfterConfigurableRetention() {
        val f = Fixture(); val trash = Trash(f.ledger, f.clock, Duration.ofDays(7))
        f.ledger.create(txn("a")); f.ledger.create(txn("b"))
        f.ledger.softDelete("a")
        f.clock.advance(Duration.ofDays(3)); f.ledger.softDelete("b")
        f.clock.advance(Duration.ofDays(4))
        assertEquals(listOf("a"), trash.purgeExpired())
        assertNull(f.ledger.get("a"))
        assertEquals(listOf("b"), trash.list().map { it.transaction.id })
        assertEquals(AuditAction.PURGED, f.log.history("a").last().action) // history survives purge
    }

    @Test fun default30DaysAndLockedPeriodsSkipped() {
        val f = Fixture(); val trash = Trash(f.ledger, f.clock)
        f.ledger.create(txn("oct", whenAt = at(2026, 10, 2))); f.ledger.create(txn("nov"))
        f.ledger.softDelete("oct"); f.ledger.softDelete("nov")
        f.ledger.lockPeriod(YearMonth.of(2026, 10))
        f.clock.advance(Duration.ofDays(29))
        assertTrue(trash.purgeExpired().isEmpty())
        f.clock.advance(Duration.ofDays(1))
        assertEquals(listOf("nov"), trash.purgeExpired())
        assertEquals(listOf("oct"), trash.list().map { it.transaction.id })
    }

    @Test fun emptyNowIgnoresRetention() {
        val f = Fixture(); val trash = Trash(f.ledger, f.clock)
        f.ledger.create(txn("a")); f.ledger.softDelete("a")
        assertEquals(listOf("a"), trash.emptyNow())
        assertNull(f.ledger.get("a"))
    }
}
