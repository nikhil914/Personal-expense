package app.ledgerly.core.insights

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.Category
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import app.ledgerly.core.model.TxnType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

object AskFixture {
    val categories = listOf(
        Category("c_food", "Food & Dining"), Category("c_groc", "Groceries"), Category("c_trans", "Transport"),
        Category("c_shop", "Shopping"), Category("c_ent", "Entertainment"), Category("c_travel", "Travel"),
        Category("c_sal", "Salary", isIncome = true), Category("c_cafe", "Cafes", parentId = "c_food"),
    )
    val accounts = listOf(
        Account("a_hdfc", "HDFC Credit Card", AccountType.CREDIT_CARD, CurrencyCode.INR, "HDFC", setOf("1234")),
        Account("a_cash", "Cash", AccountType.CASH, CurrencyCode.INR),
        Account("a_icici", "ICICI Savings", AccountType.BANK, CurrencyCode.INR, "ICICI"),
    )
    val trips = listOf(Trip("t_lis", "Lisbon 2026", CurrencyCode.EUR, "PT", d("2026-09-10"), d("2026-09-17")))

    val txns: List<Transaction> = buildList {
        // Uber (transport)
        add(txn("2026-09-05", 200, "Uber", category = "c_trans"))
        add(txn("2026-10-03", 250, "Uber", category = "c_trans"))
        add(txn(d("2026-10-12"), inr(300), "Uber", category = "c_trans", tags = setOf("work")))
        add(txn("2026-10-25", 450, "Uber", category = "c_trans"))
        add(txn("2026-11-02", 180, "Uber", category = "c_trans"))
        // Swiggy / Zomato (food)
        listOf("2026-09-20" to 350, "2026-10-01" to 400, "2026-10-15" to 600, "2026-11-01" to 500, "2026-11-03" to 300)
            .forEach { (dt, a) -> add(txn(dt, a, "Swiggy", category = "c_food")) }
        add(txn("2026-10-20", 700, "Zomato", category = "c_food"))
        // Groceries: irregular dates, 3000 each Jan-Oct + 2200 yesterday
        listOf("2026-01-03", "2026-02-18", "2026-03-09", "2026-04-27", "2026-05-12", "2026-06-02", "2026-07-22", "2026-08-08", "2026-09-28", "2026-10-10")
            .forEach { add(txn(it, 3000, "BigBasket", category = "c_groc")) }
        add(txn("2026-11-04", 2200, "BigBasket", category = "c_groc"))
        // Subscriptions
        (5..10).forEach { m -> add(txn("2026-%02d-15".format(m), 649, "Netflix", category = "c_ent")) }
        (5..10).forEach { m -> add(txn("2026-%02d-08".format(m), 119, "Spotify", category = "c_ent")) }
        // Lisbon trip (EUR)
        add(txn(d("2026-09-11"), inr(2700), "Time Out Market", category = "c_food", trip = "t_lis", original = eur("30")))
        add(txn(d("2026-09-12"), inr(18000), "Hotel Avenida", category = "c_travel", trip = "t_lis", original = eur("200")))
        // Amazon on HDFC card
        add(txn(d("2026-10-28"), inr(5000), "Amazon", category = "c_shop", account = "a_hdfc"))
        add(txn(d("2026-11-04"), inr(1200), "Amazon", category = "c_shop", account = "a_hdfc"))
        // Salary
        add(txn(d("2026-10-01"), inr(100000), "ACME Corp", TxnType.INCOME, "c_sal", account = "a_icici"))
        add(txn(d("2026-11-01"), inr(100000), "ACME Corp", TxnType.INCOME, "c_sal", account = "a_icici"))
        // Today + cash
        add(txn(d("2026-11-05"), inr(350), "Starbucks", category = "c_cafe", hour = 9))
        add(txn(d("2026-11-03"), inr(50), "Chaayos", category = "c_food", account = "a_cash"))
    }

