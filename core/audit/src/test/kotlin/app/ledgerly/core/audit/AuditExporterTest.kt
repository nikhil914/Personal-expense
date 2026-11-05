package app.ledgerly.core.audit

import app.ledgerly.core.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditExporterTest {
    @Test fun csvEscaping() {
        assertEquals("plain", AuditExporter.csvCell("plain"))
        assertEquals("\"a,b\"", AuditExporter.csvCell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", AuditExporter.csvCell("say \"hi\""))
        assertEquals("\"line1\nline2\"", AuditExporter.csvCell("line1\nline2"))
        assertEquals("", AuditExporter.csvCell(null))
        assertEquals("'=HYPERLINK(1)", AuditExporter.csvCell("=HYPERLINK(1)", protectFormula = true))
        assertEquals("\" padded \"", AuditExporter.csvCell(" padded "))
    }

    @Test fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001\\u2028\"", AuditExporter.str("a\"b\\c\n\t\u0001 "))
        assertEquals("null", AuditExporter.str(null))
        assertEquals("\"₹ ok\"", AuditExporter.str("₹ ok"))
    }

    @Test fun transactionsCsvHasHeaderAndEscapedRow() {
        val t = txn("t1", "1240.5", merchant = "Joe's, \"Best\" Cafe").copy(note = "-refund?")
        val csv = AuditExporter().transactionsCsv(listOf(t))
        val lines = csv.split("\r\n")
        assertTrue(lines[0].startsWith("id,account_id,type,status,occurred_at,original_amount"))
        assertTrue(lines[1].startsWith("t1,acc1,EXPENSE,POSTED,"))
        assertTrue(",1240.50,INR," in lines[1])
        assertTrue("\"Joe's, \"\"Best\"\" Cafe\"" in lines[1])
        assertTrue(",'-refund?," in lines[1])
        assertEquals("", lines.last())
    }

    @Test fun historyCsvOneRowPerFieldChange() {
        val f = Fixture()
        f.ledger.create(txn("t1", category = "food"))
        f.ledger.update(f.ledger.get("t1")!!.copy(categoryId = "groceries", note = "x"))
        f.ledger.lockPeriod(java.time.YearMonth.of(2026, 11))
        val csv = AuditExporter().historyCsv(f.log.history("t1"))
        val rows = csv.trimEnd().split("\r\n")
        val createdFields = TransactionDiffer.diff(null, txn("t1")).size
        assertEquals(1 + createdFields + 2 + 1, rows.size) // header + created fields + 2 changes + LOCKED row
        assertTrue(rows.any { it.contains(",CATEGORISED,") || it.contains(",UPDATED,categoryId,food,groceries,") })
    }

    @Test fun jsonExportIncludesHistoryAndOrphans() {
        val f = Fixture()
        f.ledger.create(txn("t1"))
        f.ledger.create(txn("t2"))
        f.ledger.merge("t1", listOf("t2"))
        val json = AuditExporter().exportJson(f.ledger.active(), f.log)
        assertTrue(json.startsWith("{\"exportVersion\":1,\"transactions\":[{\"id\":\"t1\""))
        assertTrue("\"original\":{\"minor\":10000,\"currency\":\"INR\",\"amount\":\"100.00\"}" in json)
        assertTrue("\"history\":[{\"id\":\"e1\"" in json)
        assertTrue("\"orphanHistory\":[{" in json && "\"transactionId\":\"t2\"" in json)
        assertBalanced(json)
    }

    @Test fun exportCsvBundlesBoth() {
        val f = Fixture()
        f.ledger.create(txn("t1"))
        val out = AuditExporter().exportCsv(f.ledger.active(), f.log)
        assertEquals(2, out.transactionsCsv.trimEnd().split("\r\n").size)
        assertTrue(out.historyCsv.contains(",CREATED,"))
    }

    @Test fun moneyFormatHuman() {
        assertEquals("₹1,240", MoneyFormat.human(Money.of("1240", "INR")))
        assertEquals("₹12,34,567.50", MoneyFormat.human(Money.of("1234567.5", "INR")))
        assertEquals("$1,234,567.05", MoneyFormat.human(Money.of("1234567.05", "USD")))
        assertEquals("-₹50", MoneyFormat.human(Money.of("-50", "INR")))
        assertEquals("AED 12.30", MoneyFormat.human(Money.of("12.3", "AED")))
        assertEquals("¥1,500", MoneyFormat.human(Money.of("1500", "JPY")))
    }

    private fun assertBalanced(json: String) {
        var depth = 0; var inStr = false; var esc = false
        for (ch in json) {
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) { '"' -> inStr = true; '{', '[' -> depth++; '}', ']' -> depth-- }
            assertTrue(depth >= 0)
        }
        assertEquals(0, depth); assertTrue(!inStr)
    }
}
