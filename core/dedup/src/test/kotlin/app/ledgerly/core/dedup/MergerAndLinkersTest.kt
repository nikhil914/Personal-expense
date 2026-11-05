package app.ledgerly.core.dedup

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.Channel.EMAIL
import app.ledgerly.core.model.Channel.NOTIFICATION
import app.ledgerly.core.model.Channel.SMS
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergerTest {
    @Test fun `never overwrites locked user-edited fields`() {
        val t = txn("t1", inr("1249.00"), at(10, 20, 15), "My Swiggy Order", channels = setOf(SMS))
            .copy(categoryId = "food", note = "dinner with A")
        val c = cand("e1", EMAIL, inr("1249.00"), at(10, 20, 10), "Swiggy Limited", ref = "X12345")
        val r = Merger.merge(t, c, locked = setOf(Fields.MERCHANT_RAW, Fields.OCCURRED_AT))
        assertEquals("My Swiggy Order", r.transaction.merchantRaw)
        assertEquals(at(10, 20, 15), r.transaction.occurredAt)
        assertEquals("food", r.transaction.categoryId)
        assertEquals("dinner with A", r.transaction.note)
        assertFalse(Fields.MERCHANT_RAW in r.changedFields)
    }

    @Test fun `keeps SMS balance and email line items in result`() {
        val t = txn("t1", inr("2100.00"), at(10, 9), "AMAZON", channels = setOf(NOTIFICATION))
        val items = listOf(LineItem("USB-C cable", inr("600.00")), LineItem("Book", inr("1500.00")))
        val r1 = Merger.merge(t, cand("e1", EMAIL, inr("2100.00"), at(10, 9, 3), "Amazon.in order #403-1", text = null).copy(lineItems = items))
        assertEquals(items, r1.lineItems)
        val r2 = Merger.merge(r1.transaction, cand("s1", SMS, inr("2100.00"), at(10, 9), "AMAZON", ref = "RRN1234567", balance = inr("12000.00")))
        assertEquals(inr("12000.00"), r2.balanceAfter)
        assertEquals("RRN1234567", r2.transaction.reference)
        assertEquals("Amazon.in order #403-1", r2.transaction.merchantRaw) // SMS doesn't beat email
    }

    @Test fun `SMS reference beats email reference`() {
        val t = txn("t1", inr("100.00"), at(10, 9), "X", channels = setOf(EMAIL), ref = "ORDER-9")
        assertEquals("RRN555555", Merger.merge(t, cand("s1", SMS, inr("100.00"), at(10, 9), "X", ref = "RRN555555")).transaction.reference)
        val t2 = txn("t2", inr("100.00"), at(10, 9), "X", channels = setOf(SMS), ref = "RRN555555")
        assertEquals("RRN555555", Merger.merge(t2, cand("e1", EMAIL, inr("100.00"), at(10, 9), "X", ref = "ORDER-9")).transaction.reference)
    }

    @Test fun `unions sources without duplicates and fills missing account`() {
        val t = txn("t1", inr("100.00"), at(10, 9), "X", accountId = null)
        val c = cand("s1", NOTIFICATION, inr("100.00"), at(10, 9), "X", accountId = "acc-9")
        val r = Merger.merge(Merger.merge(t, c).transaction, c)
        assertEquals(listOf("ev-t1", "s1"), r.transaction.sourceEventIds)
        assertEquals(setOf(SMS, NOTIFICATION), r.transaction.sourceChannels)
        assertEquals("acc-9", r.transaction.accountId)
    }

    @Test fun `cross currency candidate fills original and flags re-enrichment`() {
        val t = txn("t1", inr("4212.30"), at(10, 3), "OPENAI", channels = setOf(NOTIFICATION))
        val r = Merger.merge(t, cand("s1", SMS, usd("45.00"), at(10, 3), "OPENAI", accountAmount = inr("4212.30")))
        assertEquals(usd("45.00"), r.transaction.original)
        assertEquals(inr("4212.30"), r.transaction.account)
        assertTrue(r.needsFxReenrich)
        val locked = Merger.merge(t, cand("s1", SMS, usd("45.00"), at(10, 3), "OPENAI", accountAmount = inr("4212.30")), setOf(Fields.ORIGINAL))
        assertEquals(inr("4212.30"), locked.transaction.original)
    }
}

