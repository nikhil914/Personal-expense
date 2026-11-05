package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParsersTest {
    private val daily = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
            <gesmes:subject>Reference rates</gesmes:subject>
            <gesmes:Sender><gesmes:name>European Central Bank</gesmes:name></gesmes:Sender>
            <Cube>
                <Cube time='2026-03-10'>
                    <Cube currency='USD' rate='1.0850'/>
                    <Cube currency='JPY' rate='161.42'/>
                    <Cube currency='INR' rate='99.8125'/>
                    <Cube currency='GBP' rate='0.84633'/>
                </Cube>
            </Cube>
        </gesmes:Envelope>
    """.trimIndent()

    @Test fun `parses ECB daily xml`() {
        val rates = EcbXmlParser.parse(daily)
        assertEquals(4, rates.size)
        val usd = rates.first { it.quote == CurrencyCode.USD }
        assertEquals(CurrencyCode.EUR, usd.base)
        assertEquals(LocalDate.of(2026, 3, 10), usd.date)
        assertEquals(BigDecimal("1.0850"), usd.rate)
        assertEquals("ECB", usd.source)
    }

    @Test fun `parses ECB historical xml with multiple days and feeds store`() {
        val hist = """
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
            <Cube>
              <Cube time="2026-03-10"><Cube currency="USD" rate="1.09"/><Cube currency="INR" rate="100.0"/></Cube>
              <Cube time="2026-03-09"><Cube currency="USD" rate="1.08"/><Cube currency="INR" rate="99.0"/></Cube>
            </Cube></gesmes:Envelope>
        """.trimIndent()
        val rates = EcbXmlParser.parse(hist)
        assertEquals(4, rates.size)
        val store = InMemoryRateStore().apply { putAll(rates) }
        val q = store.lookup(CurrencyCode.USD, CurrencyCode.INR, LocalDate.of(2026, 3, 9))!!
        assertEquals("91.6667", q.rate.setScale(4, java.math.RoundingMode.HALF_EVEN).toPlainString())
    }

    @Test fun `ECB parser rejects DOCTYPE entities and garbage`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><Cube><Cube time="2026-01-01"><Cube currency="USD" rate="1"/></Cube></Cube>"""
        assertThrows<RateParseException> { EcbXmlParser.parse(evil) }
        assertThrows<RateParseException> { EcbXmlParser.parse("not xml") }
        assertThrows<RateParseException> { EcbXmlParser.parse("<Cube></Cube>") }
    }

    @Test fun `parses open exchange rates json`() {
        val json = """
            { "disclaimer": "x", "license": "y", "timestamp": 1773100800, "base": "USD",
              "rates": { "INR": 92.0, "JPY": 150.125, "KWD": 0.30725, "EUR": 9.2e-1, "BAD": "x", "USD": 1 },
              "nested": [1, {"a": null}, true, false, "esc\"apedé"] }
        """.trimIndent()
        val rates = OpenExchangeRatesJsonParser.parse(json)
        assertEquals(4, rates.size)
        val inr = rates.first { it.quote == CurrencyCode.INR }
        assertEquals(CurrencyCode.USD, inr.base)
        assertEquals(LocalDate.of(2026, 3, 10), inr.date)
        assertEquals(0, BigDecimal("0.92").compareTo(rates.first { it.quote == CurrencyCode.EUR }.rate))
        assertTrue(rates.all { it.source == "OXR" })
    }

    @Test fun `json uses fallback date and rejects malformed input`() {
        val r = OpenExchangeRatesJsonParser.parse("""{"base":"EUR","rates":{"USD":1.08}}""", LocalDate.of(2026, 1, 2))
        assertEquals(LocalDate.of(2026, 1, 2), r.single().date)
        assertThrows<RateParseException> { OpenExchangeRatesJsonParser.parse("""{"base":"EUR","rates":{"USD":1.08}""", LocalDate.now()) }
        assertThrows<RateParseException> { OpenExchangeRatesJsonParser.parse("""{"rates":{}}""", LocalDate.now()) }
        assertThrows<RateParseException> { OpenExchangeRatesJsonParser.parse("""{"base":"EUR","rates":{"USD":1.08}}""") }
    }

    @Test fun `rate provider is a fun interface`() {
        val p = RateProvider { EcbXmlParser.parse(daily) }
        assertEquals(4, p.fetch(null).size)
    }
}
