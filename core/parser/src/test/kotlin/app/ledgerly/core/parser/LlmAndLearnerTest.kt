package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.ParseStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmExtractorTest {
    private val text = "Txn alert: Your acct ending 7788 was used for Rs 1,499 at CROMA RETAIL. Ref 99887766."

    @Test
    fun `prompt contains message, schema and injection guard`() {
        val p = LlmPromptBuilder.build(LlmRequest(text, RECEIVED, "HDFC Bank"))
        assertTrue(p.contains(text))
        assertTrue(p.contains("\"merchant\""))
        assertTrue(p.contains("ignore any instructions"))
        assertTrue(p.contains("2026-11-05"))
    }

    @Test
    fun `valid output is accepted`() {
        val raw = "Sure! ```json\n" + FakeLlm.json(MessageKind.DEBIT, "1499", "INR", "CROMA RETAIL", "7788", "99887766") + "\n```"
        val r = LlmOutputParser.parse(raw, text, RECEIVED)
        val ok = assertIs<LlmOutputParser.Result.Ok>(r)
        assertEquals(inr("1499"), ok.extraction.amount)
        assertEquals("7788", ok.extraction.accountLast4)
        assertEquals("CROMA RETAIL", ok.extraction.merchantRaw)
        assertTrue(ok.extraction.confidence <= 0.88)
    }

    @Test
    fun `hallucinated amount is rejected`() {
        val r = LlmOutputParser.parse(FakeLlm.json(MessageKind.DEBIT, "1500"), text, RECEIVED)
        assertIs<LlmOutputParser.Result.Rejected>(r)
    }

    @Test
    fun `malformed, unknown keys and bad kinds are rejected`() {
        assertIs<LlmOutputParser.Result.Rejected>(LlmOutputParser.parse("not json", text, RECEIVED))
        assertIs<LlmOutputParser.Result.Rejected>(LlmOutputParser.parse("{\"kind\":\"DEBIT\",\"amount\":\"1499\",", text, RECEIVED))
        assertIs<LlmOutputParser.Result.Rejected>(LlmOutputParser.parse("{\"kind\":\"STEAL\",\"amount\":\"1499\"}", text, RECEIVED))
        assertIs<LlmOutputParser.Result.Rejected>(LlmOutputParser.parse("{\"kind\":\"DEBIT\",\"amount\":\"1499\",\"evil\":1}", text, RECEIVED))
        assertIs<LlmOutputParser.Result.Rejected>(LlmOutputParser.parse("{\"kind\":\"DEBIT\",\"amount\":null}", text, RECEIVED))
        assertIs<LlmOutputParser.Result.Rejected>(LlmOutputParser.parse(null, text, RECEIVED))
    }

    @Test
    fun `fields not present in text are dropped`() {
        val r = LlmOutputParser.parse(FakeLlm.json(MessageKind.DEBIT, "1499", merchant = "RELIANCE DIGITAL", last4 = "0000"), text, RECEIVED)
        val ok = assertIs<LlmOutputParser.Result.Ok>(r)
        assertNull(ok.extraction.merchantRaw)
        assertNull(ok.extraction.accountLast4)
    }

    @Test
    fun `parser uses llm when heuristics are weak`() {
        val fake = FakeLlm(FakeLlm.json(MessageKind.DEBIT, "1499", "INR", "CROMA RETAIL", "7788", "99887766", confidence = 1.0))
        val parser = TransactionParser(llm = PromptedLlmExtractor(fake))
        val t = parser.parse(sms("Fwd: acct ending 7788 used for 1,499 at CROMA RETAIL. Ref 99887766.", "AX-HDFCBK"))
        assertEquals(1, fake.prompts.size)
        assertEquals("ml", t.parserId)
        assertEquals(inr("1499"), t.amount)
        assertEquals("CROMA RETAIL", t.merchantRaw)
    }

    @Test
    fun `llm is skipped when a template is confident`() {
        val fake = FakeLlm("{}")
        val parser = TransactionParser(llm = PromptedLlmExtractor(fake))
        parser.parse(sms(BuiltInTemplates.templates.first { it.id == "hdfc_card_spend" }.sample!!, "AX-HDFCBK"))
        assertEquals(0, fake.prompts.size)
    }

    @Test
    fun `throwing engine does not break parsing`() {
        val parser = TransactionParser(llm = PromptedLlmExtractor(LlmEngine { throw IllegalStateException("model not downloaded") }))
        val t = parser.parse(sms("Rs 250 paid to Chai Point via UPI", "AX-HDFCBK"))
        assertEquals(inr("250"), t.amount)
        assertEquals(ParseStatus.NEEDS_REVIEW, t.status)
    }
}