class AuthSettlementTest {
    private val hotelAuth = txn("auth1", inr("15000.00"), at(3, 14), "TAJ HOTELS MUMBAI", status = TxnStatus.PENDING)

    @Test fun `hotel pre-auth settled for lower amount updates instead of new`() {
        val settle = cand("s9", SMS, inr("12480.00"), at(5, 11), "TAJ HOTELS", kind = MessageKind.CARD_SETTLEMENT)
        val r = DedupEngine().match(settle, listOf(hotelAuth))
        assertIs<MatchResult.SettleAuth>(r)
        assertEquals("auth1", r.txnId)
        val updated = AuthSettlementLinker().apply(hotelAuth, settle).transaction
        assertEquals(inr("12480.00"), updated.original)
        assertEquals(inr("12480.00"), updated.account)
        assertEquals(TxnStatus.POSTED, updated.status)
        assertEquals(at(3, 14), updated.occurredAt)
        assertEquals(listOf("ev-auth1", "s9"), updated.sourceEventIds)
    }

    @Test fun `settlement after 7 days is not linked`() {
        val settle = cand("s9", SMS, inr("12480.00"), at(11, 15), "TAJ HOTELS", kind = MessageKind.CARD_SETTLEMENT)
        assertNull(AuthSettlementLinker().find(settle, listOf(hotelAuth)))
    }

    @Test fun `settlement much larger than auth or other merchant is not linked`() {
        val linker = AuthSettlementLinker()
        assertNull(linker.find(cand("s9", SMS, inr("25000.00"), at(4, 11), "TAJ HOTELS", kind = MessageKind.CARD_SETTLEMENT), listOf(hotelAuth)))
        assertNull(linker.find(cand("s9", SMS, inr("12000.00"), at(4, 11), "MARRIOTT", kind = MessageKind.CARD_SETTLEMENT), listOf(hotelAuth)))
        assertNull(linker.find(cand("s9", SMS, inr("12000.00"), at(4, 11), "TAJ HOTELS", last4 = "9999", accountId = null, kind = MessageKind.CARD_SETTLEMENT), listOf(hotelAuth)))
    }

    @Test fun `settlement in different currency uses rate check`() {
        val auth = txn("a2", usd("200.00"), at(3, 14), "HERTZ SFO", account = inr("18400.00"), status = TxnStatus.PENDING)
        val linker = AuthSettlementLinker(rate = { _, _, _ -> BigDecimal("1.08") })
        val settle = cand("s1", SMS, CurrencyCode.EUR.let { app.ledgerly.core.model.Money.of("170.00", it) }, at(6, 9), "HERTZ", kind = MessageKind.CARD_SETTLEMENT)
        val m = assertNotNull(linker.find(settle, listOf(auth)))
        assertEquals("a2", m.txnId)
        val applied = linker.apply(auth, settle)
        assertTrue(applied.needsFxReenrich)
        assertEquals("EUR", applied.transaction.original.currency.code)
        assertEquals(inr("18400.00"), applied.transaction.account) // unknown until re-enriched
    }

    @Test fun `settlement with matching auth code links even if merchant renamed`() {
        val auth = hotelAuth.copy(reference = "AUTH 884421", merchantRaw = "TAJMAHAL PALACE")
        val r = DedupEngine().match(cand("s9", SMS, inr("14000.00"), at(4, 9), "IHCL", ref = "884421", kind = MessageKind.CARD_SETTLEMENT), listOf(auth))
        assertIs<MatchResult.SettleAuth>(r)
    }
}

