package app.ledgerly.core.audit

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.Split
import java.time.Duration
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionLedgerTest {
    private val names = mapOf("food" to "Food", "groceries" to "Groceries")
    private val fmt = HistoryFormatter(IST, categoryName = { names[it] })

    @Test fun createLogsCreatedEvent() {
        val f = Fixture()
        f.ledger.create(txn("t1"), Actor.SYSTEM_PARSER)
        val e = f.log.history("t1").single()
        assertEquals(AuditAction.CREATED, e.action)
        assertEquals(Actor.SYSTEM_PARSER, e.actor)
        assertEquals("pixel", e.deviceId)
        assertEquals(f.clock.now, e.at)
    }

    @Test fun categoryOnlyChangeIsCategorisedWithHumanLine() {
        val f = Fixture()
        f.ledger.create(txn("t1", category = "food"))
        f.ledger.update(f.ledger.get("t1")!!.copy(categoryId = "groceries"))
        val e = f.log.history("t1").last()
        assertEquals(AuditAction.CATEGORISED, e.action)
        assertEquals("Category changed from Food to Groceries by you · 3 Nov 2026", fmt.line(e))
    }

    @Test fun actionClassification() {
        val f = Fixture()
        f.ledger.create(txn("t1"))
        f.ledger.update(f.ledger.get("t1")!!.copy(splits = listOf(Split("food", inr("50")), Split("home", inr("50")))))
        f.ledger.update(f.ledger.get("t1")!!.copy(isReviewed = true))
        f.ledger.update(f.ledger.get("t1")!!.copy(note = "x", merchant = "Y"))
        assertEquals(listOf(AuditAction.CREATED, AuditAction.SPLIT, AuditAction.REVIEWED, AuditAction.UPDATED),
            f.log.history("t1").map { it.action })
    }

    @Test fun noOpUpdateLogsNothing() {
        val f = Fixture()
        f.ledger.create(txn("t1"))
        f.ledger.update(txn("t1"))
        assertEquals(1, f.log.history("t1").size)
    }

    @Test fun amountChangeLineUsesHumanMoney() {
        val f = Fixture()
        f.ledger.create(txn("t1", "100"))
        f.ledger.update(f.ledger.get("t1")!!.copy(original = inr("1240")), Actor.SYNC)
        val line = fmt.line(f.log.history("t1").last())
        assertTrue(line.startsWith("Amount changed from ₹100 to ₹1,240"), line)
        assertTrue(line.endsWith("by sync · 3 Nov 2026"))
    }

    @Test fun softDeleteRestoreAndPurgeAfterRetention() {
        val f = Fixture()
        f.ledger.create(txn("t1"))
        f.ledger.softDelete("t1")
        assertTrue(f.ledger.get("t1")!!.isDeleted)
        f.ledger.restore("t1")
        assertNull(f.ledger.get("t1")!!.deletedAt)
        f.ledger.softDelete("t1")
        assertFailsWith<IllegalStateException> { f.ledger.purge("t1", Duration.ofDays(30)) }
        f.clock.advance(Duration.ofDays(30))
        f.ledger.purge("t1", Duration.ofDays(30))
        assertNull(f.ledger.get("t1"))
        assertEquals(listOf(AuditAction.CREATED, AuditAction.DELETED, AuditAction.RESTORED, AuditAction.DELETED, AuditAction.PURGED),
            f.log.history("t1").map { it.action })
    }

    @Test fun purgeOfActiveTransactionRejected() {
        val f = Fixture()
        f.ledger.create(txn("t1"))
        assertFailsWith<IllegalStateException> { f.ledger.purge("t1", Duration.ZERO) }
    }

    @Test fun updatingTrashedTransactionRejected() {
        val f = Fixture()
        f.ledger.create(txn("t1"))
        f.ledger.softDelete("t1")
        assertFailsWith<IllegalStateException> { f.ledger.update(txn("t1", "5").copy(deletedAt = null)) }
    }

    @Test fun mergeThenUnmergeRestoresOriginals() {
        val f = Fixture()
        val sms = txn("sms", merchant = null).copy(sourceEventIds = listOf("s1"), sourceChannels = setOf(Channel.SMS), reference = "UPI123")
        val notif = txn("notif", category = null, merchant = "Swiggy").copy(sourceEventIds = listOf("n1"), sourceChannels = setOf(Channel.NOTIFICATION), tags = setOf("upi"))
        f.ledger.create(sms); f.ledger.create(notif)
        val rec = f.ledger.merge("sms", listOf("notif"))
        val merged = f.ledger.get("sms")!!
        assertNull(f.ledger.get("notif"))
        assertEquals(listOf("s1", "n1"), merged.sourceEventIds)
        assertEquals(setOf(Channel.SMS, Channel.NOTIFICATION), merged.sourceChannels)
        assertEquals("Swiggy", merged.merchant)
        assertEquals(AuditAction.MERGED, f.log.history("notif").last().action)

        f.ledger.unmerge(rec.id)
        assertEquals(sms, f.ledger.get("sms"))
        assertEquals(notif, f.ledger.get("notif"))
        assertEquals(AuditAction.UNMERGED, f.log.history("sms").last().action)
        assertEquals(AuditAction.UNMERGED, f.log.history("notif").last().action)
        assertTrue(f.ledger.mergeRecord(rec.id)!!.undone)
        assertFailsWith<IllegalStateException> { f.ledger.unmerge(rec.id) }
    }

    @Test fun unmergeKeepsEditsMadeAfterMerge() {
        val f = Fixture()
        f.ledger.create(txn("a", merchant = null)); f.ledger.create(txn("b", merchant = "Zomato"))
        val rec = f.ledger.merge("a", listOf("b"))
        f.ledger.update(f.ledger.get("a")!!.copy(note = "dinner"))
        f.ledger.unmerge(rec.id)
        val a = f.ledger.get("a")!!
        assertNull(a.merchant)
        assertEquals("dinner", a.note)
    }

    @Test fun lockedPeriodEditRejectedWithoutOverride() {
        val f = Fixture()
        f.ledger.create(txn("old", whenAt = at(2026, 10, 15)))
        f.ledger.lockPeriod(YearMonth.of(2026, 10), reason = "reconciled")
        assertEquals(AuditAction.LOCKED, f.log.history("old").last().action)
        assertFailsWith<PeriodLockedException> { f.ledger.update(f.ledger.get("old")!!.copy(note = "x")) }
        assertFailsWith<PeriodLockedException> { f.ledger.softDelete("old") }
        assertFailsWith<PeriodLockedException> { f.ledger.create(txn("new", whenAt = at(2026, 10, 20))) }
        // moving a November txn into October is also an edit to the locked period
        f.ledger.create(txn("nov"))
        assertFailsWith<PeriodLockedException> { f.ledger.update(f.ledger.get("nov")!!.copy(occurredAt = at(2026, 10, 3))) }
        assertEquals(null, f.ledger.get("old")!!.note)
    }

    @Test fun lockedPeriodEditWithOverrideIsLogged() {
        val f = Fixture()
        f.ledger.create(txn("old", whenAt = at(2026, 10, 15)))
        f.ledger.lockPeriod(YearMonth.of(2026, 10))
        f.ledger.update(f.ledger.get("old")!!.copy(note = "late fix"), overrideLock = true)
        val e = f.log.history("old").last()
        assertTrue(e.lockOverride)
        assertTrue(fmt.line(e).endsWith("(locked period override)"))
        assertEquals("late fix", f.ledger.get("old")!!.note)
    }

    @Test fun unlockAllowsEditsAgain() {
        val f = Fixture()
        f.ledger.create(txn("old", whenAt = at(2026, 10, 15)))
        f.ledger.lockPeriod(YearMonth.of(2026, 10))
        assertTrue(f.ledger.unlockPeriod(YearMonth.of(2026, 10)))
        f.ledger.update(f.ledger.get("old")!!.copy(note = "ok"))
        assertTrue(!f.log.history("old").last().lockOverride)
    }
}
