package app.ledgerly.engine

import app.ledgerly.core.model.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CoreAdaptersTest {
    private val t0 = Instant.parse("2026-11-03T10:00:00Z")

    private fun txn(id: String, channel: Channel, merchant: String, at: Instant = t0, amount: String = "850") = Transaction(
        id = id, accountId = "hdfc", type = TxnType.EXPENSE, occurredAt = at,
        original = Money.of(amount, "INR"), merchantRaw = merchant, accountLast4 = "1234",
        sourceEventIds = listOf("ev-$id"), sourceChannels = setOf(channel),
    )

    @Test
    fun parserReadsBankSms() {
        val p = CoreParserAdapter().parse(
            SourceEvent("e1", Channel.SMS, "AX-HDFCBK", null, null,
                "Rs.850.00 spent on HDFC Bank Card x1234 at SWIGGY on 03-11-26. Avl bal Rs.10,000.00", t0),
            emptyList(),
        )
        assertEquals(Money.of("850", "INR"), p.amount)
        assertEquals("1234", p.accountLast4)
    }

    @Test
    fun parserIgnoresOtp() {
        val p = CoreParserAdapter().parse(
            SourceEvent("e2", Channel.SMS, "AX-HDFCBK", null, null, "123456 is your OTP for txn of Rs 500. Do not share.", t0),
            emptyList(),
        )
        assertEquals(ParseStatus.IGNORED_OTP, p.status)
    }

    @Test
    fun sameSwipeOnTwoChannelsMergesWithBothSources() {
        val d = CoreDeduperAdapter()
        val sms = txn("a", Channel.SMS, "SWIGGY BANGALORE")
        val notif = txn("b", Channel.NOTIFICATION, "Swiggy", at = t0.plusSeconds(60))
        val decision = d.decide(notif, listOf(sms))
        assertIs<DedupDecision.Merge>(decision)
        val merged = d.merge(sms, notif)
        assertEquals(setOf(Channel.SMS, Channel.NOTIFICATION), merged.sourceChannels)
        assertTrue(merged.sourceEventIds.containsAll(listOf("ev-a", "ev-b")))
    }

    @Test
    fun differentAmountsStaySeparate() {
        val decision = CoreDeduperAdapter().decide(txn("b", Channel.NOTIFICATION, "Swiggy", amount = "120"), listOf(txn("a", Channel.SMS, "Swiggy")))
        assertEquals(DedupDecision.New, decision)
    }
}
