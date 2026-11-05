package app.ledgerly.core.insights

import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurringDetectorTest {
    private val detector = RecurringDetector(FIXED)

    @Test
    fun `monthly with jittered dates is detected with next date and amount`() {
        // May16, Jun13, Jul17, Aug14, Sep15, Oct15
        val txns = monthly("Netflix", inr(649), "2026-05", 15, listOf(1, -2, 2, -1, 0, 0))
        val s = detector.detect(txns).single()
        assertEquals(Periodicity.MONTHLY, s.periodicity)
        assertTrue(s.amountStable)
        assertEquals(inr(649), s.nextExpectedAmount)
        assertEquals(d("2026-11-15"), s.nextExpectedDate)
        assertEquals(Flow.OUT, s.flow)
        assertTrue(s.active)
        assertTrue(s.confidence > 0.8, "confidence ${s.confidence}")
    }

    @Test
    fun `weekly with one-day jitter`() {
        val jit = listOf(0, 1, 0, -1, 0, 1, 1, 0)
        val start = d("2026-09-07")
        val txns = jit.mapIndexed { i, j -> txn(start.plusWeeks(i.toLong()).plusDays(j.toLong()), inr(300), "Milk Basket") }
        val s = detector.detect(txns).single()
        assertEquals(Periodicity.WEEKLY, s.periodicity)
        assertEquals(txns.last().occurredAt.atZone(UTC).toLocalDate().plusDays(7), s.nextExpectedDate)
    }

    @Test
    fun `biweekly salary detected as income`() {
        val start = d("2026-07-03")
        val jit = listOf(0, 1, -1, 0, 2, 0, -1, 0)
        val txns = jit.mapIndexed { i, j -> txn(start.plusWeeks(2L * i).plusDays(j.toLong()), inr(45000), "ACME Payroll", TxnType.INCOME) }
        val s = detector.detect(txns).single()
        assertEquals(Periodicity.BIWEEKLY, s.periodicity)
        assertEquals(Flow.IN, s.flow)
    }

    @Test
    fun `quarterly and yearly need only two or three charges`() {
        val q = listOf(txn("2026-02-10", 1500, "Society Maintenance"), txn("2026-05-12", 1500, "Society Maintenance"), txn("2026-08-09", 1500, "Society Maintenance"))
        assertEquals(Periodicity.QUARTERLY, detector.detect(q).single().periodicity)
        val y = listOf(txn("2024-12-01", 1499, "Amazon Prime"), txn("2025-12-03", 1499, "Amazon Prime"))
        val s = detector.detect(y).single()
        assertEquals(Periodicity.YEARLY, s.periodicity)
        assertEquals(d("2026-12-03"), s.nextExpectedDate)
    }

    @Test
    fun `irregular purchases are not recurring`() {
        val txns = listOf("2026-01-03", "2026-02-18", "2026-03-09", "2026-04-27", "2026-05-12", "2026-06-02", "2026-07-22")
            .map { txn(it, 3000, "BigBasket") }
        assertTrue(detector.detect(txns).isEmpty())
    }

    @Test
    fun `two monthly charges are not enough`() {
        assertTrue(detector.detect(monthly("Gym", inr(1500), "2026-09", 5, listOf(0, 0))).isEmpty())
    }

    @Test
    fun `skipped month still counts as monthly`() {
        val txns = listOf("2026-04-05", "2026-05-05", "2026-07-06", "2026-08-05", "2026-09-04", "2026-10-05").map { txn(it, 999, "Cult Fit") }
        assertEquals(Periodicity.MONTHLY, detector.detect(txns).single().periodicity)
    }

    @Test
    fun `variable utility bill is recurring but not stable, next amount is recent average`() {
        val amounts = listOf(1800, 2400, 2100, 1600, 2600, 2000)
        val txns = amounts.mapIndexed { i, a -> txn(d("2026-05-20").plusMonths(i.toLong()), inr(a), "BESCOM Electricity") }
        val s = detector.detect(txns).single()
        assertFalse(s.amountStable)
        assertEquals(inr((1600 + 2600 + 2000) / 3), s.nextExpectedAmount.let { inr(it.minor.toInt() / 100) })
    }

    @Test
    fun `noisy merchant strings normalise into one series`() {
        val txns = listOf(
            txn(d("2026-07-15"), inr(649), "NETFLIX.COM"), txn(d("2026-08-15"), inr(649), "Netflix"),
            txn(d("2026-09-15"), inr(649), "netflix.com"), txn(d("2026-10-15"), inr(649), "NETFLIX"),
        )
        assertEquals(4, detector.detect(txns).single().transactions.size)
    }

    @Test
    fun `two plans at the same merchant split into two series`() {
        val a = monthly("Apple", inr(75), "2026-05", 2, listOf(0, 0, 0, 0, 0, 0))
        val b = monthly("Apple", inr(299), "2026-05", 20, listOf(0, 0, 0, 0, 0, 0))
        val s = detector.detect(a + b)
        assertEquals(2, s.size)
        assertEquals(setOf(inr(75), inr(299)), s.map { it.lastAmount }.toSet())
    }

    @Test
    fun `lapsed series is inactive`() {
        val txns = monthly("Old Gym", inr(1200), "2026-01", 10, listOf(0, 0, 0, 0))
        val s = detector.detect(txns).single()
        assertFalse(s.active)
    }

    @Test
    fun `deleted and failed transactions are ignored`() {
        val txns = monthly("Hulu", inr(500), "2026-07", 1, listOf(0, 0, 0, 0)).mapIndexed { i, t ->
            if (i == 3) t.copy(status = TxnStatus.FAILED) else if (i == 2) t.copy(deletedAt = t.occurredAt) else t
        }
        assertTrue(detector.detect(txns).isEmpty())
    }

    @Test
    fun `occurrencesUntil projects forward`() {
        val s = detector.detect(monthly("Netflix", inr(649), "2026-05", 15, listOf(0, 0, 0, 0, 0, 0))).single()
        assertEquals(listOf(d("2026-11-15"), d("2026-12-15"), d("2027-01-15")), s.occurrencesUntil(d("2027-01-31")))
    }
}

