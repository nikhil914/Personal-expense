package app.ledgerly.core.audit

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.ParseStatus
import app.ledgerly.core.model.ParsedTransaction
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IgnoredInboxTest {
    private fun sms(id: String, sender: String, body: String = "msg") =
        SourceEvent(id, Channel.SMS, sender, null, body = body, receivedAt = at(2026, 11, 2))

    @Test fun keepsReasonAndLists() {
        val f = Fixture(); val inbox = IgnoredInbox(f.clock)
        inbox.add(sms("m1", "VM-HDFCBK"), IgnoreReason.PARSE_FAILED, "no amount found")
        inbox.add(sms("m2", "AD-OFFERS"), IgnoreReason.PROMO)
        assertEquals(2, inbox.list().size)
        assertEquals("no amount found", inbox.list(IgnoreReason.PARSE_FAILED).single().detail)
    }

    @Test fun alwaysIgnoreSenderNormalisesDltPrefix() {
        val f = Fixture(); val inbox = IgnoredInbox(f.clock)
        inbox.add(sms("m1", "VM-OFFERS"), IgnoreReason.PROMO)
        inbox.add(sms("m2", "AD-OFFERS-P"), IgnoreReason.PROMO)
        inbox.add(sms("m3", "VM-HDFCBK"), IgnoreReason.PARSE_FAILED)
        assertEquals(2, inbox.alwaysIgnoreSender("JD-OFFERS"))
        assertEquals(listOf("m3"), inbox.list().map { it.id })
        assertNull(inbox.add(sms("m4", "BZ-OFFERS"), IgnoreReason.PROMO))
        assertTrue(inbox.isSenderIgnored("offers"))
    }

    @Test fun convertUsesParsedFieldsAndLogsCreate() {
        val f = Fixture(); val inbox = IgnoredInbox(f.clock)
        val parsed = ParsedTransaction("m1", Channel.SMS, MessageKind.DEBIT, TxnDirection.DEBIT, inr("450"),
            merchantRaw = "SWIGGY*BLR", occurredAt = at(2026, 11, 2, 9), confidence = 0.4, parserId = "heuristic",
            status = ParseStatus.NEEDS_REVIEW, reference = "R1")
        inbox.add(sms("m1", "VM-HDFCBK"), IgnoreReason.LOW_CONFIDENCE, parsed = parsed)
        val t = inbox.convertToTransaction("m1", f.ledger, "t1", categoryId = "food", accountId = "hdfc")
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals(inr("450"), t.base)
        assertEquals(listOf("m1"), t.sourceEventIds)
        assertEquals("R1", t.reference)
        assertTrue(inbox.list().isEmpty())
        assertEquals(AuditAction.CREATED, f.log.history("t1").single().action)
        assertEquals(Actor.USER, f.log.history("t1").single().actor)
    }

    @Test fun convertWithoutAmountNeedsOne() {
        val f = Fixture(); val inbox = IgnoredInbox(f.clock)
        inbox.add(sms("m1", "VM-HDFCBK"), IgnoreReason.PARSE_FAILED)
        assertFailsWith<IllegalArgumentException> { inbox.convertToTransaction("m1", f.ledger, "t1") }
        val t = inbox.convertToTransaction("m1", f.ledger, "t1", amount = inr("99"), type = TxnType.INCOME)
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(at(2026, 11, 2), t.occurredAt)
    }

    @Test fun dismissAndUnblock() {
        val f = Fixture(); val inbox = IgnoredInbox(f.clock)
        inbox.add(sms("m1", "VM-X"), IgnoreReason.OTP)
        assertTrue(inbox.dismiss("m1"))
        inbox.alwaysIgnoreSender("VM-X")
        assertTrue(inbox.unblockSender("x"))
        assertEquals("m2", inbox.add(sms("m2", "VM-X"), IgnoreReason.OTP)!!.id)
    }
}
