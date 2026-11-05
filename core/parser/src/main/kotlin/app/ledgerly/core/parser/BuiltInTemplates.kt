package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.MessageKind.ATM_WITHDRAWAL
import app.ledgerly.core.model.MessageKind.BILL_DUE
import app.ledgerly.core.model.MessageKind.CREDIT
import app.ledgerly.core.model.MessageKind.DEBIT
import app.ledgerly.core.model.MessageKind.EMI
import app.ledgerly.core.model.MessageKind.REFUND
import app.ledgerly.core.model.MessageKind.SALARY
import app.ledgerly.core.model.MessageKind.STATEMENT
import app.ledgerly.core.model.MessageKind.UPI_RECEIVED
import app.ledgerly.core.model.MessageKind.UPI_SENT
import app.ledgerly.core.model.TxnDirection

/**
 * Built-in template library. Each template carries a realistic [ParserTemplate.sample] that the
 * unit tests re-parse. Bump [VERSION] whenever templates change.
 */
object BuiltInTemplates {
    const val VERSION = 1

    private val UPI_APPS = listOf(
        "com\\.google\\.android\\.apps\\.nbu\\.paisa\\.user", "com\\.phonepe\\.app", "net\\.one97\\.paytm",
        "in\\.org\\.npci\\.upiapp", "com\\.dreamplug\\.androidapp", "in\\.amazon\\.mShop", "com\\.mobikwik",
        "Google Pay", "PhonePe", "Paytm", "BHIM", "CRED", "Amazon Pay",
    )
    private val NEOBANKS = listOf("revolut", "n26", "number26", "monzo", "getmondo", "wise", "transferwise", "starling", "paypal")
    private const val GMAIL = "com\\.google\\.android\\.gm"

    private fun t(
        id: String, institution: String?, pattern: String, kind: MessageKind?, sample: String,
        senders: List<String> = emptyList(), direction: TxnDirection? = null, confidence: Double = 0.95, currency: String? = null,
    ) = ParserTemplate(id, institution, pattern, senders, kind, direction, currency, confidence, sample = sample)