    val converter: MoneyConverter = { m, cur, _ -> Money(m.minor / 100, cur) } // 100 INR = 1 unit

    fun engine(llm: LlmQueryTranslator? = null) = AskEngine(FIXED, categories, accounts, trips, converter, llm)
}

class AskEngineTest {
    private val engine = AskFixture.engine()
    private fun ask(q: String) = engine.ask(q, AskFixture.txns)

    companion object {
        /** question -> expected headline value in INR (or count when the metric is COUNT). */
        @JvmStatic
        fun sums(): Stream<Arguments> = Stream.of(
            Arguments.of("How much did I spend on Uber in October?", 1000),
            Arguments.of("what did I spend on food last month", 1700),
            Arguments.of("How much did I spend on Uber in Oct 2026?", 1000),
            Arguments.of("uber spend in october", 1000),
            Arguments.of("how much did I spend yesterday", 3400),
            Arguments.of("spent today", 350),
            Arguments.of("Uber spend in the last 30 days", 930),
            Arguments.of("how much on uber between 1 Oct and 15 Oct", 550),
            Arguments.of("how much on uber from Oct 1 to Oct 15", 550),
            Arguments.of("Swiggy since October", 1800),
            Arguments.of("what did I spend in Q3 on transport", 200),
            Arguments.of("how much did I spend on groceries this year", 32200),
            Arguments.of("how much did I spend with HDFC card last month", 5000),
            Arguments.of("how much did I spend #work", 300),
            Arguments.of("expenses tagged work", 300),
            Arguments.of("how much did I spend in Portugal", 20700),
            Arguments.of("Netflix in 2026", 3894),
            Arguments.of("how much did I spend last week", 5500),
            Arguments.of("spending this week", 4280),
            Arguments.of("how much did I spend using my cash account", 50),
            Arguments.of("total food spend this month", 1200),
            Arguments.of("how much did I earn last month", 100000),
            Arguments.of("what was my salary in november", 100000),
            Arguments.of("how much did I spend on cafes", 350),
            Arguments.of("dining expenses in the past 2 months", 5950),
            Arguments.of("total spent on Amazon on card ending 1234", 6200),
            Arguments.of("How much did I spend on Lisbon trip", 20700),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sums")
    fun `sum questions`(question: String, expected: Int) {
        val a = ask(question)
        assertEquals(inr(expected), a.value, "${a.query} -> ${a.text}")
    }

    @Test
    fun `uber in october answer sentence`() {
        val a = ask("How much did I spend on Uber in October?")
        assertEquals(Metric.SUM, a.query!!.metric)
        assertEquals("Uber", a.query!!.merchant)
        assertEquals("You spent ₹1,000 on Uber in October 2026 across 3 transactions.", a.text)
        assertEquals(3, a.rows.size)
    }

    @Test
    fun `food last month resolves category`() {
        val a = ask("what did I spend on food last month")
        assertEquals("Food & Dining", a.query!!.category)
        assertEquals(d("2026-10-01"), a.query!!.range!!.start)
        assertEquals(d("2026-10-31"), a.query!!.range!!.end)
    }

    @Test
    fun `top 5 merchants this year`() {
        val a = ask("top 5 merchants this year")
        assertEquals(Metric.TOP_MERCHANTS, a.query!!.metric)
        assertEquals(listOf("BigBasket", "Hotel Avenida", "Amazon", "Netflix", "Time Out Market"), a.rows.map { it.label })
        assertTrue(a.text.startsWith("Your top 5 merchants this year: 1. BigBasket (₹32,200)"), a.text)
    }

    @Test
    fun `top 3 categories last month`() {
        val a = ask("top 3 categories last month")
        assertEquals(Metric.TOP_CATEGORIES, a.query!!.metric)
        assertEquals(listOf("Shopping", "Groceries", "Food & Dining"), a.rows.map { it.label })
    }

    @Test
    fun `where do I spend the most`() {
        val a = ask("where do I spend the most?")
        assertEquals(Metric.TOP_MERCHANTS, a.query!!.metric)
        assertEquals("BigBasket", a.rows.first().label)
    }

    @Test
    fun `average grocery spend per month in 2026`() {
        val a = ask("average grocery spend per month in 2026")
        assertEquals(Metric.AVG, a.query!!.metric)
        assertEquals(GroupBy.MONTH, a.query!!.groupBy)
        assertEquals(Money(292727, CurrencyCode.INR), a.value) // 32,200 over Jan..Nov = 11 months
        assertTrue(a.text.contains("11 months"), a.text)
    }

    @Test
    fun `average swiggy order`() {
        val a = ask("average Swiggy order")
        assertEquals(inr(430), a.value)
    }

    @Test
    fun `compare dining this month vs last month`() {
        val a = ask("compare dining this month vs last month")
        assertEquals(Metric.COMPARE, a.query!!.metric)
        assertEquals(inr(1200), a.rows[0].amount)
        assertEquals(inr(1700), a.rows[1].amount)
        assertEquals(-29, Math.round(a.changePct!!).toInt())
        assertTrue(a.text.contains("29% less"), a.text)
    }

    @Test
    fun `compare without second range uses previous period`() {
        val a = ask("compare uber spending in october")
        assertEquals(d("2026-09-01"), a.query!!.compareRange!!.start)
        assertEquals(inr(1000), a.rows[0].amount)
        assertEquals(inr(200), a.rows[1].amount)
    }

    @Test
    fun `biggest expense last week`() {
        val a = ask("biggest expense last week")
        assertEquals(Metric.MAX, a.query!!.metric)
        assertEquals(inr(5000), a.value)
        assertTrue(a.text.contains("Amazon"), a.text)
        assertTrue(a.text.contains("28 Oct 2026"), a.text)
    }

    @Test
    fun `smallest expense this month`() {
        val a = ask("smallest expense this month")
        assertEquals(inr(50), a.value)
    }

    @Test
    fun `biggest grocery purchase`() {
        assertEquals(inr(3000), ask("what's my biggest grocery purchase in 2026").value)
    }

    @Test
    fun `how many times did I order Swiggy`() {
        val a = ask("how many times did I order Swiggy")
        assertEquals(Metric.COUNT, a.query!!.metric)
        assertEquals(5, a.count)
        assertTrue(a.text.startsWith("You paid Swiggy 5 times"), a.text)
    }

    @Test
    fun `how often do I take Uber`() {
        assertEquals(5, ask("how often do I take Uber").count)
        assertEquals(3, ask("number of uber rides in october").count)
    }

    @Test
    fun `total spent in Lisbon trip in EUR`() {
        val a = ask("total spent in Lisbon trip in EUR")
        assertEquals(CurrencyCode.EUR, a.query!!.outputCurrency)
        assertEquals("Lisbon 2026", a.query!!.trip)
        assertEquals(eur("230"), a.value)
        assertTrue(a.text.contains("€230"), a.text)
    }

    @Test
    fun `output currency via converter`() {
        val a = ask("how much did I spend on uber in october in usd")
        assertEquals(Money.of("10", CurrencyCode.USD), a.value)
        assertTrue(a.text.contains("$10"), a.text)
    }

    @Test
    fun `output currency without converter falls back with note`() {
        val e = AskEngine(FIXED, AskFixture.categories, AskFixture.accounts, AskFixture.trips)
        val a = e.ask("uber spend in october in dollars", AskFixture.txns)
        assertEquals(inr(1000), a.value)
        assertTrue(a.text.contains("no exchange rates"), a.text)
    }

    @Test
    fun `list subscriptions`() {
        val a = ask("list subscriptions")
        assertTrue(a.query!!.subscriptionsOnly)
        assertEquals(2, a.count)
        assertTrue(a.text.contains("Netflix") && a.text.contains("Spotify"), a.text)
        assertEquals(inr(649 * 12 + 119 * 12), a.value)
    }

    @Test
    fun `show my amazon transactions`() {
        val a = ask("show my Amazon transactions")
        assertEquals(Metric.LIST, a.query!!.metric)
        assertEquals(2, a.rows.size)
        assertEquals(d("2026-11-04"), a.rows.first().date)
    }

    @Test
    fun `last 3 transactions`() {
        val a = ask("last 3 transactions")
        assertEquals(Metric.LIST, a.query!!.metric)
        assertEquals(3, a.rows.size)
        assertEquals("Starbucks", a.rows.first().label)
    }

    @Test
    fun `spending by category last month`() {
        val a = ask("spending by category last month")
        assertEquals(GroupBy.CATEGORY, a.query!!.groupBy)
        assertEquals(inr(11468), a.value)
        assertEquals("Shopping", a.rows.first().label)
    }

    @Test
    fun `monthly uber spend`() {
        val a = ask("monthly uber spend")
        assertEquals(listOf("Sep 2026", "Oct 2026", "Nov 2026"), a.rows.map { it.label })
        assertEquals(listOf(inr(200), inr(1000), inr(180)), a.rows.map { it.amount })
    }

    @Test
    fun `no matches gives zero`() {
        val a = ask("how much did I spend on Uber in 2024")
        assertEquals(inr(0), a.value)
        assertEquals("You spent ₹0 on Uber in 2024.", a.text)
    }
}

class QueryParserTest {
    private val p = QueryParser(FIXED)

    @Test
    fun `time expressions`() {
        val tp = TimeExpressionParser(TODAY)
        fun r(s: String) = tp.find(s)!!.range
        assertEquals(d("2026-03-01"), r("since march").start)
        assertEquals(TODAY, r("since march").end)
        assertEquals(d("2025-12-01"), r("in december").start) // future month -> last year
        assertEquals(d("2026-10-01") to d("2026-12-31"), r("q4").let { it.start to it.end })
        assertEquals(d("2025-01-01") to d("2025-03-31"), r("q1 2025").let { it.start to it.end })
        assertEquals(d("2026-10-01") to d("2026-10-15"), r("between 1 oct and 15 oct").let { it.start to it.end })
        assertEquals(d("2026-10-07"), r("last 30 days").start)
        assertEquals(d("2026-10-26") to d("2026-11-01"), r("last week").let { it.start to it.end })
        assertEquals(d("2026-11-02"), r("this week").start)
        assertEquals(d("2026-11-04"), r("yesterday").start)
        assertEquals(d("2025-01-01") to d("2025-12-31"), r("in 2025").let { it.start to it.end })
        assertEquals(d("2026-01-01"), r("this year").start)
        assertEquals(d("2026-07-01") to d("2026-09-30"), r("last quarter").let { it.start to it.end })
        assertEquals(d("2026-10-02"), r("on 2 oct").start)
        assertEquals(d("2026-08-06"), r("last 3 months").start)
        assertEquals(d("2026-10-23"), r("last two weeks").start)
        assertEquals(d("2025-12-20") to d("2026-01-05"), r("between 20 dec and 5 jan").let { it.start to it.end }.let { (a, b) -> a to b })
        assertNull(tp.find("how much did i spend"))
    }

    @Test
    fun `fallback merchant without vocabulary`() {
        val q = p.parse("how much did I spend on starbucks")
        assertEquals("starbucks", q.merchant)
        assertEquals(0.7, q.confidence)
    }

    @Test
    fun `fallback maps synonyms to categories`() {
        assertEquals("food", p.parse("what did I spend on food last month").category)
        assertEquals("groceries", p.parse("grocery spending this year").category)
    }

    @Test
    fun `income direction and grouping`() {
        val q = p.parse("how much did I earn per month this year")
        assertEquals(DirectionFilter.INCOME, q.direction)
        assertEquals(GroupBy.MONTH, q.groupBy)
    }

    @Test
    fun `currency filter vs output currency`() {
        val q = p.parse("total USD transactions in EUR")
        assertEquals(CurrencyCode.USD, q.currency)
        assertEquals(CurrencyCode.EUR, q.outputCurrency)
        assertNull(p.parse("spent in october").outputCurrency)
        assertNull(p.parse("spend on all things").outputCurrency)
    }

    @Test
    fun `top with word numbers`() {
        val q = p.parse("show me the top three merchants last month")
        assertEquals(Metric.TOP_MERCHANTS, q.metric)
        assertEquals(3, q.limit)
    }

    @Test
    fun `gibberish has low confidence`() {
        assertTrue(p.parse("zzz qqq").confidence < 0.5)
    }

    @Test
    fun `country detection`() {
        assertEquals("JP", p.parse("how much did I spend in Japan").country)
    }
}

class LlmHookTest {
    private val ctxTxns = AskFixture.txns

    @Test
    fun `llm used only when rule parser is unsure, output validated`() {
        var calls = 0
        var seen: LlmContext? = null
        val llm = LlmQueryTranslator { _, ctx -> calls++; seen = ctx; """{"metric":"SUM","merchant":"uber","range":"last month"}""" }
        val e = AskFixture.engine(llm)
        val a = e.ask("zzz qqq", ctxTxns)
        assertEquals(1, calls)
        assertEquals(inr(1000), a.value)
        assertEquals(0, calls - 1)
        e.ask("How much did I spend on Uber in October?", ctxTxns)
        assertEquals(1, calls, "confident rule parse must not call the LLM")
        // Context holds names and aggregates only.
        val ctx = seen!!
        assertTrue(ctx.categoryNames.contains("Groceries"))
        assertTrue(ctx.aggregates.isNotEmpty())
        val dump = ctx.toString()
        assertFalse(ctxTxns.any { dump.contains(it.id + ",") || dump.contains("id=" + it.id) })
    }

    @Test
    fun `invalid llm output falls back to rule parse`() {
        val e = AskFixture.engine { _, _ -> """{"metric":"DROP TABLE"}""" }
        val q = e.parse("zzz qqq", ctxTxns)
        assertTrue(q.confidence < 0.5)
        val e2 = AskFixture.engine { _, _ -> throw IllegalStateException("model unavailable") }
        assertNotNull(e2.parse("zzz", ctxTxns))
    }

    @Test
    fun `query json validation`() {
        val ok = QueryJson.parse("""{"metric":"TOP_MERCHANTS","range":{"start":"2026-01-01","end":"2026-03-31"},"outputCurrency":"eur"}""", TODAY).getOrThrow()
        assertEquals(Metric.TOP_MERCHANTS, ok.metric)
        assertEquals(5, ok.limit)
        assertEquals(CurrencyCode.EUR, ok.outputCurrency)
        assertTrue(QueryJson.parse("""{"metric":"SUM","limit":0}""", TODAY).isFailure)
        assertTrue(QueryJson.parse("""{"metric":"SUM","evil":1}""", TODAY).isFailure)
        assertTrue(QueryJson.parse("""{"metric":"SUM","range":{"start":"2026-05-01","end":"2026-04-01"}}""", TODAY).isFailure)
        assertTrue(QueryJson.parse("""{"metric":"COMPARE","range":"this month"}""", TODAY).isFailure)
        assertTrue(QueryJson.parse("""{"metric":"SUM","outputCurrency":"XYZ"}""", TODAY).isFailure)
        assertTrue(QueryJson.parse("""[1,2]""", TODAY).isFailure)
        assertTrue(QueryJson.parse("""{"metric":"SUM",""", TODAY).isFailure)
        val fenced = QueryJson.parse("```json\n{\"metric\":\"COUNT\",\"merchant\":\"Swiggy \\u0041\",\"subscriptionsOnly\":false}\n```", TODAY).getOrThrow()
        assertEquals("Swiggy A", fenced.merchant)
        assertEquals(Metric.COUNT, fenced.metric)
    }
}
