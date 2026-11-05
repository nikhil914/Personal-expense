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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val Z: ZoneId = ZoneOffset.UTC
internal fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC)
internal fun inr(s: String) = Money.of(s, INR)
internal fun usd(s: String) = Money.of(s, USD)

class EnricherAndFeesTest {
    private val day = LocalDate.of(2026, 3, 10)
    private val store = InMemoryRateStore().apply {
        put(FxRate(day, USD, INR, BigDecimal("92.0"), "OXR"))
        put(FxRate(day, EUR, USD, BigDecimal("1.08"), "ECB"))
        put(FxRate(day, USD, JPY, BigDecimal("150"), "OXR"))
    }
    private val enricher = MultiCurrencyEnricher(store)

    @Test fun `USD 45 on INR card at 4212_30 vs market 92 exposes fee`() {
        val r = enricher.enrich(EnrichmentInput(usd("45.00"), inr("4212.30"), INR, INR, day))
        assertEquals(inr("4212.30"), r.account)
        assertEquals(inr("4212.30"), r.base)
        assertEquals(BigDecimal("93.606667"), r.fxRateApplied)
        assertEquals(0, BigDecimal("92.0").compareTo(r.marketRate))
        assertEquals(inr("72.30"), r.fxFee)
        assertEquals(BigDecimal("1.75"), r.fxFeePct)
        assertFalse(r.accountEstimated)
        assertEquals("Your bank charged ₹72.30 (1.7%) above the market rate", FxFeeExposer(Z).message(r.fxFee!!, inr("4140.00")))
    }

    @Test fun `same currency has no fx fields`() {
        val r = enricher.enrich(EnrichmentInput(inr("500.00"), null, INR, INR, day))
        assertEquals(inr("500.00"), r.account)
        assertNull(r.fxRateApplied); assertNull(r.fxFee); assertNull(r.marketRate)
    }

    @Test fun `missing bank amount estimates account at market without fee`() {
        val r = enricher.enrich(EnrichmentInput(usd("10.00"), null, INR, INR, day))
        assertEquals(inr("920.00"), r.account)
        assertTrue(r.accountEstimated)
        assertNull(r.fxFee)
    }

    @Test fun `base currency different from account converts account amount`() {
        // EUR spend on a USD account, reporting in INR
        val r = enricher.enrich(EnrichmentInput(Money.of("100.00", EUR), usd("110.00"), USD, INR, day))
        assertEquals(usd("110.00"), r.account)
        assertEquals(usd("2.00"), r.fxFee) // 100*1.08 = 108
        assertEquals(inr("10120.00"), r.base)
    }

    @Test fun `JPY original rounds account to INR correctly`() {
        val r = enricher.enrich(EnrichmentInput(Money(1234, JPY), null, INR, INR, day))
        // 1234 / 150 * 92 = 756.8533 -> 756.85
        assertEquals(inr("756.85"), r.account)
    }

    @Test fun `enrich transaction copies fields`() {
        val t = Transaction("t1", "card", TxnType.EXPENSE, occurredAt = at(2026, 3, 10), original = usd("45.00"))
        val e = enricher.enrich(t, inr("4212.30"), INR, INR, Z)
        assertEquals(inr("72.30"), e.fxFee)
        assertEquals(day, e.rateDate)
        assertEquals("OXR", e.rateSource)
    }

    private fun foreign(id: String, acct: String, o: Money, a: Money, fee: Money?, market: String?, t: java.time.Instant, last4: String? = "1234") =
        Transaction(id, acct, TxnType.EXPENSE, occurredAt = t, original = o, account = a, base = a,
            fxFee = fee, marketRate = market?.let(::BigDecimal), merchantRaw = "SHOP $id", accountLast4 = last4)

    @Test fun `per transaction message rounds large fees to whole rupees`() {
        val t = foreign("a", "hdfc", usd("45.65"), inr("4347.00"), inr("147.00"), "92.0", at(2026, 3, 10))
        // market value 45.65*92 = 4199.80; 147/4199.8 = 3.5%
        assertEquals("Your bank charged ₹147 (3.5%) above the market rate", FxFeeExposer(Z).message(t))
    }

    @Test fun `no message for negligible fee`() {
        val t = foreign("a", "hdfc", usd("45.00"), inr("4140.50"), inr("0.50"), "92.0", at(2026, 3, 10))
        assertNull(FxFeeExposer(Z).message(t))
    }

    @Test fun `separate markup line within 3 days attributed to original`() {
        val orig = foreign("o1", "hdfc", usd("45.00"), inr("4140.00"), inr("0.00"), "92.0", at(2026, 3, 10, 9))
        val other = foreign("o2", "icici", usd("20.00"), inr("1840.00"), inr("0.00"), "92.0", at(2026, 3, 10, 10), last4 = "9999")
        val markup = Transaction("m1", "hdfc", TxnType.EXPENSE, occurredAt = at(2026, 3, 12, 8), original = inr("144.90"),
            merchantRaw = "FOREIGN CURRENCY MARKUP FEE", accountLast4 = "1234")
        val late = Transaction("m2", "hdfc", TxnType.EXPENSE, occurredAt = at(2026, 3, 20), original = inr("50.00"),
            merchantRaw = "FX MARKUP", accountLast4 = "1234")
        val ex = FxFeeExposer(Z)
        val attrs = ex.attributeMarkupCharges(listOf(orig, other, markup, late))
        val a = attrs.single()
        assertEquals("m1", a.markupTxnId); assertEquals("o1", a.originalTxnId)
        assertEquals(BigDecimal("3.50"), a.pctOfOriginal)
        assertEquals("Your bank charged ₹145 (3.5%) above the market rate", ex.message(orig, a.fee))
    }

