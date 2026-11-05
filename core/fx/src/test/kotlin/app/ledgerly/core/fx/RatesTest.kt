package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.CurrencyCode.Companion.EUR
import app.ledgerly.core.model.CurrencyCode.Companion.INR
import app.ledgerly.core.model.CurrencyCode.Companion.JPY
import app.ledgerly.core.model.CurrencyCode.Companion.KWD
import app.ledgerly.core.model.CurrencyCode.Companion.USD
import app.ledgerly.core.model.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatesTest {
    private val d = LocalDate.of(2026, 3, 10)
    private fun r(base: CurrencyCode, quote: CurrencyCode, rate: String, date: LocalDate = d, src: String = "ECB") =
        FxRate(date, base, quote, BigDecimal(rate), src)

    @Test fun `direct lookup on exact date is not stale`() {
        val s = InMemoryRateStore().apply { put(r(USD, INR, "92.0")) }
        val q = assertNotNull(s.lookup(USD, INR, d))
        assertEquals(0, BigDecimal("92.0").compareTo(q.rate))
        assertFalse(q.isStale)
        assertEquals(RateDerivation.DIRECT, q.derivation)
    }

    @Test fun `falls back to nearest earlier date and flags stale`() {
        val s = InMemoryRateStore(maxStalenessDays = 5).apply {
            put(r(USD, INR, "91.0", d.minusDays(4)))
            put(r(USD, INR, "91.5", d.minusDays(2)))
        }
        val q = assertNotNull(s.lookup(USD, INR, d))
        assertEquals(d.minusDays(2), q.rateDate)
        assertTrue(q.isStale)
        assertEquals(2, q.ageDays)
    }

    @Test fun `never uses a later rate and respects max staleness`() {
        val s = InMemoryRateStore(maxStalenessDays = 3).apply {
            put(r(USD, INR, "91.0", d.minusDays(5)))
            put(r(USD, INR, "93.0", d.plusDays(1)))
        }
        assertNull(s.lookup(USD, INR, d))
    }

    @Test fun `stale tolerance covers weekends`() {
        val s = InMemoryRateStore(staleToleranceDays = 3).apply { put(r(EUR, USD, "1.08", d.minusDays(2))) }
        assertFalse(s.lookup(EUR, USD, d)!!.isStale)
    }

    @Test fun `inverse pair is derived`() {
        val s = InMemoryRateStore().apply { put(r(EUR, USD, "1.25")) }
        val q = assertNotNull(s.lookup(USD, EUR, d))
        assertEquals(RateDerivation.INVERSE, q.derivation)
        assertEquals(0, BigDecimal("0.8").compareTo(q.rate))
    }

    @Test fun `triangulates via EUR pivot from ECB data`() {
        val s = InMemoryRateStore().apply {
            put(r(EUR, JPY, "160.00"))
            put(r(EUR, INR, "96.00"))
        }
        val q = assertNotNull(s.lookup(JPY, INR, d))
        assertEquals(RateDerivation.TRIANGULATED, q.derivation)
        assertEquals(listOf(JPY, EUR, INR), q.path)
        assertEquals(BigDecimal("0.6000"), q.rate.setScale(4, java.math.RoundingMode.HALF_EVEN))
    }

    @Test fun `triangulates via USD when EUR legs missing and uses oldest leg date`() {
        val s = InMemoryRateStore().apply {
            put(r(USD, KWD, "0.307", d.minusDays(1), "OXR"))
            put(r(USD, INR, "92.0", d, "OXR"))
        }
        val q = assertNotNull(s.lookup(KWD, INR, d))
        assertEquals(listOf(KWD, USD, INR), q.path)
        assertEquals(d.minusDays(1), q.rateDate)
        assertTrue(q.isStale)
        assertEquals("OXR", q.source)
    }

    @Test fun `identity rate for same currency`() {
        val q = InMemoryRateStore().lookup(INR, INR, d)!!
        assertEquals(BigDecimal.ONE, q.rate)
        assertEquals(RateDerivation.IDENTITY, q.derivation)
    }

    @Test fun `direct preferred over newer-or-equal inverse only on tie`() {
        val s = InMemoryRateStore().apply {
            put(r(USD, INR, "90.0", d.minusDays(1)))
            put(r(INR, USD, "0.0125", d)) // = 80 INR per USD, newer
        }
        assertEquals(RateDerivation.INVERSE, s.lookup(USD, INR, d)!!.derivation)
    }

    @Test fun `rejects non-positive rate`() {
        assertThrows<IllegalArgumentException> { r(USD, INR, "0") }
    }

    @Test fun `converter rounds HALF_EVEN to JPY and KWD exponents`() {
        val s = InMemoryRateStore().apply {
            put(r(USD, JPY, "150.125"))
            put(r(USD, KWD, "0.30725"))
        }
        val c = Converter(s)
        // 10.10 USD * 150.125 = 1516.2625 -> 1516 JPY
        assertEquals(Money(1516, JPY), c.convert(Money.of("10.10", USD), JPY, d).result)
        // 0.10 USD * 150.125 = 15.0125 -> 15; 0.03 USD * 150 = 4.50375 -> 5
        assertEquals(Money(5, JPY), c.convert(Money.of("0.03", USD), JPY, d).result)
        // 12.34 USD * 0.30725 = 3.791465 -> 3.791 KWD
        assertEquals(Money(3791, KWD), c.convert(Money.of("12.34", USD), KWD, d).result)
        // JPY -> USD inverse: 1000 JPY / 150.125 = 6.6611... -> 6.66
        assertEquals(Money.of("6.66", USD), c.convert(Money(1000, JPY), USD, d).result)
    }

    @Test fun `half even on exact midpoint`() {
        val s = InMemoryRateStore().apply { put(r(USD, JPY, "100.5")) }
        // 1.00 * 100.5 = 100.5 -> 100 (even); 3.00*100.5 = 301.5 -> 302
        assertEquals(Money(100, JPY), Converter(s).convert(Money.of("1.00", USD), JPY, d).result)
        assertEquals(Money(302, JPY), Converter(s).convert(Money.of("3.00", USD), JPY, d).result)
    }

    @Test fun `conversion reports rate metadata and throws when unavailable`() {
        val s = InMemoryRateStore().apply { put(r(USD, INR, "92.0", d.minusDays(1))) }
        val conv = Converter(s).convert(Money.of("45.00", USD), INR, d)
        assertEquals(Money.of("4140.00", INR), conv.result)
        assertEquals(d.minusDays(1), conv.rateDate)
        assertTrue(conv.stale)
        assertEquals("ECB", conv.source)
        assertThrows<RateUnavailableException> { Converter(s).convert(Money.of("1", USD), JPY, d) }
        assertNull(Converter(s).convertOrNull(Money.of("1", USD), JPY, d))
    }
}
