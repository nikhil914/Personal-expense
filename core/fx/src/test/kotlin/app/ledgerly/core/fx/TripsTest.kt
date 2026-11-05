package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.CurrencyCode.Companion.EUR
import app.ledgerly.core.model.CurrencyCode.Companion.INR
import app.ledgerly.core.model.CurrencyCode.Companion.JPY
import app.ledgerly.core.model.CurrencyCode.Companion.USD
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import app.ledgerly.core.model.TxnType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripsTest {
    private val z = ZoneOffset.UTC
    private fun t(id: String, o: Money, day: Int, acct: String = "hdfc", base: Money? = null, cat: String? = null,
                  fee: Money? = null, tripId: String? = null, type: TxnType = TxnType.EXPENSE) =
        Transaction(id, acct, type, occurredAt = at(2026, 4, day), original = o, account = base ?: o, base = base ?: o,
            categoryId = cat, fxFee = fee, tripId = tripId)

    @Test fun `currency country map covers 60+ currencies`() {
        assertTrue(CurrencyCountries.size >= 60)
        assertEquals("JP", CurrencyCountries.countryFor(JPY))
        assertEquals(EUR, CurrencyCountries.currencyFor("FR"))
        assertEquals("Japan", CurrencyCountries.countryName("JP"))
        assertEquals("Thailand", CurrencyCountries.countryName(CurrencyCountries.countryFor(CurrencyCode.of("THB"))!!))
    }

    @Test fun `suggests trip on first foreign currency transaction`() {
        val det = TripDetector(INR, zone = z)
        val s = det.suggestStart(listOf(t("1", inr("100.00"), 1), t("2", Money(1500, JPY), 3), t("3", Money(800, JPY), 4)), emptyList())
        val sug = s.single()
        assertEquals(JPY, sug.currency); assertEquals("JP", sug.countryGuess)
        assertEquals(LocalDate.of(2026, 4, 3), sug.startDate)
        assertEquals(listOf("2", "3"), sug.txnIds)
        assertTrue(sug.message.contains("Japan"))
    }

    @Test fun `respects min foreign txns and active trips`() {
        val det = TripDetector(INR, minForeignTxns = 2, zone = z)
        assertTrue(det.suggestStart(listOf(t("2", Money(1500, JPY), 3)), emptyList()).isEmpty())
        val trip = Trip("tr", "Japan", JPY, "JP", LocalDate.of(2026, 4, 1), null)
        val txns = listOf(t("2", Money(1500, JPY), 3), t("3", Money(800, JPY), 4))
        assertTrue(TripDetector(INR, zone = z).suggestStart(txns, listOf(trip)).isEmpty())
    }

    @Test fun `country from merchant triggers trip even when billed in home currency`() {
        val det = TripDetector(INR, zone = z, merchantCountry = { if (it.id == "x") "TH" else null })
        val s = det.suggestStart(listOf(t("x", inr("2000.00"), 5)), emptyList()).single()
        assertEquals(CurrencyCode.of("THB"), s.currency)
        assertEquals("TH", s.countryGuess)
    }

    @Test fun `ignore predicate filters online foreign subscriptions`() {
        val det = TripDetector(INR, zone = z, ignore = { it.id == "netflix" })
        assertTrue(det.suggestStart(listOf(t("netflix", usd("15.49"), 2)), emptyList()).isEmpty())
    }

    @Test fun `suggests ending trip after N days without foreign txns`() {
        val trip = Trip("tr", "Japan", JPY, "JP", LocalDate.of(2026, 4, 1), null)
        val txns = listOf(t("a", Money(1500, JPY), 2), t("b", Money(800, JPY), 6), t("c", inr("300.00"), 9))
        val det = TripDetector(INR, endAfterDays = 3, zone = z)
        assertNull(det.suggestEnd(trip, txns, LocalDate.of(2026, 4, 8)))
        val end = assertNotNull(det.suggestEnd(trip, txns, LocalDate.of(2026, 4, 9)))
        assertEquals(LocalDate.of(2026, 4, 6), end.suggestedEnd)
        assertEquals(3, end.daysWithoutForeign)
    }

    @Test fun `trip summary totals categories per day fees and cash vs card`() {
        val trip = Trip("tr", "Japan", JPY, "JP", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 5))
        val txns = listOf(
            t("a", Money(10000, JPY), 1, base = inr("6100.00"), cat = "food", fee = inr("200.00")),
            t("b", Money(20000, JPY), 2, base = inr("12200.00"), cat = "hotel", fee = inr("400.00")),
            t("c", Money(5000, JPY), 3, acct = "cash-jpy", base = inr("3050.00"), cat = "food"),
            t("d", inr("999.00"), 3, cat = "food"),               // home currency, untagged -> excluded
            t("e", inr("1500.00"), 4, cat = "transport", tripId = "tr"), // tagged -> included
            t("f", Money(2000, JPY), 4, base = inr("1220.00"), cat = "food", type = TxnType.REFUND),
            t("g", Money(30000, JPY), 2, base = inr("18300.00"), type = TxnType.ATM_WITHDRAWAL),
        )
        val ledger = CashPoolLedger(INR).apply { buy(LocalDate.of(2026, 3, 30), Money(20000, JPY), inr("12000.00")) }
        val spend = ledger.spend(LocalDate.of(2026, 4, 4), Money(5000, JPY))
        val s = TripSummary.compute(trip, txns, INR, z, cashAccountIds = setOf("cash-jpy"), cashSpends = listOf(spend to "food"))
        // 6100+12200+3050+1500-1220+3000
        assertEquals(inr("24630.00"), s.total)
        assertEquals(5, s.days)
        assertEquals(inr("4926.00"), s.perDayAverage)
        assertEquals(inr("600.00"), s.fxFeesPaid)
        assertEquals(inr("6050.00"), s.cash)
        assertEquals(inr("18580.00"), s.card)
        assertEquals(inr("10930.00"), s.byCategory["food"])
        assertEquals(inr("12200.00"), s.byCategory["hotel"])
        assertTrue(s.unconverted.isEmpty())
    }

    @Test fun `realised gain loss on cross currency own transfer`() {
        val day = LocalDate.of(2026, 3, 10)
        val store = InMemoryRateStore().apply { put(FxRate(day, USD, INR, BigDecimal("92.0"), "OXR")) }
        val calc = TransferFxCalculator(store, INR)
        // USD 1000 acquired at 85 → moved into INR account receiving 91,500
        val r = calc.realised(usd("1000.00"), inr("91500.00"), day, fromCostRate = BigDecimal("85"))
        assertEquals(inr("85000.00"), r.costHome)
        assertEquals(inr("6500.00"), r.realisedGain)
        assertEquals(BigDecimal("0.54"), r.spreadPct)
        // without cost basis: cost at market -> gain is the negative spread
        assertEquals(inr("-500.00"), calc.realised(usd("1000.00"), inr("91500.00"), day).realisedGain)
        // INR -> USD: cost is INR debited, proceeds valued at market
        assertEquals(inr("-200.00"), calc.realised(inr("92200.00"), usd("1000.00"), day).realisedGain)
    }
}
