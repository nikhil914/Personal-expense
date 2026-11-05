package app.ledgerly.core.budget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnvelopeBudgetTest {
    private val agg = aggregator()
    private val nov = BudgetPeriod(d(2026, 11, 1), d(2026, 12, 1))

    private fun funded() = EnvelopeBudget.of(INR,
        Envelope("rent", "Rent", setOf("rent")),
        Envelope("food", "Food", setOf("food"), target = inr("12000")),
        Envelope("transport", "Transport", setOf("transport")),
    ).addIncome(inr("50000")).assign("rent", inr("20000")).assign("food", inr("10000")).assign("transport", inr("3000"))

    private val spendTxns = listOf(
        spend("6000", "groceries", d(2026, 11, 3)), spend("4500", "dining", d(2026, 11, 4)),
        spend("1000", "transport", d(2026, 11, 4)), spend("700", "shopping", d(2026, 11, 5)),
    )

    @Test fun assignReducesToBeAssigned() {
        val b = funded()
        assertEquals(inr("17000"), b.toBeAssigned)
        assertFailsWith<IllegalArgumentException> { b.assign("food", inr("17000.01")) }
        assertEquals(inr("16000"), b.assign("rent", inr("1000")).toBeAssigned)
        assertEquals(inr("18000"), b.assign("rent", inr("-1000")).toBeAssigned)
        assertEquals(83.3, b.status("food").targetFundedPercent)
    }

    @Test fun moveMoneyBetweenEnvelopes() {
        val b = funded().move("food", "transport", inr("1000"))
        assertEquals(inr("9000"), b.available("food"))
        assertEquals(inr("4000"), b.available("transport"))
        assertEquals(inr("17000"), b.toBeAssigned) // moves don't touch TBA
        assertEquals(EnvelopeMove("food", "transport", inr("1000")), b.moves.last())
        assertFailsWith<IllegalArgumentException> { b.move("transport", "rent", inr("4000.01")) }
        assertFailsWith<IllegalArgumentException> { b.move("rent", "rent", inr("1")) }
    }

    @Test fun activityMapsChildCategoriesAndDetectsOverspend() {
        val b = funded().move("food", "transport", inr("1000")).withActivity(agg.spendByCategory(spendTxns, nov), agg::parentOf)
        assertEquals(inr("-1500"), b.available("food"))
        assertEquals(inr("3000"), b.available("transport"))
        assertEquals(inr("700"), b.unmappedActivity)
        assertEquals(listOf("food"), b.overspent().map { it.envelope.id })
    }

    @Test fun overspendCoveredFromOtherEnvelope() {
        val b = funded().move("food", "transport", inr("1000"))
            .withActivity(agg.spendByCategory(spendTxns, nov), agg::parentOf)
            .coverOverspend("food", "rent")
        assertTrue(b.available("food").isZero)
        assertEquals(inr("18500"), b.available("rent"))
        assertTrue(b.overspent().isEmpty())
        assertFailsWith<IllegalArgumentException> { b.coverOverspend("food", "rent") }
    }

    @Test fun rolloverCarriesPositivesAndChargesDeficitsToTba() {
        val b = funded().move("food", "transport", inr("1000")).withActivity(agg.spendByCategory(spendTxns, nov), agg::parentOf)
        val next = b.rollover()
        assertEquals(inr("20000"), next.available("rent"))
        assertEquals(inr("3000"), next.available("transport"))
        assertTrue(next.available("food").isZero)
        // TBA 17000 − food overspend 1500 − unmapped 700
        assertEquals(inr("14800"), next.toBeAssigned)
        val dec = next.addIncome(inr("50000"))
        assertEquals(inr("64800"), dec.toBeAssigned)
    }

    @Test fun categoryInTwoEnvelopesRejected() {
        assertFailsWith<IllegalArgumentException> {
            EnvelopeBudget.of(INR, Envelope("a", "A", setOf("food")), Envelope("b", "B", setOf("food")))
        }
    }
}
