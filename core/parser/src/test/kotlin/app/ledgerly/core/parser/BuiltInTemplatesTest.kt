package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.MessageKind.*
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.ParseStatus
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.TxnDirection
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltInTemplatesTest {

    private data class Case(
        val id: String,
        val event: SourceEvent,
        val kind: MessageKind,
        val amount: Money,
        val last4: String? = null,
        val merchant: String? = null,
        val ref: String? = null,
        val balance: Money? = null,
        val occurredAt: Instant? = null,
        val dueDate: LocalDate? = null,
        val minDue: Money? = null,
        val accountAmount: Money? = null,
        val institution: String? = null,
    )

    private fun sample(id: String) = BuiltInTemplates.templates.first { it.id == id }.sample!!
    private fun s(id: String, sender: String) = sms(sample(id), sender)

    private val cases = listOf(
        Case("hdfc_card_spend", s("hdfc_card_spend", "AX-HDFCBK-S"), DEBIT, inr("1250.00"), "1234", "AMAZON PAY INDIA",
            occurredAt = ist(2026, 11, 3, 14, 22, 10), institution = "HDFC Bank"),
        Case("hdfc_upi_sent", s("hdfc_upi_sent", "VM-HDFCBK"), UPI_SENT, inr("500.00"), "1234", "SWIGGY", "412345678901",
            occurredAt = ist(2026, 11, 3)),
        Case("hdfc_upi_received", s("hdfc_upi_received", "VM-HDFCBK"), UPI_RECEIVED, inr("2000"), "1234", "ravi.k@okaxis", "412345678912"),
        Case("hdfc_acct_debit", s("hdfc_acct_debit", "AD-HDFCBK"), DEBIT, inr("5000"), "1234", "NEFT-HDFCR52026110312345-ACME CORP",
            balance = inr("45210.50")),
        Case("hdfc_deposit", s("hdfc_deposit", "AD-HDFCBK"), SALARY, inr("85000"), "1234", balance = inr("102345.67"),
            occurredAt = ist(2026, 10, 31)),
        Case("hdfc_atm", s("hdfc_atm", "AD-HDFCBK"), ATM_WITHDRAWAL, inr("3000"), "1234", "MG ROAD ATM BANGALORE", balance = inr("42000"),
            occurredAt = ist(2026, 11, 3, 10, 11, 12)),
        Case("hdfc_cc_statement", s("hdfc_cc_statement", "AD-HDFCCC"), STATEMENT, inr("12345.67"), "1234",
            dueDate = LocalDate.of(2026, 11, 22), minDue = inr("620")),
        Case("hdfc_gmail_upi", notif(Pkg.GMAIL, "HDFC Bank InstaAlerts",
            "Dear Customer, Rs.899.00 has been debited from account **1234 to VPA netflix@icici NETFLIX on 03-11-26. Your UPI transaction reference number is 412355556666."),
            UPI_SENT, inr("899"), "1234", "netflix@icici NETFLIX", "412355556666", institution = "HDFC Bank"),
        Case("icici_card_spend", s("icici_card_spend", "JD-ICICIB"), DEBIT, inr("2499"), "5678", "FLIPKART", institution = "ICICI Bank"),
        Case("icici_acct_debit", s("icici_acct_debit", "JD-ICICIB-S"), UPI_SENT, inr("1500"), "123", "RAVI KUMAR", "412398765432"),
        Case("icici_acct_credit", s("icici_acct_credit", "JD-ICICIB"), UPI_RECEIVED, inr("25000"), "123", "ACME CORP", "412398765433"),
        Case("icici_cc_bill_due", s("icici_cc_bill_due", "VM-ICICIT"), BILL_DUE, inr("8765.43"), "5678",
            dueDate = LocalDate.of(2026, 11, 15), minDue = inr("440")),
        Case("icici_gmail_card", notif(Pkg.GMAIL, "Transaction alert for your ICICI Bank Credit Card",
            "Dear Customer, Your ICICI Bank Credit Card XX5678 has been used for a transaction of INR 1,234.00 on Nov 03, 2026 at 10:15:23. Info: SWIGGY."),
            DEBIT, inr("1234"), "5678", "SWIGGY", occurredAt = ist(2026, 11, 3, 10, 15, 23), institution = "ICICI Bank"),
        Case("sbi_upi_debit", s("sbi_upi_debit", "JD-SBIUPI"), UPI_SENT, inr("250"), "1234", "ZOMATO LTD", "412312341234",
            occurredAt = ist(2026, 11, 3)),
        Case("sbi_upi_credit", s("sbi_upi_credit", "AD-SBIINB"), UPI_RECEIVED, inr("500"), "1234", ref = "412312341299"),
        Case("sbi_atm", s("sbi_atm", "AD-SBIINB"), ATM_WITHDRAWAL, inr("2000"), "1234", "S1BW000123", balance = inr("12345")),
        Case("sbi_card_spend", s("sbi_card_spend", "VM-SBICRD"), DEBIT, inr("1099"), "4321", "NETFLIX", institution = "SBI Card"),
        Case("axis_card_spend", s("axis_card_spend", "AX-AXISBK"), DEBIT, inr("799"), "9876", "SPOTIFY", occurredAt = ist(2026, 11, 3, 14, 5, 11)),
        Case("axis_acct_debit", s("axis_acct_debit", "AX-AXISBK"), UPI_SENT, inr("3200"), "9876", "BIGBASKET", "412345671234"),
        Case("kotak_upi_sent", s("kotak_upi_sent", "VM-KOTAKB"), UPI_SENT, inr("150"), "4567", "uber@ybl", "412398761234"),
        Case("kotak_upi_received", s("kotak_upi_received", "VM-KOTAKB"), UPI_RECEIVED, inr("1000"), "4567", "ravi@okhdfcbank", "412398761299"),
        Case("yes_card_spend", s("yes_card_spend", "VM-YESBNK"), DEBIT, inr("560"), "2345", "UBER INDIA", occurredAt = ist(2026, 11, 3, 21, 15)),
        Case("idfc_card_spend", s("idfc_card_spend", "AX-IDFCFB"), DEBIT, inr("1899"), "3456", "MYNTRA", occurredAt = ist(2026, 11, 3, 20, 12)),
        Case("au_acct_debit", s("au_acct_debit", "AX-AUBANK"), UPI_SENT, inr("450"), "6789", "UPI/412312345678/SWIGGY", balance = inr("9550")),
        Case("federal_upi_debit", s("federal_upi_debit", "VM-FEDBNK"), UPI_SENT, inr("320"), "7890", "blinkit@hdfcbank", "412311112222"),
        Case("indusind_card_spend", s("indusind_card_spend", "AX-INDUSB"), DEBIT, inr("2150"), "8901", "DECATHLON SPORTS"),
        Case("generic_emi_debit", s("generic_emi_debit", "AX-HDFCBK"), EMI, inr("4567"), "4321", occurredAt = ist(2026, 11, 5, 10, 0)),
        Case("generic_refund", s("generic_refund", "AX-HDFCBK"), REFUND, inr("499"), "1234", "MYNTRA"),
        Case("generic_cc_payment_received", s("generic_cc_payment_received", "JD-ICICIB"), CREDIT, inr("15000"), "5678"),
        Case("generic_salary", s("generic_salary", "VM-KOTAKB"), SALARY, inr("85000"), "9012"),
        Case("generic_statement", s("generic_statement", "VM-SBICRD"), STATEMENT, inr("5432.10"), "4321",
            dueDate = LocalDate.of(2026, 11, 20), minDue = inr("271")),
        Case("upi_app_paid", notif(Pkg.GPAY, null, "You paid ₹250.00 to Zomato"), UPI_SENT, inr("250"), merchant = "Zomato", institution = "Google Pay"),
        Case("upi_app_amount_first", notif(Pkg.GPAY, "₹349 paid to Domino's Pizza", "Paid with HDFC Bank •• 1234"), UPI_SENT, inr("349"),
            merchant = "Domino's Pizza", institution = "Google Pay"),
        Case("upi_app_received", notif(Pkg.PHONEPE, null, "Received ₹500 from Ravi Kumar"), UPI_RECEIVED, inr("500"), merchant = "Ravi Kumar"),
        Case("generic_fx_dual", s("generic_fx_dual", "AX-HDFCBK"), DEBIT, money("45.00", "USD"), "1234", "AWS EMEA",
            accountAmount = inr("3912.30")),
        Case("amex_charge", notif(Pkg.AMEX, "Amex", sample("amex_charge")), DEBIT, money("45.20", "USD"), "1005", "STARBUCKS",
            occurredAt = ist(2026, 11, 3), institution = "American Express"),
        Case("amex_in_spend", s("amex_in_spend", "AX-AMEXIN"), DEBIT, inr("3456"), "1009", "ZARA"),
        Case("neobank_you_paid", notif(Pkg.REVOLUT, "Revolut", "You paid €12.50 at Lidl"), DEBIT, money("12.50", "EUR"), merchant = "Lidl",
            institution = "Revolut"),
        Case("neobank_received", notif(Pkg.MONZO, null, "You received £50.00 from John Smith"), CREDIT, money("50", "GBP"), merchant = "John Smith"),
        Case("wise_spent_suffix", notif(Pkg.WISE, null, "You spent 23.40 EUR at REWE Markt GmbH."), DEBIT, money("23.40", "EUR"),
            merchant = "REWE Markt GmbH"),
        Case("card_spent_prefix", notif(Pkg.N26, null, "You spent €23.40 at REWE"), DEBIT, money("23.40", "EUR"), merchant = "REWE"),
        Case("wise_sent", notif(Pkg.WISE, null, "You've sent 100.00 GBP to Jane Doe."), DEBIT, money("100", "GBP"), merchant = "Jane Doe"),
        Case("chase_txn", notif(Pkg.CHASE, "Chase", sample("chase_txn")), DEBIT, money("24.99", "USD"), merchant = "NETFLIX.COM"),
        Case("hsbc_card", notif(Pkg.HSBC, "HSBC", sample("hsbc_card")), DEBIT, money("18.75", "GBP"), "6789", "TESCO STORES",
            occurredAt = ist(2026, 11, 3)),
        Case("monzo_spend", notif(Pkg.MONZO, "£4.50 at Pret A Manger", "🥪 Lunch"), DEBIT, money("4.50", "GBP"), merchant = "Pret A Manger",
            institution = "Monzo"),
    )

    private val parser = TransactionParser()

    @TestFactory
    fun `every built-in template parses its sample`(): List<DynamicTest> = cases.map { c ->
        DynamicTest.dynamicTest(c.id) {
            val r = parser.parseDetailed(c.event)
            val t = r.transaction
            assertEquals(c.id, t.parserId, "parser id; trace=${r.trace}")
            assertEquals(c.kind, t.kind, "kind")
            assertEquals(c.amount, t.amount, "amount")
            c.last4?.let { assertEquals(it, t.accountLast4, "last4") }
            c.merchant?.let { assertEquals(it, t.merchantRaw, "merchant") }
            c.ref?.let { assertEquals(it, t.reference, "ref") }
            c.balance?.let { assertEquals(it, t.balanceAfter, "balance") }
            c.occurredAt?.let { assertEquals(it, t.occurredAt, "occurredAt") }
            c.dueDate?.let { assertEquals(it, t.dueDate, "dueDate") }
            c.minDue?.let { assertEquals(it, t.minimumDue, "minDue") }
            c.accountAmount?.let { assertEquals(it, t.accountAmount, "accountAmount") }
            c.institution?.let { assertEquals(it, t.institution, "institution") }
            val expected = if (c.kind in Kinds.INFO) ParseStatus.INFO_ONLY else ParseStatus.PARSED
            if (c.id != "generic_fx_dual") assertEquals(expected, t.status, "status (confidence ${t.confidence})")
            assertEquals(Kinds.defaultDirection(c.kind), t.direction, "direction")
        }
    }

    @Test
    fun `every built-in template has a test case and a sample`() {
        val ids = BuiltInTemplates.templates.map { it.id }
        assertTrue(ids.size >= 25, "at least 25 templates, got ${ids.size}")
        assertEquals(ids.size, ids.toSet().size, "unique ids")
        assertEquals(ids.toSet(), cases.map { it.id }.toSet())
        BuiltInTemplates.templates.forEach { assertNotNull(it.sample, it.id) }
    }

    @Test
    fun `every template sample matches its own template in isolation`() {
        for (t in BuiltInTemplates.templates) {
            val engine = TemplateEngine(listOf(t))
            val m = engine.match(TextNormalizer.normalize(t.sample), null, RECEIVED, MessageClassifier().classify(t.sample!!))
            assertNotNull(m, "template ${t.id} must match its sample")
            assertNotNull(m.extraction.amount, "amount for ${t.id}")
        }
    }

    @Test
    fun `foreign currency HDFC card spend keeps currency`() {
        val t = parser.parse(sms("USD 45.00 spent on HDFC Bank Card XX1234 at AMAZON WEB SERVICES on 2026-11-03", "AX-HDFCBK"))
        assertEquals("hdfc_card_spend", t.parserId)
        assertEquals(money("45.00", "USD"), t.amount)
        assertEquals("1234", t.accountLast4)
        assertEquals("AMAZON WEB SERVICES", t.merchantRaw)
        assertEquals(TxnDirection.DEBIT, t.direction)
        assertEquals(ParseStatus.PARSED, t.status)
    }
}