class KnownServicesTest {
    @Test
    fun `catalogue has 40+ services with https cancel urls`() {
        assertTrue(KnownServices.all.size >= 40)
        assertTrue(KnownServices.all.all { it.cancelUrl.startsWith("https://") })
        assertEquals(KnownServices.all.size, KnownServices.all.map { it.id }.toSet().size)
    }

    @Test
    fun `matches noisy merchant strings`() {
        assertEquals("netflix", KnownServices.match("NETFLIX.COM")?.id)
        assertEquals("jiohotstar", KnownServices.match("JioHotstar")?.id)
        assertEquals("jiohotstar", KnownServices.match("Disney+ Hotstar")?.id)
        assertEquals("spotify", KnownServices.match("Spotify AB Stockholm")?.id)
        assertEquals("chatgpt", KnownServices.match("OPENAI *CHATGPT SUBSCR")?.id)
        assertEquals("claude", KnownServices.match("CLAUDE.AI SUBSCRIPTION")?.id)
        assertEquals("youtube_premium", KnownServices.match("Google YouTube Premium")?.id)
        assertEquals("google_one", KnownServices.match("Google One")?.id)
        assertEquals("microsoft365", KnownServices.match("Microsoft 365 Family")?.id)
        assertEquals("prime", KnownServices.match("Amazon Prime")?.id)
        assertEquals("cultfit", KnownServices.match("CULTFIT")?.id)
        assertEquals("swiggy_one", KnownServices.match("Swiggy One")?.id)
    }

    @Test
    fun `ordinary merchants do not match`() {
        assertNull(KnownServices.match("Swiggy"))
        assertNull(KnownServices.match("Uber"))
        assertNull(KnownServices.match("BigBasket"))
        assertNull(KnownServices.match(null))
    }
}

class SubscriptionManagerTest {
    private val mgr = SubscriptionManager(FIXED)

    @Test
    fun `lists subscriptions with annual cost and cancel url`() {
        val txns = monthly("NETFLIX.COM", inr(649), "2026-05", 15, listOf(0, 1, -1, 0, 2, 0))
        val sub = mgr.subscriptions(txns).single()
        assertEquals("Netflix", sub.name)
        assertEquals(inr(649 * 12), sub.annualCost)
        assertEquals("https://www.netflix.com/cancelplan", sub.cancelUrl)
        assertEquals(inr(649), mgr.totalMonthlyCost(listOf(sub), inr(0).currency))
    }

    @Test
    fun `unknown stable monthly charge qualifies but weekly groceries do not`() {
        val gym = monthly("Iron Paradise Gym", inr(1500), "2026-05", 3, listOf(0, 0, 1, 0, -1, 0))
        val weekly = (0 until 8).map { txn(d("2026-09-05").plusWeeks(it.toLong()), inr(800), "Sunday Market") }
        val subs = mgr.subscriptions(gym + weekly)
        assertEquals(listOf("Iron Paradise Gym"), subs.map { it.name })
        assertNull(subs.single().cancelUrl)
    }