class TransferDetectorTest {
    private val accounts = mapOf(
        "hdfc-sb" to Account("hdfc-sb", "HDFC Savings", AccountType.BANK, CurrencyCode.INR),
        "icici-sb" to Account("icici-sb", "ICICI Savings", AccountType.BANK, CurrencyCode.INR),
        "hdfc-cc" to Account("hdfc-cc", "HDFC Regalia", AccountType.CREDIT_CARD, CurrencyCode.INR),
        "wise-usd" to Account("wise-usd", "Wise USD", AccountType.WALLET, CurrencyCode.USD),
    )

    @Test fun `own account transfer detected with group id`() {
        val debit = txn("d1", inr("25000.00"), at(10, 10), "IMPS-SELF-ICICI", accountId = "hdfc-sb")
        val credit = txn("c1", inr("25000.00"), at(10, 10, 1), "IMPS FROM HDFC", accountId = "icici-sb", type = TxnType.INCOME)
        val s = TransferDetector().detect(listOf(debit, credit), accounts).single()
        assertEquals("d1", s.debitId); assertEquals("c1", s.creditId)
        assertEquals(TransferKind.OWN_TRANSFER, s.kind)
        assertTrue(s.confidence >= 0.85)
        assertEquals("tg_d1_c1", s.transferGroupId)
    }

    @Test fun `credit card bill payment detected`() {
        val bank = txn("d1", inr("48230.00"), at(10, 9), "BILLDESK HDFC CREDIT CARD", accountId = "hdfc-sb")
        val card = txn("c1", inr("48230.00"), at(11, 8), "PAYMENT RECEIVED - THANK YOU", accountId = "hdfc-cc", type = TxnType.INCOME)
        val s = TransferDetector().detect(listOf(bank, card), accounts).single()
        assertEquals(TransferKind.CARD_BILL_PAYMENT, s.kind)
        assertTrue(s.confidence >= 0.9)
    }

    @Test fun `no transfer when outside window amount differs or same account`() {
        val d = txn("d1", inr("5000.00"), at(10, 9), "NEFT", accountId = "hdfc-sb")
        val det = TransferDetector()
        assertTrue(det.detect(listOf(d, txn("c1", inr("5000.00"), at(13, 9), "NEFT", accountId = "icici-sb", type = TxnType.INCOME)), accounts).isEmpty())
        assertTrue(det.detect(listOf(d, txn("c1", inr("5001.00"), at(10, 9), "NEFT", accountId = "icici-sb", type = TxnType.INCOME)), accounts).isEmpty())
        assertTrue(det.detect(listOf(d, txn("c1", inr("5000.00"), at(10, 9), "NEFT", accountId = "hdfc-sb", type = TxnType.INCOME)), accounts).isEmpty())
    }

    @Test fun `fx equivalent transfer within tolerance`() {
        val d = txn("d1", inr("92500.00"), at(10, 9), "WISE TOPUP", accountId = "hdfc-sb")
        val c = txn("c1", usd("995.00"), at(11, 9), "Received from HDFC", accountId = "wise-usd", type = TxnType.INCOME)
        val det = TransferDetector(rate = { _, _, _ -> BigDecimal("0.010870") }) // ~92 INR/USD
        val s = assertNotNull(det.detect(listOf(d, c), accounts).singleOrNull())
        assertEquals(TransferKind.OWN_TRANSFER, s.kind)
        assertTrue(TransferDetector().detect(listOf(d, c), accounts).isEmpty()) // no rate fn -> no FX match
        val far = c.copy(original = usd("900.00"), account = usd("900.00"), base = usd("900.00"))
        assertTrue(det.detect(listOf(d, far), accounts).isEmpty())
    }

    @Test fun `greedy one-to-one assignment and detectFor`() {
        val d1 = txn("d1", inr("1000.00"), at(10, 9), "TRANSFER", accountId = "hdfc-sb")
        val d2 = txn("d2", inr("1000.00"), at(10, 18), "TRANSFER", accountId = "hdfc-sb")
        val c1 = txn("c1", inr("1000.00"), at(10, 9, 1), "TRANSFER", accountId = "icici-sb", type = TxnType.INCOME)
        val res = TransferDetector().detect(listOf(d1, d2, c1), accounts)
        assertEquals(1, res.size)
        assertEquals("d1", res.single().debitId)
        assertEquals("d1", TransferDetector().detectFor(c1, listOf(d1, d2), accounts)!!.debitId)
    }
}

