package app.ledgerly.core.audit

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.Split
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionDifferTest {
    @Test fun identicalTransactionsHaveNoDiff() {
        assertTrue(TransactionDiffer.diff(txn("a"), txn("a")).isEmpty())
    }

    @Test fun creationListsEveryPresentField() {
        val d = TransactionDiffer.diff(null, txn("a"))
        val fields = d.map { it.field }
        assertTrue("original" in fields && "categoryId" in fields && "merchant" in fields)
        assertTrue(d.all { it.old == null })
        assertTrue("note" !in fields) // null on both sides
    }

    @Test fun moneyIsRenderedStably() {
        val d = TransactionDiffer.diff(txn("a", "100"), txn("a", "1240.5"))
        val c = d.first { it.field == "original" }
        assertEquals("100.00 INR", c.old)
        assertEquals("1240.50 INR", c.new)
    }

    @Test fun decimalScaleDifferencesAreNotChanges() {
        val a = txn("a").copy(fxRateApplied = BigDecimal("83.10"))
        val b = txn("a").copy(fxRateApplied = BigDecimal("83.1"))
        assertTrue(TransactionDiffer.diff(a, b).isEmpty())
    }

    @Test fun splitsTagsAndChannelsAreDiffed() {
        val a = txn("a").copy(tags = setOf("b", "a"), sourceChannels = setOf(Channel.SMS))
        val b = a.copy(
            splits = listOf(Split("food", inr("60")), Split("home", inr("40"), "bulb")),
            tags = setOf("a", "c"),
            sourceChannels = setOf(Channel.SMS, Channel.NOTIFICATION),
        )
        val d = TransactionDiffer.diff(a, b).associateBy { it.field }
        assertEquals("food=60.00 INR; home=40.00 INR (bulb)", d["splits"]!!.new)
        assertEquals("a,b", d["tags"]!!.old)
        assertEquals("a,c", d["tags"]!!.new)
        assertEquals("NOTIFICATION,SMS", d["sourceChannels"]!!.new)
    }

    @Test fun applyFieldsRevertsOnlyDiffedFields() {
        val before = txn("a", category = "food")
        val after = before.copy(categoryId = "groceries")
        val diff = TransactionDiffer.diff(before, after)
        val concurrentlyEdited = after.copy(note = "keep me")
        val reverted = TransactionDiffer.applyFields(concurrentlyEdited, before, diff)
        assertEquals("food", reverted.categoryId)
        assertEquals("keep me", reverted.note)
    }
}
