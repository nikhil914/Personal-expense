package app.ledgerly.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun parsesWithExponent() {
        assertEquals(123450L, Money.of("1,234.50", "INR").minor)
        assertEquals(1235L, Money.of("1234.5", "JPY").minor)
        assertEquals(1234500L, Money.of("1234.5", "KWD").minor)
    }

    @Test
    fun rejectsCurrencyMismatch() {
        assertFailsWith<IllegalArgumentException> { Money.of("1", "INR") + Money.of("1", "USD") }
    }

    @Test
    fun formats() {
        assertEquals("12.50 EUR", Money.of("12.5", "EUR").toString())
    }
}
