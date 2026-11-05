package app.ledgerly.core.budget

import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Split
import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpendAggregatorTest {
    private val agg = aggregator()
    private val nov = BudgetPeriod(d(2026, 11, 1), d(2026, 12, 1))

    @Test fun splitsCountPerCategory() {
        val t = spend("1000", "groceries", d(2026, 11, 2)).copy(splits = listOf(Split("groceries", inr("700")), Split("shopping", inr("300"))))
        val m = agg.spendByCategory(listOf(t), nov)
        assertEquals(inr("700"), m["groceries"])
        assertEquals(inr("300"), m["shopping"])
    }

    @Test fun transfersAdjustmentsIncomeDeletedAndFailedExcluded() {
        val txns = listOf(
            spend("100", "food", d(2026, 11, 2)),
            spend("5000", null, d(2026, 11, 2), TxnType.TRANSFER),
            spend("300", "food", d(2026, 11, 2)).copy(transferGroupId = "g1"),
            spend("50", "food", d(2026, 11, 2), TxnType.ADJUSTMENT),
            spend("90000", "salary", d(2026, 11, 2), TxnType.INCOME),
            spend("70", "food", d(2026, 11, 2)).copy(deletedAt = instant(d(2026, 11, 3))),
            spend("80", "food", d(2026, 11, 2)).copy(status = TxnStatus.FAILED),
        )
        assertEquals(inr("100"), agg.totalSpend(txns, nov))
        assertEquals(inr("90000"), agg.income(txns, nov))
    }

    @Test fun refundReducesSpendInOriginalCategory() {
        val orig = spend("2000", "shopping", d(2026, 11, 2), id = "o1")
        val refund = spend("500", null, d(2026, 11, 6), TxnType.REFUND).copy(refundOfId = "o1")
        assertEquals(inr("1500"), agg.spendByCategory(listOf(orig, refund), nov)["shopping"])
    }

    @Test fun usesBaseAmountForForeignTransactions() {
        val t = spend("0", "dining", d(2026, 11, 3)).copy(original = Money.of("45", "USD"), account = inr("3912.30"), base = inr("3912.30"))
        assertEquals(inr("3912.30"), agg.spendByCategory(listOf(t), nov)["dining"])
    }

    @Test fun foreignCurrencySplitsAreScaledToBase() {
        val t = spend("0", "dining", d(2026, 11, 3)).copy(
            original = Money.of("30", "USD"), base = inr("2500"),
            splits = listOf(Split("dining", Money.of("20", "USD")), Split("transport", Money.of("10", "USD"))),
        )
        val m = agg.spendByCategory(listOf(t), nov)
        assertEquals(inr("1666.67"), m["dining"])
        assertEquals(inr("833.33"), m["transport"])
    }

    @Test fun parentRollsUpChildrenAndPeriodBoundaryUsesZone() {
        val txns = listOf(
            spend("100", "groceries", d(2026, 11, 2)),
            spend("200", "dining", d(2026, 11, 2)),
            spend("50", "food", d(2026, 11, 2)),
            // 30 Nov 23:30 IST is still November locally even though it's Nov 30 18:00 UTC
            spend("10", "dining", d(2026, 11, 30)).copy(occurredAt = d(2026, 11, 30).atTime(23, 30).atZone(IST).toInstant()),
            spend("999", "dining", d(2026, 12, 1)),
        )
        val m = agg.spendByCategory(txns, nov)
        assertEquals(inr("360"), agg.rolledUp(m, "food"))
        assertEquals(inr("50"), agg.rolledUp(m, "food", includeChildren = false))
        assertEquals(setOf("food", "groceries", "dining"), agg.withDescendants("food"))
        assertNull(agg.parentOf("food"))
    }
}
