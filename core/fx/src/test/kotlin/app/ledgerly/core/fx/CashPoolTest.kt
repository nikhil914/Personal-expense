package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode.Companion.EUR
import app.ledgerly.core.model.CurrencyCode.Companion.INR
import app.ledgerly.core.model.CurrencyCode.Companion.JPY
import app.ledgerly.core.model.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashPoolTest {
    private val d1 = LocalDate.of(2026, 5, 1)
    private fun eur(s: String) = Money.of(s, EUR)

    @Test fun `buy creates lot with rate`() {
        val l = CashPoolLedger(INR)
        val lot = l.buy(d1, eur("300.00"), inr("27600.00"))
        assertEquals(BigDecimal("92.0000000000"), lot.rate)
        assertEquals(eur("300.00"), l.balance(EUR))
        assertEquals(inr("27600.00"), l.costBasis(EUR))
    }

    @Test fun `FIFO spend across partial lots`() {
        val l = CashPoolLedger(INR, CostMethod.FIFO)
        l.buy(d1, eur("300.00"), inr("27600.00"), "L1")   // 92.00
        l.buy(d1.plusDays(1), eur("200.00"), inr("19000.00"), "L2") // 95.00
        val s1 = l.spend(d1.plusDays(2), eur("250.00"), "dinner")
        assertEquals(inr("23000.00"), s1.homeCost)
        assertEquals(listOf("L1"), s1.draws.map { it.lotId })
        val s2 = l.spend(d1.plusDays(3), eur("100.00"))
        // 50 from L1 @92 = 4600, 50 from L2 @95 = 4750
        assertEquals(inr("9350.00"), s2.homeCost)
        assertEquals(listOf("L1", "L2"), s2.draws.map { it.lotId })
        assertEquals(listOf(inr("4600.00"), inr("4750.00")), s2.draws.map { it.homeCost })
        assertFalse(s2.isOverdrawn)
        assertEquals(eur("150.00"), l.balance(EUR))
        assertEquals(inr("14250.00"), l.costBasis(EUR))
        assertEquals(1, l.openLots(EUR).size)
    }

    @Test fun `AVERAGE mode uses moving average cost`() {
        val l = CashPoolLedger(INR, CostMethod.AVERAGE)
        l.buy(d1, eur("300.00"), inr("27600.00"))
        l.buy(d1, eur("200.00"), inr("19000.00"))
        // avg = 46600/500 = 93.20
        val s = l.spend(d1, eur("100.00"))
        assertEquals(inr("9320.00"), s.homeCost)
        assertEquals("AVERAGE", s.draws.single().lotId)
        assertEquals(inr("37280.00"), l.costBasis(EUR))
        val s2 = l.spend(d1, eur("400.00"))
        assertEquals(inr("37280.00"), s2.homeCost)
        assertEquals(Money.zero(EUR), l.balance(EUR))
        assertEquals(Money.zero(INR), l.costBasis(EUR))
    }

    @Test fun `overdraw is flagged and estimated at last rate`() {
        val l = CashPoolLedger(INR)
        l.buy(d1, eur("50.00"), inr("4600.00"))
        val s = l.spend(d1, eur("80.00"))
        assertTrue(s.isOverdrawn)
        assertEquals(eur("30.00"), s.overdrawn)
        assertEquals(inr("2760.00"), s.overdrawnHomeEstimate)
        assertEquals(inr("7360.00"), s.homeCost)
        // no history at all for JPY
        val s2 = l.spend(d1, Money(1000, JPY))
        assertTrue(s2.isOverdrawn)
        assertEquals(Money.zero(INR), s2.homeCost)
    }

    @Test fun `leftover revaluation gives unrealised gain or loss`() {
        val l = CashPoolLedger(INR)
        l.buy(d1, eur("300.00"), inr("27600.00"))
        l.spend(d1, eur("200.00"))
        val r = l.revalue(EUR, BigDecimal("95.50"), d1.plusDays(10))
        assertEquals(eur("100.00"), r.foreignBalance)
        assertEquals(inr("9200.00"), r.costBasis)
        assertEquals(inr("9550.00"), r.marketValue)
        assertEquals(inr("350.00"), r.unrealisedGain)
        val store = InMemoryRateStore().apply { put(FxRate(d1, EUR, INR, BigDecimal("90"), "ECB")) }
        assertEquals(inr("-200.00"), l.revalue(EUR, store, d1).unrealisedGain)
    }

    @Test fun `reconverting leftover records realised gain loss`() {
        val l = CashPoolLedger(INR)
        l.buy(d1, eur("300.00"), inr("27600.00"), "L1")
        l.buy(d1, eur("100.00"), inr("9500.00"), "L2")
        l.spend(d1, eur("250.00"))
        // left: 50 @92 (4600) + 100 @95 (9500) = 14100 cost basis; bureau gives 13,800
        val rc = l.reconvert(d1.plusDays(9), eur("150.00"), inr("13800.00"))
        assertEquals(inr("14100.00"), rc.costBasis)
        assertEquals(inr("-300.00"), rc.realisedGain)
        assertEquals(Money.zero(EUR), l.balance(EUR))
        assertTrue(l.currencies().isEmpty())
    }

    @Test fun `partial-lot rounding never leaks cost`() {
        val l = CashPoolLedger(INR)
        l.buy(d1, eur("100.00"), inr("9333.33"))
        val parts = (1..3).map { l.spend(d1, eur(if (it < 3) "33.33" else "33.34")).homeCost }
        assertEquals(inr("9333.33"), parts.fold(Money.zero(INR)) { a, m -> a + m })
    }

    @Test fun `JPY cash with zero-exponent currency`() {
        val l = CashPoolLedger(INR)
        l.buy(d1, Money(50000, JPY), inr("30500.00")) // 0.61 INR/JPY
        assertEquals(inr("7930.00"), l.spend(d1, Money(13000, JPY)).homeCost)
    }

    @Test fun `validation`() {
        val l = CashPoolLedger(INR)
        assertThrows<IllegalArgumentException> { l.buy(d1, inr("10.00"), inr("10.00")) }
        assertThrows<IllegalArgumentException> { l.buy(d1, eur("10.00"), eur("10.00")) }
        assertThrows<IllegalArgumentException> { l.spend(d1, eur("-1.00")) }
    }
}