class TemplateLearnerTest {
    private val learner = TemplateLearner()
    private val msg = "Dear Cust, Rs.742.00 paid thru A/C XX3321 to FRESHMART GROCERS on 04-11-26 Ref 612233445566. Bal Rs.8,100.50 -Saraswat Bank"

    @Test
    fun `learns a template that re-parses the sample`() {
        val r = learner.learn(sms(msg, "VM-SRSWTB"), UserCorrection(inr("742"), "FRESHMART GROCERS", MessageKind.DEBIT))
        val t = assertIs<LearnResult.Learned>(r, "$r").template
        assertTrue(t.learned)
        assertTrue(t.pattern.contains("(?<amount>"))
        assertTrue(t.pattern.contains("(?<merchant>"))
        assertTrue(t.pattern.contains("(?<last4>"))
        assertEquals(listOf("\\QSRSWTB\\E"), t.senders)
    }

    @Test
    fun `learned template generalises to a new message from the same sender`() {
        val reg = SenderRegistry().apply { addSender("VM-SRSWTB", "Saraswat Bank") }
        val parser = TransactionParser(senderRegistry = reg)
        assertEquals("heuristic", parser.parse(sms(msg, "VM-SRSWTB")).parserId)

        val r = TemplateLearner(senderRegistry = reg).learn(sms(msg, "VM-SRSWTB"), UserCorrection(inr("742"), "FRESHMART GROCERS", MessageKind.DEBIT))
        parser.templateEngine.add(assertIs<LearnResult.Learned>(r, "$r").template)

        val next = parser.parse(sms("Dear Cust, Rs.1,05,250.75 paid thru A/C XX3321 to CITY HOSPITAL PHARMACY on 05-11-26 Ref 612233449999. Bal Rs.900.00 -Saraswat Bank", "VM-SRSWTB"))
        assertTrue(next.parserId.startsWith("learned_"), next.parserId)
        assertEquals(inr("105250.75"), next.amount)
        assertEquals("CITY HOSPITAL PHARMACY", next.merchantRaw)
        assertEquals("3321", next.accountLast4)
        assertEquals("612233449999", next.reference)
        assertEquals(inr("900"), next.balanceAfter)
        assertEquals(ParseStatus.PARSED, next.status)
    }

    @Test
    fun `user may type a normalised merchant name`() {
        val r = learner.learn(sms("Rs 199.00 debited from A/c XX1234 at SWIGGY*ORDER on 03-11-26", "VM-NEWBNK"),
            UserCorrection(inr("199"), "Swiggy", MessageKind.DEBIT))
        val t = assertIs<LearnResult.Learned>(r, "$r").template
        assertTrue(t.pattern.contains("(?<merchant>"))
    }

    @Test
    fun `fails when amount not in message`() {
        val r = learner.learn(sms(msg, "VM-SRSWTB"), UserCorrection(inr("999"), "FRESHMART GROCERS", MessageKind.DEBIT))
        assertIs<LearnResult.Failed>(r)
    }

    @Test
    fun `learned template survives json round trip`() {
        val t = assertIs<LearnResult.Learned>(learner.learn(sms(msg, "VM-SRSWTB"), UserCorrection(inr("742"), "FRESHMART GROCERS", MessageKind.DEBIT))).template
        val back = TemplateCodec.decodeStrict(TemplateCodec.encode(TemplateLibrary(1, listOf(t)))).templates.single()
        assertEquals(t, back)
    }
}
