package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.MessageKind.*
import app.ledgerly.core.model.TxnDirection
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MessageClassifierTest {
    private val c = MessageClassifier()

    @ParameterizedTest(name = "[{index}] {1} <- {0}")
    @MethodSource("samples")
    fun classifies(text: String, expected: MessageKind) {
        assertEquals(expected, c.classify(text).kind, "signals=${c.classify(text).signals}")
    }

    @Test
    fun `direction for debit and credit`() {
        assertEquals(TxnDirection.DEBIT, c.classify("Rs 500 debited from A/c XX1234 on 03-11-26").direction)
        assertEquals(TxnDirection.CREDIT, c.classify("Rs 500 credited to A/c XX1234 on 03-11-26").direction)
    }

    @Test
    fun `earliest verb wins when both debited and credited appear`() {
        val r = c.classify("ICICI Bank Acct XX123 debited for Rs 1,500.00 on 03-Nov-26; RAVI KUMAR credited. UPI:412398765432")
        assertEquals(UPI_SENT, r.kind)
        assertEquals(TxnDirection.DEBIT, r.direction)
    }

    @Test
    fun `never throws on garbage`() {
        assertEquals(UNKNOWN, c.classify("").kind)
        assertEquals(UNKNOWN, c.classify("\u0000\u0001 ### ((((").kind)
    }

    companion object {
        @JvmStatic
        fun samples(): List<Arguments> = listOf(
            // OTP (with amounts inside) must be OTP
            "482913 is your OTP for txn of Rs 2,500.00 at AMAZON on HDFC Bank card ending 1234. Valid till 10:32. Do not share OTP for security reasons." to OTP,
            "Your One Time Password for transaction of INR 15,000.00 is 552211. Never share it with anyone. -ICICI Bank" to OTP,
            "Use verification code 9921 to complete your payment of Rs 499 on Swiggy." to OTP,
            "OTP 771122 for login to SBI YONO. Do not share with anyone." to OTP,
            // Promo
            "Get Rs 500 cashback on your first UPI payment! Use code FIRST500. T&C apply." to PROMO,
            "Congratulations! You are pre-approved for a Personal Loan of up to Rs 5,00,000. Apply now: http://bit.ly/x" to PROMO,
            "Flat 20% off on Myntra with HDFC Bank Credit Cards. Offer valid till 10 Nov." to PROMO,
            "Upgrade your card today and earn 5X reward points. Click here https://axis.bank/upg" to PROMO,
            // Transactions
            "Rs.1,250.00 spent on HDFC Bank Card x1234 at AMAZON on 2026-11-03" to DEBIT,
            "Sent Rs.500.00 From HDFC Bank A/C *1234 To SWIGGY On 03/11/26 Ref 412345678901" to DEBIT,
            "Rs 320.00 debited from your A/c XX7890 to VPA blinkit@hdfcbank on 03-11-2026. UPI Ref 412311112222" to UPI_SENT,
            "Received Rs.1,000.00 in your Kotak Bank AC X4567 from ravi@okhdfcbank on 03-11-26.UPI Ref:412398761299." to UPI_RECEIVED,
            "Rs 25,000 credited to your A/c XX1234 by NEFT from ACME CORP" to CREDIT,
            "INR 85,000.00 credited to A/c XX1234 towards SALARY for OCT-2026" to SALARY,
            "Rs.2,000 withdrawn at SBI ATM S1BW000123 from A/cX1234 on 03Nov26" to ATM_WITHDRAWAL,
            "Cash withdrawal of Rs 5,000 at ATM HDFC0001 from card XX1234 done" to ATM_WITHDRAWAL,
            "Your EMI of Rs 4,567.00 for Loan A/c XX4321 has been debited on 05-11-26" to EMI,
            "Refund of Rs 499.00 from MYNTRA has been credited to your card XX1234" to REFUND,
            "Rs 500 reversed to your A/c XX1234 against failed UPI txn Ref 412312341234" to REVERSAL,
            "Your transaction of Rs 500 at FLIPKART has failed. Amount debited, if any, will be reversed in 5-7 days." to FAILED_TXN,
            "UPI transaction of Rs 500 to ravi@ybl declined due to insufficient balance" to FAILED_TXN,
            "Payment of Rs 15,000.00 received on your ICICI Bank Credit Card XX5678. Thank you." to CREDIT,
            "Thank you for your payment of Rs 8,000 towards your SBI Card ending 4321" to CREDIT,
            "Your ICICI Bank Credit Card XX5678 bill of Rs 8,765.43 is due on 15-Nov-26. Min amount due Rs 440.00." to BILL_DUE,
            "HDFC Bank Credit Card XX1234 Statement: Total due Rs.12,345.67, Min due Rs.620.00, payment due by 22-11-2026" to STATEMENT,
            "UPI AutoPay mandate of Rs 649 for Netflix created successfully on your A/c XX1234. Next debit on 10-11-26" to AUTOPAY_MANDATE,
            "Rs 199 debited via UPI AutoPay mandate for Spotify from A/c XX1234" to UPI_SENT,
            "Forex markup fee of Rs 98.50 charged on your card XX1234 for txn at AMAZON WEB SERVICES" to FX_MARKUP,
            "Rs 2,000 has been authorised on your card XX1234 at HOTEL TAJ. Pending settlement." to CARD_AUTH,
            "Available balance in A/c XX1234 is Rs 45,210.50 as on 03-11-26" to BALANCE,
            "Your Amazon order has been shipped and will arrive tomorrow" to UNKNOWN,
            "You paid €12.50 at Lidl" to DEBIT,
        ).map { Arguments.of(it.first, it.second) }
    }
}