    @Test fun `markup line keyword detection`() {
        val ex = FxFeeExposer(Z)
        fun t(m: String) = Transaction("x", "a", TxnType.EXPENSE, occurredAt = at(2026, 1, 1), original = inr("1"), merchantRaw = m)
        assertTrue(ex.isMarkupLine(t("DCC MARKUP")))
        assertTrue(ex.isMarkupLine(t("Cross Currency Markup")))
        assertTrue(ex.isMarkupLine(t("INTL TXN FEE")))
        assertFalse(ex.isMarkupLine(t("STARBUCKS")))
    }

    @Test fun `yearly report per card with alternative card savings`() {
        val txns = listOf(
            foreign("a", "hdfc", usd("100.00"), inr("9522.00"), inr("322.00"), "92.0", at(2026, 2, 1)),
            foreign("b", "hdfc", usd("200.00"), inr("19044.00"), inr("644.00"), "92.0", at(2026, 6, 1)),
            foreign("c", "hdfc", usd("50.00"), inr("4761.00"), inr("161.00"), "92.0", at(2025, 6, 1)), // other year
            foreign("d", "niyo", usd("100.00"), inr("9200.00"), inr("0.00"), "92.0", at(2026, 4, 1), last4 = "5555"),
        )
        val reports = FxFeeExposer(Z).yearlyReport(2026, txns, listOf(
            CardFxProfile("Niyo Global", BigDecimal.ZERO),
            CardFxProfile("Scapia", BigDecimal("0"), inr("10.00")),
            CardFxProfile("Expensive", BigDecimal("5")),
        ))
        val hdfc = reports.first { it.accountId == "hdfc" }
        assertEquals(2, hdfc.foreignTxnCount)
        assertEquals(inr("27600.00"), hdfc.foreignSpendAtMarket)
        assertEquals(inr("966.00"), hdfc.totalFees)
        assertEquals(BigDecimal("3.50"), hdfc.effectivePct)
        assertEquals(listOf("Niyo Global", "Scapia"), hdfc.alternatives.map { it.profile.name })
        assertEquals("Niyo Global would have saved ₹966 in 2026", hdfc.alternatives.first().message)
        assertEquals(inr("946.00"), hdfc.alternatives[1].savings)
        val niyo = reports.first { it.accountId == "niyo" }
        assertEquals(inr("0.00"), niyo.totalFees)
        assertTrue(niyo.alternatives.isEmpty())
    }

    @Test fun `DCC flagged when charged in home currency during trip`() {
        val trip = Trip("trip1", "Japan", JPY, "JP", LocalDate.of(2026, 4, 1), null)
        val dcc = DccDetector(INR, Z) { if (it.id == "x") "JP" else null }
        val t = Transaction("x", "hdfc", TxnType.EXPENSE, occurredAt = at(2026, 4, 3), original = inr("5400.00"), merchant = "Uniqlo Ginza")
        val alert = assertNotNull(dcc.detect(t, listOf(trip)))
        assertEquals(0.9, alert.confidence)
        assertTrue(alert.message.contains("Dynamic Currency Conversion"))
        assertTrue(alert.message.contains("pay in JPY"))
        assertTrue(alert.message.contains("Japan"))
        // local currency charge is fine; outside trip dates is fine
        assertNull(dcc.detect(t.copy(original = Money(3000, JPY)), listOf(trip)))
        assertNull(dcc.detect(t.copy(occurredAt = at(2026, 3, 30)), listOf(trip)))
    }

    @Test fun `DCC skipped when merchant is domestic`() {
        val trip = Trip("trip1", "Japan", JPY, "JP", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 10))
        val dcc = DccDetector(INR, Z) { "IN" }
        val t = Transaction("y", "hdfc", TxnType.EXPENSE, occurredAt = at(2026, 4, 3), original = inr("199.00"), merchant = "Netflix")
        assertNull(dcc.detect(t, listOf(trip)))
        val unknown = DccDetector(INR, Z)
        assertEquals(0.6, unknown.detect(t, listOf(trip))!!.confidence)
    }

    @Test fun `format helpers`() {
        assertEquals("₹27,600", FxFormat.short(inr("27600.00")))
        assertEquals("€12.50", FxFormat.short(Money.of("12.50", EUR)))
        assertEquals("KWD 1.235", FxFormat.full(Money.of("1.235", CurrencyCode.KWD)))
        assertEquals("¥1,500", FxFormat.full(Money(1500, JPY)))
        assertEquals("2%", FxFormat.pct(BigDecimal("2.00")))
    }
}