    val templates: List<ParserTemplate> = listOf(
        // ---------------- HDFC ----------------
        t("hdfc_card_spend", "HDFC Bank",
            "{CUR}\\s?{AMT} spent on HDFC Bank (?:Credit |Debit )?Card {L4} at {MERCHANT} on {DATE}",
            DEBIT, "Rs.1,250.00 spent on HDFC Bank Card x1234 at AMAZON PAY INDIA on 2026-11-03:14:22:10.Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 1234 to 7308080808",
            senders = listOf("HDFC")),
        t("hdfc_upi_sent", "HDFC Bank",
            "Sent {CUR}\\s?{AMT} From HDFC Bank A/C {L4} To {MERCHANT} On {DATE} Ref {REF}",
            UPI_SENT, "Sent Rs.500.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 03/11/26\nRef 412345678901\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
            senders = listOf("HDFC")),
        t("hdfc_upi_received", "HDFC Bank",
            "Money Received - {CUR}\\s?{AMT} in your HDFC Bank A/c {L4} on {DATE} by (?:A/c linked to )?VPA {MERCHANT} \\(UPI Ref No\\.? {REF}\\)",
            UPI_RECEIVED, "Money Received - INR 2,000.00 in your HDFC Bank A/c xx1234 on 03-11-26 by A/c linked to VPA ravi.k@okaxis (UPI Ref No 412345678912).",
            senders = listOf("HDFC")),
        t("hdfc_acct_debit", "HDFC Bank",
            "{CUR}\\s?{AMT} debited from HDFC Bank (?:A/c )?{L4} on {DATE}\\.? Info:?\\s?{MERCHANT}(?:\\.\\s?Avl bal:?\\s?{BAL}|\\.?\\s*$|\\.\\s)",
            DEBIT, "Update! INR 5,000.00 debited from HDFC Bank XX1234 on 03-NOV-26. Info: NEFT-HDFCR52026110312345-ACME CORP. Avl bal:INR 45,210.50",
            senders = listOf("HDFC")),
        t("hdfc_deposit", "HDFC Bank",
            "{CUR}\\s?{AMT} deposited in HDFC Bank A/c {L4} on {DATE} for {MERCHANT}\\.\\s?Avl bal:?\\s?{BAL}",
            null, "Update! INR 85,000.00 deposited in HDFC Bank A/c XX1234 on 31-OCT-26 for NEFT Cr-CITI0000001-ACME TECHNOLOGIES PVT LTD-SALARY OCT 2026.Avl bal INR 1,02,345.67. Cheque deposits in A/C are subject to clearing",
            senders = listOf("HDFC"), direction = TxnDirection.CREDIT),
        t("hdfc_atm", "HDFC Bank",
            "{CUR}\\s?{AMT} withdrawn from HDFC Bank (?:Debit )?Card {L4} at {MERCHANT} on {DATE}(?:\\.?\\s?Avl bal:?\\s?{BAL})?",
            ATM_WITHDRAWAL, "Rs.3000 withdrawn from HDFC Bank Card x1234 at +MG ROAD ATM BANGALORE on 2026-11-03:10:11:12. Avl bal: Rs.42,000.00",
            senders = listOf("HDFC")),
        t("hdfc_cc_statement", "HDFC Bank",
            "HDFC Bank Credit Card {L4} Statement: Total due {CUR}\\s?{AMT}, Min(?:imum)? due {MCUR}\\s?{MIN}, payment due by {DUE}",
            STATEMENT, "HDFC Bank Credit Card XX1234 Statement: Total due Rs.12,345.67, Min due Rs.620.00, payment due by 22-11-2026. Pay now via NetBanking/PayZapp",
            senders = listOf("HDFC")),
        t("hdfc_gmail_upi", "HDFC Bank",
            "{CUR}\\s?{AMT} has been debited from account {L4} to VPA {MERCHANT} on {DATE}\\.? Your UPI transaction reference number is {REF}",
            UPI_SENT, "HDFC Bank InstaAlerts | Dear Customer, Rs.899.00 has been debited from account **1234 to VPA netflix@icici NETFLIX on 03-11-26. Your UPI transaction reference number is 412355556666.",
            senders = listOf("HDFC", GMAIL)),
        // ---------------- ICICI ----------------
        t("icici_card_spend", "ICICI Bank",
            "{CUR}\\s?{AMT} spent (?:using|on) ICICI Bank (?:Credit )?Card {L4} on {DATE} (?:on|at) {MERCHANT}\\.(?:\\s|$)",
            DEBIT, "INR 2,499.00 spent using ICICI Bank Card XX5678 on 03-Nov-26 on FLIPKART. Avl Limit: INR 1,45,000.00. If not you, call 1800 2662/SMS BLOCK 5678 to 9215676766.",
            senders = listOf("ICICI")),
        t("icici_acct_debit", "ICICI Bank",
            "ICICI Bank Acc(?:oun)?t {L4} debited (?:for|with) {CUR}\\s?{AMT} on {DATE}(?:;| and) {MERCHANT} credited\\.\\s?UPI:?\\s?{REF}",
            null, "ICICI Bank Acct XX123 debited for Rs 1,500.00 on 03-Nov-26; RAVI KUMAR credited. UPI:412398765432. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.",
            senders = listOf("ICICI"), direction = TxnDirection.DEBIT),
        t("icici_acct_credit", "ICICI Bank",
            "Acc(?:oun)?t {L4} is credited with {CUR}\\s?{AMT} on {DATE} (?:from|by) {MERCHANT}\\.\\s?UPI:?\\s?{REF}",
            null, "Dear Customer, Acct XX123 is credited with Rs 25,000.00 on 03-Nov-26 from ACME CORP. UPI:412398765433-ICICI Bank.",
            senders = listOf("ICICI"), direction = TxnDirection.CREDIT),
        t("icici_cc_bill_due", "ICICI Bank",
            "ICICI Bank Credit Card {L4} bill of {CUR}\\s?{AMT} is due on {DUE}\\.\\s?Min(?:imum)? amount due:? {MCUR}\\s?{MIN}",
            BILL_DUE, "Your ICICI Bank Credit Card XX5678 bill of Rs 8,765.43 is due on 15-Nov-26. Min amount due Rs 440.00. Ignore if paid.",
            senders = listOf("ICICI")),
        t("icici_gmail_card", "ICICI Bank",
            "ICICI Bank Credit Card {L4} has been used for a transaction of {CUR}\\s?{AMT} on {DATE}\\.\\s?Info:?\\s?{MERCHANT}{END}",
            DEBIT, "Transaction alert for your ICICI Bank Credit Card | Dear Customer, Your ICICI Bank Credit Card XX5678 has been used for a transaction of INR 1,234.00 on Nov 03, 2026 at 10:15:23. Info: SWIGGY.",
            senders = listOf("ICICI", GMAIL)),
        // ---------------- SBI ----------------
        t("sbi_upi_debit", "State Bank of India",
            "A/C ?{L4} debited by {CUR}?\\s?{AMT} on date {DATE} trf to {MERCHANT} Ref ?no {REF}",
            UPI_SENT, "Dear UPI user A/C X1234 debited by 250.0 on date 03Nov26 trf to ZOMATO LTD Refno 412312341234. If not u? call 1800111109. -SBI",
            senders = listOf("SBI", "State Bank")),
        t("sbi_upi_credit", "State Bank of India",
            "A/c ?{L4} credited by {CUR}?\\s?{AMT} on {DATE} by {MERCHANT}?\\s?\\(Ref no {REF}\\)",
            UPI_RECEIVED, "Dear SBI UPI User, ur A/cX1234 credited by Rs500 on 03Nov26 by (Ref no 412312341299)",
            senders = listOf("SBI", "State Bank")),
        t("sbi_atm", "State Bank of India",
            "{CUR}\\s?{AMT} withdrawn at SBI ATM {MERCHANT} from A/c ?{L4} on {DATE}.*?Available Balance:? {BAL}",
            ATM_WITHDRAWAL, "Dear Customer, Rs.2,000 withdrawn at SBI ATM S1BW000123 from A/cX1234 on 03Nov26 Transaction Number 1234. Available Balance Rs.12,345.00. If not withdrawn by you, forward this SMS to 9223008333 -SBI",
            senders = listOf("SBI", "State Bank")),
        t("sbi_card_spend", "SBI Card",
            "(?:Trxn|Txn|Transaction)\\.? of {CUR}\\s?{AMT} done on your SBI Credit Card ending {L4} at {MERCHANT} on {DATE}",
            DEBIT, "Trxn. of Rs 1,099.00 done on your SBI Credit Card ending 4321 at NETFLIX on 03/11/26. Trxn. not done by you? Report at https://sbicard.com/Dispute",
            senders = listOf("SBI")),
        // ---------------- Axis ----------------
        t("axis_card_spend", "Axis Bank",
            "Spent {CUR}\\s?{AMT} Axis Bank Card no\\.? {L4} {DATE}(?: IST)? {MERCHANT}(?= Avl Limit| Not you|\\s*$)",
            DEBIT, "Spent INR 799.00 Axis Bank Card no. XX9876 03-11-26 14:05:11 IST SPOTIFY Avl Limit: INR 98,201.00 Not you? SMS BLOCK 9876 to 919951860002",
            senders = listOf("AXIS")),
        t("axis_acct_debit", "Axis Bank",
            "{CUR}\\s?{AMT} debited A/c no\\.? {L4} {DATE}(?: IST)? UPI/P2[MA]/{REF}/{MERCHANT}(?= Not you|\\s*$|\\.\\s)",
            UPI_SENT, "INR 3,200.00 debited A/c no. XX9876 03-11-26 12:10:45 UPI/P2M/412345671234/BIGBASKET Not you? SMS BLOCK 9876 to +919951860002 Axis Bank",
            senders = listOf("AXIS")),
        // ---------------- Kotak ----------------
        t("kotak_upi_sent", "Kotak Mahindra Bank",
            "Sent {CUR}\\s?{AMT} from Kotak Bank AC {L4} to {MERCHANT} on {DATE}\\.\\s?UPI Ref:?\\s?{REF}",
            UPI_SENT, "Sent Rs.150.00 from Kotak Bank AC X4567 to uber@ybl on 03-11-26.UPI Ref 412398761234. Not you, https://kotak.com/KBANKT/Fraud",
            senders = listOf("KOTAK")),
        t("kotak_upi_received", "Kotak Mahindra Bank",
            "Received {CUR}\\s?{AMT} in your Kotak Bank AC {L4} from {MERCHANT} on {DATE}\\.\\s?UPI Ref:?\\s?{REF}",
            UPI_RECEIVED, "Received Rs.1,000.00 in your Kotak Bank AC X4567 from ravi@okhdfcbank on 03-11-26.UPI Ref:412398761299.",
            senders = listOf("KOTAK")),
        // ---------------- Other Indian banks ----------------
        t("yes_card_spend", "Yes Bank",
            "{CUR}\\s?{AMT} spent on YES BANK (?:Credit |Debit )?Card {L4} at {MERCHANT} on {DATE}",
            DEBIT, "INR 560.00 spent on YES BANK Credit Card XX2345 at UBER INDIA on 03-11-2026 at 09:15 PM. Avl Lmt INR 1,20,000.00. SMS BLKCC 2345 to 9840909000 if not you",
            senders = listOf("YES")),
        t("idfc_card_spend", "IDFC FIRST Bank",
            "IDFC FIRST Bank (?:Credit |Debit )?Card ending {L4} has been used for {CUR}\\s?{AMT} at {MERCHANT} on {DATE}",
            DEBIT, "Your IDFC FIRST Bank Credit Card ending XX3456 has been used for INR 1,899.00 at MYNTRA on 03-NOV-2026 at 08:12 PM. Available limit: INR 74,101.00",
            senders = listOf("IDFC")),
        t("au_acct_debit", "AU Small Finance Bank",
            "A/c {L4} has been debited (?:with|by) {CUR}\\s?{AMT} on {DATE} towards {MERCHANT}\\.\\s?Avl Bal:?\\s?{BAL}",
            null, "Your A/c XX6789 has been debited with INR 450.00 on 03-NOV-26 towards UPI/412312345678/SWIGGY. Avl Bal INR 9,550.00 - AU Small Finance Bank",
            senders = listOf("AU ?BANK", "AUSFB", "AU Small", "aubank"), direction = TxnDirection.DEBIT),
        t("federal_upi_debit", "Federal Bank",
            "{CUR}\\s?{AMT} debited from your A/c {L4} to VPA {MERCHANT} on {DATE}\\.\\s?UPI Ref:?\\s?{REF}",
            UPI_SENT, "Rs 320.00 debited from your A/c XX7890 to VPA blinkit@hdfcbank on 03-11-2026. UPI Ref 412311112222. -Federal Bank",
            senders = listOf("FED")),
        t("indusind_card_spend", "IndusInd Bank",
            "{CUR}\\s?{AMT} spent on IndusInd Bank (?:Credit |Debit )?Card {L4} at {MERCHANT} on {DATE}",
            DEBIT, "INR 2,150.00 spent on IndusInd Bank Credit Card XX8901 at DECATHLON SPORTS on 03-11-26. Avl Lmt INR 50,000.00",
            senders = listOf("INDUS", "IBL")),
        // ---------------- Generic Indian formats ----------------
        t("generic_emi_debit", null,
            "EMI of {CUR}\\s?{AMT} for (?:your )?(?:Loan )?(?:A/?c|Account)(?: no\\.?)? {L4} has been (?:debited|deducted|paid)(?: on {DATE})?",
            EMI, "Your EMI of Rs 4,567.00 for Loan A/c XX4321 has been debited on 05-11-26. -HDFC Bank", confidence = 0.9),
        t("generic_refund", null,
            "Refund of {CUR}\\s?{AMT} from {MERCHANT} has been (?:credited|processed) to your .*?(?:Card|A/c|Account)(?: ending| no\\.?)? {L4}(?: on {DATE})?",
            REFUND, "Refund of Rs 499.00 from MYNTRA has been credited to your HDFC Bank Credit Card XX1234 on 03-11-26.", confidence = 0.9),
        t("generic_cc_payment_received", null,
            "Payment of {CUR}\\s?{AMT} (?:has been )?received (?:on|towards|for) your [A-Za-z ]*?Credit Card {L4}(?: on {DATE})?",
            CREDIT, "Payment of Rs 15,000.00 received on your ICICI Bank Credit Card XX5678 on 03-Nov-26. Thank you.", confidence = 0.9),
        t("generic_salary", null,
            "(?:Salary|SAL) (?:of |credit of )?{CUR}\\s?{AMT} (?:has been )?credited to (?:your )?(?:A/c|Account|Acct) {L4}(?: on {DATE})?",
            SALARY, "Salary of Rs 85,000.00 credited to your A/c XX9012 on 31-10-2026. -Kotak Bank", confidence = 0.9),
        t("generic_statement", null,
            "Total (?:Amount |Amt\\.? )?Due:? {CUR}\\s?{AMT}.{0,40}?Min(?:imum)?\\.? (?:Amount |Amt\\.? )?Due:? {MCUR}\\s?{MIN}.{0,60}?(?:due (?:date|by|on)|pay by):? {DUE}",
            null, "Your SBI Card statement for card ending 4321 is ready. Total Amt Due Rs 5,432.10 Min Amt Due Rs 271.00 Payment Due Date 20-11-2026", confidence = 0.88),
        t("upi_app_paid", null,
            "(?:You paid|Paid|Sent|You sent|Payment of) {CUR}\\s?{AMT} (?:to|at) {MERCHANT}{END}",
            UPI_SENT, "You paid ₹250.00 to Zomato", senders = UPI_APPS, confidence = 0.9),
        t("upi_app_amount_first", null,
            "(?<![\\w.,]){CUR}\\s?{AMT} (?:paid|sent) (?:to|at) {MERCHANT}{END}",
            UPI_SENT, "₹349 paid to Domino's Pizza | Paid with HDFC Bank •• 1234", senders = UPI_APPS, confidence = 0.9),
        t("upi_app_received", null,
            "(?:Received|You received|You've received) {CUR}\\s?{AMT} from {MERCHANT}{END}",
            UPI_RECEIVED, "Received ₹500 from Ravi Kumar", senders = UPI_APPS, confidence = 0.9),
        t("generic_fx_dual", null,
            "{CUR}\\s?{AMT}\\s?\\(\\s?{ACUR}\\s?{AAMT}\\s?\\)",
            null, "Txn of USD 45.00 (INR 3,912.30) on your Card XX1234 at AWS EMEA on 03-11-26", confidence = 0.7),
        // ---------------- International ----------------
        t("amex_charge", "American Express",
            "A charge of {CUR}\\s?{AMT} at {MERCHANT} was made on your (?:American Express |Amex )?Card ending(?: in)? {L4}(?: on {DATE})?",
            DEBIT, "Amex Alert: A charge of \$45.20 at STARBUCKS was made on your Card ending in 31005 on Nov 3, 2026.",
            senders = listOf("AMEX", "American Express", "americanexpress", "aexp")),
        t("amex_in_spend", "American Express",
            "(?:You(?:'ve| have)? (?:made a purchase|spent)|Purchase) of {CUR}\\s?{AMT} at {MERCHANT} on your (?:American Express|Amex) Card ending(?: in)? {L4}",
            DEBIT, "AMEX: You have made a purchase of INR 3,456.00 at ZARA on your American Express Card ending 21009",
            senders = listOf("AMEX", "American Express", "americanexpress", "aexp")),
        t("neobank_you_paid", null,
            "You paid {CUR}\\s?{AMT} (?:at|to) {MERCHANT}{END}",
            DEBIT, "You paid €12.50 at Lidl", senders = NEOBANKS, confidence = 0.93),
        t("neobank_received", null,
            "(?:You received|You've received|You got|Received) {CUR}\\s?{AMT} from {MERCHANT}{END}",
            CREDIT, "You received £50.00 from John Smith", senders = NEOBANKS, confidence = 0.93),
        t("wise_spent_suffix", "Wise",
            "You spent {AMT}\\s?{CUR} at {MERCHANT}{END}",
            DEBIT, "You spent 23.40 EUR at REWE Markt GmbH.", senders = NEOBANKS),
        t("card_spent_prefix", null,
            "You spent {CUR}\\s?{AMT} at {MERCHANT}{END}",
            DEBIT, "You spent €23.40 at REWE", senders = NEOBANKS, confidence = 0.93),
        t("wise_sent", "Wise",
            "You(?:'ve| have)? sent {AMT}\\s?{CUR} to {MERCHANT}{END}",
            DEBIT, "You've sent 100.00 GBP to Jane Doe.", senders = NEOBANKS),
        t("chase_txn", "Chase",
            "You made an? {CUR}\\s?{AMT} transaction with {MERCHANT} on {DATE}",
            DEBIT, "Chase Freedom: You made a \$24.99 transaction with NETFLIX.COM on Nov 3, 2026 at 8:15 PM ET.",
            senders = listOf("chase", "24273")),
        t("hsbc_card", "HSBC",
            "Your (?:Credit|Debit) Card ending {L4} was used for {CUR}\\s?{AMT} at {MERCHANT} on {DATE}",
            DEBIT, "HSBC: Your Credit Card ending 6789 was used for GBP 18.75 at TESCO STORES on 03/11/2026.",
            senders = listOf("HSBC")),
        t("monzo_spend", "Monzo",
            "(?<![\\w.,])(?<currency>£|€|\\x24)\\s?{AMT} at {MERCHANT}{END}",
            DEBIT, "£4.50 at Pret A Manger | 🥪 Lunch", senders = listOf("monzo", "getmondo", "starling")),
    )

    val library: TemplateLibrary = TemplateLibrary(VERSION, templates, "2026-11-05")
}
