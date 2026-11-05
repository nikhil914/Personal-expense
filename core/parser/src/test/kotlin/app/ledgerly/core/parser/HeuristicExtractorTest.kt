package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeuristicExtractorTest {
    private val h = HeuristicExtractor()
    private val cls = MessageClassifier()
    private fun x(text: String) = h.extract(text, RECEIVED, cls.classify(text))

    @Test
    fun `unknown bank debit format`() {
        val e = x("Dear Customer, your a/c no. XXXXXX5521 is debited by Rs.1,23,456.78 on 02/11/2026 towards PAYMENT TO LANDLORD. Avl Bal Rs.2,500.00. RRN 630712345678")
        assertEquals(inr("123456.78"), e.amount)
        assertEquals("5521", e.accountLast4)
        assertEquals(inr("2500"), e.balanceAfter)
        assertEquals("PAYMENT TO LANDLORD", e.merchantRaw)
        assertEquals("630712345678", e.reference)
        assertEquals(ist(2026, 11, 2), e.occurredAt)
        assertEquals(MessageKind.DEBIT, e.kind)
        assertTrue(e.confidence >= 0.75, "confidence ${e.confidence}")
    }

    @Test
    fun `dual currency gives account amount`() {
        val e = x("Your card ending 4455 was charged USD 45.00 (INR 3,912.30) at GITHUB INC on 03-11-26")
        assertEquals(money("45.00", "USD"), e.amount)
        assertEquals(inr("3912.30"), e.accountAmount)
        assertEquals("4455", e.accountLast4)
        assertEquals("GITHUB INC", e.merchantRaw)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = ';',
        value = [
            "Rs 500 debited from card XX1234 at SHOP;1234", "Rs 500 debited from card **9876 at SHOP;9876",
            "Rs 500 spent on card ending 4321 at SHOP;4321", "Rs 500 debited from A/c no. XXXXXX1234 on 03-11-26;1234",
            "Rs 500 debited from A/cX5566 on 03-11-26;5566", "\$5 charged to card ending in 31005 at CAFE;1005",
        ],
    )
    fun `finds last4 variants`(text: String, last4: String) {
        assertEquals(last4, x(text).accountLast4)
    }

    @Test
    fun `missing last4 is null and lowers confidence`() {
        val e = x("Rs 250 paid to Chai Point via UPI")
        assertNull(e.accountLast4)
        assertEquals(inr("250"), e.amount)
        assertEquals("Chai Point", e.merchantRaw)
        assertTrue(e.confidence < 0.75)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = ';',
        value = [
            "Rs 99 debited UPI Ref No 412345678901 thanks;412345678901", "Rs 99 debited. Ref no: ABC123456 thanks;ABC123456",
            "Rs 99 debited. RRN: 630712345678;630712345678", "Rs 99 debited. UTR HDFCN52026110312345 done;HDFCN52026110312345",
        ],
    )
    fun `finds references`(text: String, ref: String) {
        assertEquals(ref, x(text).reference)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = ';',
        value = [
            "Rs 450 debited from A/c XX1234 to VPA swiggy@icici on 03-11-26;swiggy@icici",
            "Rs 450 debited from A/c XX1234 towards BESCOM BILL on 03-11-26;BESCOM BILL",
            "Rs 450 debited from A/c XX1234. Info: ACH-DR-LIC OF INDIA;ACH-DR-LIC OF INDIA",
            "INR 450 debited A/c XX1234 UPI/P2M/412345678901/ZEPTO MARKETPLACE;ZEPTO MARKETPLACE",
            "Rs 5,000 credited to A/c XX1234 from RAVI SHARMA on 03-11-26;RAVI SHARMA",
        ],
    )
    fun `finds merchant after cue words`(text: String, merchant: String) {
        assertEquals(merchant, x(text).merchantRaw)
    }

    @Test
    fun `amount without currency next to verb uses default currency`() {
        val e = x("A/C X1234 debited by 250.0 on date 03Nov26 trf to CHAI POINT Refno 412312341234")
        assertEquals(inr("250"), e.amount)
        assertEquals("1234", e.accountLast4)
    }

    @Test
    fun `balance and limit are not taken as the amount`() {
        val e = x("Avl Bal Rs 10,000.00. Rs 300 debited from A/c XX1234 at METRO")
        assertEquals(inr("300"), e.amount)
        assertEquals(inr("10000"), e.balanceAfter)
        val e2 = x("Avl Limit: INR 50,000. INR 2,150.00 spent on card XX8901 at DECATHLON")
        assertEquals(inr("2150"), e2.amount)
    }

    @Test
    fun `bill due picks total and minimum due`() {
        val e = x("Your card XX1234 bill: Total Amount Due Rs 9,000.00, Minimum Amount Due Rs 450.00, pay by 20 Nov")
        assertEquals(inr("9000"), e.amount)
        assertEquals(inr("450"), e.minimumDue)
        assertEquals(java.time.LocalDate.of(2026, 11, 20), e.dueDate)
    }

    @Test
    fun `never throws`() {
        x("")
        x("₹₹₹ ,,,, 1,2,3,,, XX XX ****")
        h.extract("Rs 5".repeat(5000), RECEIVED, null)
    }
}
