package app.ledgerly.core.parser

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.ParseStatus
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.TxnDirection
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionParserTest {
    private val parser = TransactionParser()

    @Test
    fun `otp containing an amount is ignored`() {
        val t = parser.parse(sms("482913 is your OTP for txn of Rs 2,500.00 at AMAZON on HDFC Bank card ending 1234. Valid till 10:32. Do not share OTP.", "AX-HDFCBK"))
        assertEquals(ParseStatus.IGNORED_OTP, t.status)
        assertEquals(MessageKind.OTP, t.kind)
        assertNull(t.amount)
    }

    @Test
    fun `cashback promo is ignored`() {
        val t = parser.parse(sms("Get Rs 500 cashback on your first credit card bill payment via PayZapp. T&C apply. Visit hdfc.bank/pz", "AX-HDFCBK"))
        assertEquals(ParseStatus.IGNORED_PROMO, t.status)
        assertNull(t.amount)
    }

    @Test
    fun `transaction alert that says never share otp is still parsed`() {
        val t = parser.parse(sms("Rs.1,250.00 spent on HDFC Bank Card x1234 at AMAZON PAY INDIA on 2026-11-03:14:22:10. Never share your OTP/CVV with anyone.", "AX-HDFCBK"))
        assertEquals(ParseStatus.PARSED, t.status)
        assertEquals(inr("1250"), t.amount)
    }

    @Test
    fun `cashback that was credited is a credit, not a promo`() {
        val t = parser.parse(sms("Cashback of Rs 50.00 has been credited to your A/c XX1234 on 03-11-26 for your UPI payment", "AX-HDFCBK"))
        assertEquals(TxnDirection.CREDIT, t.direction)
        assertEquals(inr("50"), t.amount)
    }

    @Test
    fun `failed transaction is info only and books nothing`() {
        val t = parser.parse(sms("Your transaction of Rs 500 at FLIPKART using card XX1234 has failed. Amount debited, if any, will be reversed within 5-7 working days.", "AX-HDFCBK"))
        assertEquals(MessageKind.FAILED_TXN, t.kind)
        assertEquals(inr("500"), t.amount)
        assertTrue(t.status == ParseStatus.INFO_ONLY || t.status == ParseStatus.NEEDS_REVIEW)
        assertTrue(t.status != ParseStatus.PARSED)
    }

    @Test
    fun `simple failed transaction text`() {
        val t = parser.parse(sms("transaction of Rs 500 failed", "AX-HDFCBK"))
        assertEquals(MessageKind.FAILED_TXN, t.kind)
        assertTrue(t.status != ParseStatus.PARSED)
    }

    @Test
    fun `reversal is a credit`() {
        val t = parser.parse(sms("Rs 500.00 reversed to your A/c XX1234 on 03-11-26 against failed UPI txn Ref 412312341234", "AX-HDFCBK"))
        assertEquals(MessageKind.REVERSAL, t.kind)
        assertEquals(TxnDirection.CREDIT, t.direction)
        assertEquals(inr("500"), t.amount)
        assertEquals("1234", t.accountLast4)
        assertEquals("412312341234", t.reference)
    }

    @Test
    fun `credit card payment received`() {
        val t = parser.parse(sms("Payment of Rs 15,000.00 received on your ICICI Bank Credit Card XX5678 on 03-Nov-26. Thank you.", "JD-ICICIB"))
        assertEquals(MessageKind.CREDIT, t.kind)
        assertEquals(TxnDirection.CREDIT, t.direction)
        assertEquals("ICICI Bank", t.institution)
    }

    @Test
    fun `lakh amounts`() {
        val t = parser.parse(sms("Update! INR 12,34,567.89 debited from HDFC Bank XX1234 on 03-NOV-26. Info: RTGS-HDFCR5202611031-BUILDER LLP. Avl bal:INR 1,00,000.00", "AD-HDFCBK"))
        assertEquals(inr("1234567.89"), t.amount)
        assertEquals(inr("100000"), t.balanceAfter)
    }

    @Test
    fun `missing last4 still parses amount and merchant`() {
        val t = parser.parse(notif(Pkg.PAYTM, "Paytm", "Paid Rs.120 to Chai Point"))
        assertEquals(inr("120"), t.amount)
        assertEquals("Chai Point", t.merchantRaw)
        assertNull(t.accountLast4)
        assertEquals(ParseStatus.PARSED, t.status)
    }

    @Test
    fun `notification title plus body`() {
        val t = parser.parse(notif(Pkg.GPAY, "₹349 paid to Domino's Pizza", "Paid with HDFC Bank •• 1234"))
        assertEquals(inr("349"), t.amount)
        assertEquals(MessageKind.UPI_SENT, t.kind)
        assertEquals("Domino's Pizza", t.merchantRaw)
        val r = parser.parseDetailed(notif(Pkg.GPAY, "₹349 paid to Domino's Pizza", "Paid with HDFC Bank •• 1234"))
        assertEquals("Domino's", r.merchant?.name)
        assertEquals("Food/Dining", r.merchant?.categoryHint)
    }

    @Test
    fun `revolut notification`() {
        val r = parser.parseDetailed(notif(Pkg.REVOLUT, "Revolut", "You paid €12.50 at Lidl 💳"))
        assertEquals(money("12.50", "EUR"), r.transaction.amount)
        assertEquals("Lidl", r.transaction.merchantRaw)
        assertEquals("Lidl", r.merchant?.name)
        assertEquals("Revolut", r.transaction.institution)
    }

    @Test
    fun `non-allowlisted app notification is ignored`() {
        val t = parser.parse(notif("com.whatsapp", "Ravi", "Rs 500 debited from your account lol"))
        assertEquals(ParseStatus.IGNORED_NOT_FINANCIAL, t.status)
    }

    @Test
    fun `unknown sms sender goes to review with capped confidence`() {
        val t = parser.parse(sms("Rs.1,250.00 spent on HDFC Bank Card x1234 at AMAZON on 2026-11-03", "+919845012345"))
        assertEquals(ParseStatus.NEEDS_REVIEW, t.status)
        assertTrue(t.confidence <= 0.6)
    }

    @Test
    fun `unknown sms sender ignored when policy is ignore`() {
        val p = TransactionParser(ParserConfig(unknownSmsSenderPolicy = UnknownSenderPolicy.IGNORE))
        assertEquals(ParseStatus.IGNORED_NOT_FINANCIAL, p.parse(sms("Rs 500 debited", "+919845012345")).status)
    }

    @Test
    fun `non financial message from bank is ignored`() {
        val t = parser.parse(sms("Dear Customer, our branch will remain closed on 08-11-26 on account of a holiday.", "AX-HDFCBK"))
        assertEquals(ParseStatus.IGNORED_NOT_FINANCIAL, t.status)
    }

    @Test
    fun `heuristic fallback with review status for vague text`() {
        val t = parser.parse(sms("Rs 250 paid via UPI", "AX-HDFCBK"))
        assertEquals(inr("250"), t.amount)
        assertEquals("heuristic", t.parserId)
        assertEquals(ParseStatus.NEEDS_REVIEW, t.status)
    }

    @Test
    fun `unknown format parsed confidently by heuristic`() {
        val t = parser.parse(sms("Dear Customer, your a/c no. XXXXXX5521 is debited by Rs.2,345.00 on 02/11/2026 towards PAYMENT TO LANDLORD. Avl Bal Rs.2,500.00. RRN 630712345678", "AX-HDFCBK"))
        assertEquals("heuristic", t.parserId)
        assertEquals(ParseStatus.PARSED, t.status)
        assertEquals(inr("2345"), t.amount)
        assertEquals("HDFC Bank", t.institution)
    }

    @Test
    fun `bill due is info only with due date`() {
        val t = parser.parse(sms("Your ICICI Bank Credit Card XX5678 bill of Rs 8,765.43 is due on 15-Nov-26. Min amount due Rs 440.00. Ignore if paid.", "VM-ICICIT"))
        assertEquals(ParseStatus.INFO_ONLY, t.status)
        assertNotNull(t.dueDate)
    }

    @Test
    fun `autopay mandate creation is info only`() {
        val t = parser.parse(sms("UPI AutoPay mandate of Rs 649.00 for NETFLIX created successfully on your A/c XX1234. Next debit on 10-11-26", "AX-HDFCBK"))
        assertEquals(MessageKind.AUTOPAY_MANDATE, t.kind)
        assertTrue(t.status != ParseStatus.PARSED)
    }

    @Test
    fun `fx markup fee`() {
        val t = parser.parse(sms("Forex markup fee of Rs 98.50 charged on your HDFC Bank Card XX1234 for txn at AMAZON WEB SERVICES on 03-11-26", "AX-HDFCBK"))
        assertEquals(MessageKind.FX_MARKUP, t.kind)
        assertEquals(inr("98.50"), t.amount)
        assertEquals(TxnDirection.DEBIT, t.direction)
    }

    @Test
    fun `received time used when message has no date`() {
        val t = parser.parse(notif(Pkg.PHONEPE, null, "Received ₹500 from Ravi Kumar"))
        assertEquals(RECEIVED, t.occurredAt)
    }

    @Test
    fun `configurable timezone`() {
        val p = TransactionParser(ParserConfig(zone = java.time.ZoneId.of("Europe/London")))
        val t = p.parse(sms("Rs.1,250.00 spent on HDFC Bank Card x1234 at AMAZON on 2026-11-03:14:22:10", "AX-HDFCBK"))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 3, 14, 22, 10).atZone(java.time.ZoneId.of("Europe/London")).toInstant(), t.occurredAt)
    }

    @Test
    fun `never throws on bad input`() {
        val bad = listOf(
            SourceEvent("1", Channel.SMS, null, null, null, "", RECEIVED),
            SourceEvent("2", Channel.SMS, "", "", "", "   \n\t ", RECEIVED),
            SourceEvent("3", Channel.NOTIFICATION, null, Pkg.GPAY, "\u0000", "₹₹₹₹ (((( [[[[ \\\\ $$$", RECEIVED),
            SourceEvent("4", Channel.SMS, "AX-HDFCBK", null, null, "Rs " + "9".repeat(400), RECEIVED),
            SourceEvent("5", Channel.MANUAL, null, null, null, "Rs 1,00,00,00,00,00,00,00,000 debited", RECEIVED),
            SourceEvent("6", Channel.SMS, "AX-HDFCBK", null, null, "Rs.500 debited ".repeat(2000), RECEIVED),
        )
        for (e in bad) {
            val t = parser.parse(e)
            assertEquals(e.id, t.sourceEventId)
        }
        assertEquals(ParseStatus.IGNORED_NOT_FINANCIAL, parser.parse(bad[0]).status)
    }

    @Test
    fun `manual channel bypasses sender check`() {
        val t = parser.parse(SourceEvent("m", Channel.MANUAL, null, null, null, "Rs.1,250.00 spent on HDFC Bank Card x1234 at AMAZON on 2026-11-03", RECEIVED))
        assertEquals(ParseStatus.PARSED, t.status)
    }

    @Test
    fun `trace records pipeline stages`() {
        val r = parser.parseDetailed(sms(BuiltInTemplates.templates.first { it.id == "sbi_upi_debit" }.sample!!, "JD-SBIUPI"))
        assertTrue(r.trace.any { it.startsWith("template:sbi_upi_debit") }, r.trace.toString())
        assertEquals("Zomato", r.merchant?.name)
    }
}
