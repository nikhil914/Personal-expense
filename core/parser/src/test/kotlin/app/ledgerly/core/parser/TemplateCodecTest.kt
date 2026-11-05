package app.ledgerly.core.parser

import app.ledgerly.core.model.MessageKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateCodecTest {

    @Test
    fun `built-in library round trips through json`() {
        val json = TemplateCodec.encode(BuiltInTemplates.library)
        assertTrue(json.contains("\"version\": ${BuiltInTemplates.VERSION}"))
        val back = TemplateCodec.decodeStrict(json)
        assertEquals(BuiltInTemplates.library, back)
        assertEquals(back, TemplateCodec.decode(TemplateCodec.encode(back, pretty = false)))
    }

    @Test
    fun `lenient decode skips invalid templates, strict fails`() {
        val json = """{"version": 7, "templates": [
            {"id":"ok","institution":"X","pattern":"Paid {CUR}\\s?{AMT} to {MERCHANT}{END}","kind":"DEBIT"},
            {"id":"bad-regex","pattern":"(unclosed"},
            {"id":"bad-kind","pattern":"x","kind":"NOPE"}
        ]}"""
        val lib = TemplateCodec.decode(json)
        assertEquals(7, lib.version)
        assertEquals(listOf("ok"), lib.templates.map { it.id })
        assertEquals(MessageKind.DEBIT, lib.templates[0].kind)
        assertThrows<IllegalArgumentException> { TemplateCodec.decodeStrict(json) }
    }

    @Test
    fun `remote update replaces templates only when newer and keeps learned ones`() {
        val engine = TemplateEngine()
        val learned = ParserTemplate("learned_1", null, "foo {AMT}", learned = true, kind = MessageKind.DEBIT)
        engine.add(learned)
        val remote = TemplateLibrary(BuiltInTemplates.VERSION + 1, listOf(ParserTemplate("r1", "R", "Paid {CUR}\\s?{AMT}", kind = MessageKind.DEBIT)))
        assertFalse(engine.applyLibrary(remote.copy(version = BuiltInTemplates.VERSION)))
        assertTrue(engine.applyLibrary(remote))
        assertEquals(setOf("r1", "learned_1"), engine.templates().map { it.id }.toSet())
        assertEquals(BuiltInTemplates.VERSION + 1, engine.exportLibrary().version)
    }

    @Test
    fun `engine rejects invalid regex on add`() {
        assertThrows<IllegalArgumentException> { TemplateEngine(emptyList()).add(ParserTemplate("x", null, "([")) }
    }

    @Test
    fun `mini json handles escapes and unicode`() {
        val v = mapOf("s" to "a\"b\\c\n₹ \u0001", "n" to 12L, "d" to 0.5, "b" to true, "z" to null, "l" to listOf(1L, "x"))
        assertEquals(v, MiniJson.parse(MiniJson.stringify(v)))
        assertEquals("€", MiniJson.parse("\"\\u20ac\""))
        assertThrows<MiniJson.JsonException> { MiniJson.parse("{\"a\":1} extra") }
        assertThrows<MiniJson.JsonException> { MiniJson.parse("[1,2") }
    }
}
