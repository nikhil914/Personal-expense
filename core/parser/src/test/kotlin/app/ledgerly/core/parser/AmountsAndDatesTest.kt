package app.ledgerly.core.parser

import app.ledgerly.core.model.CurrencyCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmountsAndDatesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = ';',
        value = [
            "1,23,456.78;123456.78", "12,34,56,789;123456789", "1,234.50;1234.50", "500;500", "250.0;250.0",
            "1.234,56;1234.56", "12,50;12.50", "500/-;500", "1.234.567;1234567",
        ],
    )
    fun `parses numbers`(raw: String, expected: String) {
        assertEquals(0, BigDecimal(expected).compareTo(AmountParser.parseNumber(raw)))
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = ';',
        value = [
            "Rs.1,250.00 spent;1250.00;INR", "Rs 500 debited;500.00;INR", "₹ 99 paid;99.00;INR", "INR3,912.30 debited;3912.30;INR",
            "\$45.20 at Starbucks;45.20;USD", "USD 45.00 spent;45.00;USD", "€12,50 at Lidl;12.50;EUR", "£4.50 at Pret;4.50;GBP",
            "AED 120.00 spent;120.00;AED", "SGD 35.10 at Grab;35.10;SGD", "¥1,200 at Lawson;1200;JPY", "spent 23.40 EUR at REWE;23.40;EUR",
            "Rs.1,23,456.78 credited;123456.78;INR", "S\$12.00 at Kopitiam;12.00;SGD", "KWD 1.250 at Avenues;1.250;KWD",
        ],
    )
    fun `finds currency amounts`(text: String, amount: String, currency: String) {
        val hit = AmountParser.findAll(text).first()
        assertEquals(CurrencyCode.of(currency), hit.money.currency)
        assertEquals(0, BigDecimal(amount).compareTo(hit.money.toDecimal()))
    }

    @Test
    fun `does not treat bare numbers or words as currency`() {
        assertTrue(AmountParser.findAll("Call 18002586161 or SMS BLOCK 1234 to 7308080808").isEmpty())
        assertTrue(AmountParser.findAll("Hrs 12 left; Mrs 50").isEmpty())
    }

    @Test
    fun `multiple currencies are found in order`() {
        val hits = AmountParser.findAll("USD 45.00 (INR 3,912.30)")
        assertEquals(listOf("USD", "INR"), hits.map { it.money.currency.code })
    }

    @Test
    fun `currency resolution`() {
        assertEquals(CurrencyCode.INR, Currencies.resolve("Rs."))
        assertEquals(CurrencyCode.INR, Currencies.resolve("₹"))
        assertEquals(CurrencyCode.EUR, Currencies.resolve("€"))
        assertEquals(CurrencyCode.of("CAD"), Currencies.resolve("\$", CurrencyCode.of("CAD")))
        assertNull(Currencies.resolve("xyz1"))
        assertNull(Currencies.resolve(""))
    }

    private val dates = DateResolver(ZoneId.of("Asia/Kolkata"))

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = ';',
        value = [
            "on 03-11-26 at;2026-11-03", "on 03/11/2026;2026-11-03", "dated 03.11.2026;2026-11-03", "on 03-NOV-26.;2026-11-03",
            "on 03Nov26 trf;2026-11-03", "on 2026-11-03:14:22:10;2026-11-03", "on 3 Nov 2026;2026-11-03", "on Nov 3, 2026 at;2026-11-03",
            "on 03 Nov at 10:00;2026-11-03", "on 28 Dec;2025-12-28", "on 31-OCT-26 for;2026-10-31", "on 13/02/2026;2026-02-13",
        ],
    )
    fun `parses dates relative to receivedAt`(text: String, expected: String) {
        val hit = assertNotNull(dates.findFirst(text, RECEIVED), text)
        assertEquals(LocalDate.parse(expected), hit.date)
    }

    @Test
    fun `parses times with am pm`() {
        val hit = assertNotNull(dates.findFirst("on 03-11-2026 at 09:15 PM.", RECEIVED))
        assertEquals(LocalTime.of(21, 15), hit.time)
        assertEquals(ist(2026, 11, 3, 21, 15), dates.toInstant(hit, RECEIVED))
    }

    @Test
    fun `same-day date keeps receivedAt and future dates fall back`() {
        assertEquals(RECEIVED, dates.toInstant(dates.findFirst("on 05-11-26", RECEIVED), RECEIVED))
        assertEquals(RECEIVED, dates.toInstant(dates.findFirst("on 25-12-26", RECEIVED), RECEIVED))
    }

    @Test
    fun `due dates prefer the future`() {
        val hit = assertNotNull(dates.findFirst("due on 15 Jan", RECEIVED, preferFuture = true))
        assertEquals(LocalDate.of(2027, 1, 15), hit.date)
    }

    @Test
    fun `month-first numeric dates when configured`() {
        val us = DateResolver(ZoneId.of("America/New_York"), dayFirst = false)
        assertEquals(LocalDate.of(2026, 11, 3), us.findFirst("on 11/03/2026", RECEIVED)?.date)
    }

    @Test
    fun `invalid dates are ignored`() {
        assertNull(dates.findFirst("on 31-02-2026", RECEIVED))
        assertNull(dates.findFirst("Supermarket 24 hours", RECEIVED))
    }
}