class RefundLinkerTest {
    private val order = txn("o1", inr("3499.00"), at(1, 12), "AMAZON PAY INDIA")

    @Test fun `full refund links to original`() {
        val refund = txn("r1", inr("3499.00"), at(12, 10), "Amazon refund", type = TxnType.REFUND)
        val l = assertNotNull(RefundLinker().link(refund, listOf(order, refund)))
        assertEquals("o1", l.originalId)
        assertFalse(l.partial)
        assertEquals(inr("0.00"), l.remainingAfter)
    }

    @Test fun `partial refunds track remaining refundable`() {
        val r1 = txn("r1", inr("1000.00"), at(5, 10), "AMAZON", type = TxnType.REFUND, refundOf = "o1")
        val r2 = txn("r2", inr("2000.00"), at(8, 10), "AMAZON", type = TxnType.REFUND)
        val l = assertNotNull(RefundLinker().link(r2, listOf(order, r1, r2)))
        assertTrue(l.partial)
        assertEquals(inr("499.00"), l.remainingAfter)
        val r3 = txn("r3", inr("600.00"), at(9, 10), "AMAZON", type = TxnType.REFUND)
        assertNull(RefundLinker().link(r3, listOf(order, r1, r2.copy(refundOfId = "o1"), r3)))
    }

    @Test fun `refund larger than original or older than 90 days or other merchant is not linked`() {
        val linker = RefundLinker()
        assertNull(linker.link(txn("r", inr("4000.00"), at(12, 10), "AMAZON", type = TxnType.REFUND), listOf(order)))
        assertNull(linker.link(txn("r", inr("100.00"), at(1, 10, month = 7), "AMAZON", type = TxnType.REFUND), listOf(order)))
        assertNull(linker.link(txn("r", inr("100.00"), at(12, 10), "FLIPKART", type = TxnType.REFUND), listOf(order)))
    }

    @Test fun `credit with refund keyword counts and picks exact amount match`() {
        val small = txn("o2", inr("499.00"), at(3, 12), "AMAZON")
        val credit = txn("c", inr("499.00"), at(6, 10), "REFUND AMAZON", type = TxnType.INCOME)
        assertEquals("o2", RefundLinker().link(credit, listOf(order, small))!!.originalId)
        assertNull(RefundLinker().link(txn("c2", inr("499.00"), at(6, 10), "AMAZON", type = TxnType.INCOME), listOf(order)))
    }
}

class SourceBadgeTest {
    @Test fun `badge text ordering and merged label`() {
        assertEquals("SMS + Notification + Email", SourceBadge.text(setOf(EMAIL, SMS, NOTIFICATION)))
        val t = txn("t", inr("1.00"), at(1, 1), "X", channels = setOf(NOTIFICATION, SMS))
        assertTrue(SourceBadge.isMerged(t))
        assertEquals("Merged from SMS + Notification", SourceBadge.mergedFrom(t))
        assertNull(SourceBadge.mergedFrom(t.copy(sourceChannels = setOf(Channel.MANUAL))))
        assertEquals("Manual", SourceBadge.text(setOf(Channel.MANUAL)))
    }

    @Test fun `direction resolver defaults`() {
        assertEquals(TxnDirection.CREDIT, DirectionResolver.DEFAULT.of(txn("a", inr("1.00"), at(1, 1), "x", type = TxnType.REFUND)))
        assertEquals(TxnDirection.DEBIT, DirectionResolver.DEFAULT.of(txn("a", inr("1.00"), at(1, 1), "x", type = TxnType.ATM_WITHDRAWAL)))
        assertNull(DirectionResolver.DEFAULT.of(txn("a", inr("1.00"), at(1, 1), "x", type = TxnType.TRANSFER)))
    }
}