    @Test
    fun `price increase alert`() {
        val txns = monthly("Spotify", inr(119), "2026-06", 8, listOf(0, 0, 0, 0)) + txn(d("2026-10-08"), inr(139), "Spotify")
        val alert = mgr.alerts(txns).filterIsInstance<SubscriptionAlert.PriceIncrease>().single()
        assertEquals(inr(119), alert.oldAmount)
        assertEquals(inr(139), alert.newAmount)
        assertTrue(alert.message.contains("17%"), alert.message)
    }

    @Test
    fun `no price alert below threshold`() {
        val txns = monthly("Spotify", inr(119), "2026-06", 8, listOf(0, 0, 0, 0)) + txn(d("2026-10-08"), inr(122), "Spotify")
        assertTrue(mgr.alerts(txns).none { it is SubscriptionAlert.PriceIncrease })
    }

    @Test
    fun `renewing in 3 days alert`() {
        val txns = monthly("Netflix", inr(649), "2026-05", 8, listOf(0, 0, 0, 0, 0, 0)) // next Nov 8, today Nov 5
        val a = mgr.alerts(txns).filterIsInstance<SubscriptionAlert.RenewingSoon>().single()
        assertEquals(3, a.daysUntil)
        assertTrue(a.message.contains("in 3 days"), a.message)
        // 4 days out: no alert
        val later = monthly("Netflix", inr(649), "2026-05", 9, listOf(0, 0, 0, 0, 0, 0))
        assertTrue(mgr.alerts(later).none { it is SubscriptionAlert.RenewingSoon })
    }

    @Test
    fun `free trial ending heuristic`() {
        val txns = listOf(txn(d("2026-10-09"), inr("2"), "Spotify Premium"))
        val a = mgr.alerts(txns).filterIsInstance<SubscriptionAlert.TrialEnding>().single()
        assertEquals(d("2026-11-08"), a.expectedEnd) // Spotify 30-day trial
        assertEquals(3, a.daysLeft)
    }

    @Test
    fun `no trial alert once a full charge followed`() {
        val txns = listOf(txn(d("2026-10-09"), inr("2"), "Spotify"), txn(d("2026-11-04"), inr(119), "Spotify"))
        assertTrue(mgr.alerts(txns).none { it is SubscriptionAlert.TrialEnding })
    }

    @Test
    fun `unknown merchant small charge is not a trial unless tagged`() {
        val plain = listOf(txn(d("2026-10-30"), inr("1"), "Random Store"))
        assertTrue(mgr.alerts(plain).none { it is SubscriptionAlert.TrialEnding })
        val tagged = listOf(txn(d("2026-10-30"), inr("0"), "Some App", tags = setOf("trial")))
        val a = mgr.alerts(tagged).filterIsInstance<SubscriptionAlert.TrialEnding>().single()
        assertEquals(d("2026-11-06"), a.expectedEnd)
    }

    @Test
    fun `unused subscription nudge only with usage data`() {
        val txns = monthly("Netflix", inr(649), "2026-05", 20, listOf(0, 0, 0, 0, 0, 0))
        assertTrue(mgr.alerts(txns).none { it is SubscriptionAlert.Unused })
        val stale = mgr.alerts(txns, mapOf("netflix" to TODAY.minusDays(60))).filterIsInstance<SubscriptionAlert.Unused>().single()
        assertEquals(60, stale.daysUnused)
        assertTrue(mgr.alerts(txns, mapOf("netflix" to TODAY.minusDays(3))).none { it is SubscriptionAlert.Unused })
        val disabled = SubscriptionManager(FIXED, SubscriptionConfig(unusedAfterDays = null))
        assertTrue(disabled.alerts(txns, mapOf("netflix" to TODAY.minusDays(90))).none { it is SubscriptionAlert.Unused })
    }

    @Test
    fun `lapsed subscription is not listed`() {
        val txns = monthly("Netflix", inr(649), "2026-01", 15, listOf(0, 0, 0, 0))
        assertTrue(mgr.subscriptions(txns).isEmpty())
        assertNotNull(RecurringDetector(FIXED).detect(txns).singleOrNull())
    }

    @Test
    fun `subscriptions sorted by annual cost and summed`() {
        val txns = monthly("Netflix", inr(649), "2026-05", 15, List(6) { 0 }) +
            monthly("Spotify", inr(119), "2026-05", 10, List(6) { 0 }) +
            listOf(txn("2025-11-20", 1499, "Amazon Prime"), txn("2024-11-21", 1499, "Amazon Prime"))
        val subs = mgr.subscriptions(txns)
        assertEquals(listOf("Netflix", "Amazon Prime", "Spotify"), subs.map { it.name })
        assertEquals(inr(649 * 12 + 119 * 12 + 1499), mgr.totalAnnualCost(subs, inr(0).currency))
        assertEquals(LocalDate.of(2026, 11, 20), subs.first { it.name == "Amazon Prime" }.nextRenewal)
    }
}
