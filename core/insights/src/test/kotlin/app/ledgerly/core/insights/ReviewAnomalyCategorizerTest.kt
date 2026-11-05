package app.ledgerly.core.insights

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.Category
import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Trip
import app.ledgerly.core.model.TxnType
import org.junit.jupiter.api.Test
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommonTest {
    @Test
    fun `money formatting`() {
        assertEquals("₹5,000", MoneyFormat.format(inr(5000)))
        assertEquals("₹1,00,000", MoneyFormat.format(inr(100000)))
        assertEquals("₹12,34,567.50", MoneyFormat.format(inr("1234567.5")))
        assertEquals("$1,234.05", MoneyFormat.format(Money.of("1234.05", CurrencyCode.USD)))
        assertEquals("¥1,200", MoneyFormat.format(Money.of("1200", CurrencyCode.JPY)))
        assertEquals("-€3.10", MoneyFormat.format(eur("-3.1")))
        assertEquals("KWD 1.250", MoneyFormat.format(Money.of("1.25", CurrencyCode.KWD)))
    }

    @Test
    fun `merchant normalisation`() {
        assertEquals("netflix", MerchantNormalizer.normalize("NETFLIX.COM"))
        assertEquals("uber", MerchantNormalizer.normalize("UBER *TRIP 4521 BLR"))
        assertEquals("swiggy", MerchantNormalizer.normalize("UPI-SWIGGY"))
        assertEquals("amazon", MerchantNormalizer.normalize("Amazon Pvt Ltd"))
        assertEquals("air india", MerchantNormalizer.normalize("Air India"))
        assertNull(MerchantNormalizer.normalize("  "))
    }
}

class MonthlyReviewGeneratorTest {
    private val cats = listOf(Category("food", "Food"), Category("shop", "Shopping"), Category("groc", "Groceries"), Category("ent", "Entertainment"))
    private val gen = MonthlyReviewGenerator(FIXED, cats)
    private val oct = YearMonth.of(2026, 10)

    private fun base() = buildList {
        // Groceries history ~2000 each month (Apr..Sep), varied
        listOf("2026-04-12" to 1900, "2026-05-03" to 2100, "2026-06-25" to 2000, "2026-07-09" to 1950, "2026-08-28" to 2050, "2026-09-14" to 2000)
            .forEach { (dt, a) -> add(txn(dt, a, "DMart", category = "groc")) }
        add(txn(d("2026-10-12"), inr(2000), "DMart", category = "groc"))
        // Food: Sep 3000, Oct 6000
        add(txn("2026-09-05", 3000, "Swiggy", category = "food"))
        add(txn("2026-10-05", 6000, "Swiggy", category = "food"))
        // Netflix renewals
        (5..10).forEach { m -> add(txn("2026-%02d-15".format(m), 649, "Netflix", category = "ent")) }
        // Income
        add(txn(d("2026-10-01"), inr(50000), "ACME", TxnType.INCOME))
        // New merchant in October
        add(txn("2026-10-20", 1500, "Decathlon", category = "shop"))
    }

    @Test
    fun `total vs last month, category changes, new merchants, subscriptions, savings`() {
        val r = gen.generate(oct, base())
        assertEquals(inr(2000 + 6000 + 649 + 1500), r.totalSpend)
        assertEquals(inr(2000 + 3000 + 649), r.previousSpend)
        assertTrue(r.bullets.first().contains("more than last month"), r.bullets.first())
        assertEquals("Food", r.categoryChanges.first().name)
        assertEquals(100.0, r.categoryChanges.first().changePct)
        assertTrue(r.bullets.any { it.startsWith("Food up 100%") }, r.bullets.toString())
        assertEquals(listOf("Decathlon"), r.newMerchants)
        assertEquals(listOf("Netflix"), r.renewedSubscriptions)
        assertEquals(50000.0 - 10149.0, r.savingsRate!! * 500.0, 1.0)
        assertTrue(r.bullets.any { it.startsWith("You saved 80%") }, r.bullets.toString())
    }

    @Test
    fun `fx fees from input or transactions`() {
        val r = gen.generate(oct, base(), fxFeesTotal = inr(420))
        assertEquals(inr(420), r.fxFees)
        assertTrue(r.bullets.any { it.contains("₹420") && it.contains("foreign-exchange") })
        val withFee = base() + txn(d("2026-10-22"), inr(9000), "Hotel Lisboa", original = eur("100"), fxFee = inr(315))
        assertEquals(inr(315), gen.generate(oct, withFee).fxFees)
        assertNull(gen.generate(oct, base()).fxFees)
    }

    @Test
    fun `unusual spend via z-score`() {
        val txns = base() + txn("2026-10-25", 9000, "Nature's Basket", category = "groc")
        val r = gen.generate(oct, txns)
        assertEquals("Nature's Basket", r.unusual.single().transaction.merchant)
        assertTrue(r.bullets.any { it.startsWith("Unusual:") })
    }

    @Test
    fun `lifestyle creep when 3-month average rises more than 10 percent`() {
        val txns = listOf(5000, 5000, 5000, 6000, 6000, 6500).mapIndexed { i, a -> txn(d("2026-05-07").plusMonths(i.toLong()), inr(a), "Mall $i", category = "shop") }
        val r = gen.generate(oct, txns)
        val overall = r.lifestyleCreep.first { it.scope == "Overall" }
        assertEquals(inr(5000), overall.earlierAvg)
        assertTrue(overall.changePct > 20)
        assertTrue(r.bullets.any { it.startsWith("Lifestyle creep") })
        val flat = List(6) { i -> txn(d("2026-05-07").plusMonths(i.toLong()), inr(5000), "Mall $i", category = "shop") }
        assertTrue(gen.generate(oct, flat).lifestyleCreep.isEmpty())
    }
}

class AnomalyDetectorTest {
    private val accounts = listOf(Account("cc", "Visa", AccountType.CREDIT_CARD, CurrencyCode.INR))
    private val det = AnomalyDetector(FIXED, accounts)
    private val history = listOf(300, 350, 280, 320, 310).mapIndexed { i, a -> txn(d("2026-10-01").plusDays(i * 3L), inr(a), "Swiggy", category = "food") }

    @Test
    fun `normal transactions are not flagged`() {
        assertTrue(det.scan(history).isEmpty())
    }

    @Test
    fun `large versus merchant history`() {
        val big = txn(d("2026-11-01"), inr(2500), "Swiggy", category = "food")
        val a = det.scan(history + big, listOf(big)).single()
        assertEquals(AnomalyType.LARGE_VS_MERCHANT, a.type)
        assertEquals(Severity.HIGH, a.severity)
    }

    @Test
    fun `large versus category history for a new merchant`() {
        val big = txn(d("2026-11-01"), inr(1500), "Fancy Diner", category = "food")
        assertTrue(det.scan(history + big, listOf(big)).any { it.type == AnomalyType.LARGE_VS_CATEGORY })
    }

    @Test
    fun `duplicate charge within 10 minutes`() {
        val a = txn(d("2026-11-03"), inr(499), "Croma", hour = 14, minute = 0)
        val b = txn(d("2026-11-03"), inr(499), "CROMA", hour = 14, minute = 7)
        val c = txn(d("2026-11-03"), inr(499), "Croma", hour = 14, minute = 30)
        val flags = det.scan(listOf(a, b, c)).filter { it.type == AnomalyType.DUPLICATE_CHARGE }
        assertEquals(listOf(b.id), flags.map { it.transaction.id })
        assertEquals(listOf(a.id), flags.single().relatedIds)
    }

    @Test
    fun `new merchant abroad`() {
        val t = txn(d("2026-11-02"), inr(4500), "Pastelaria Lisboa", original = eur("50"))
        assertTrue(det.scan(history + t, listOf(t)).any { it.type == AnomalyType.NEW_MERCHANT_ABROAD })
        val trip = Trip("tr", "Bali", CurrencyCode.of("IDR"), "ID", d("2026-10-01"), null)
        val d2 = AnomalyDetector(FIXED, trips = listOf(trip), config = AnomalyConfig(homeCountry = "IN"))
        val t2 = txn(d("2026-11-02"), inr(800), "Warung", trip = "tr")
        assertTrue(d2.scan(history + t2, listOf(t2)).any { it.type == AnomalyType.NEW_MERCHANT_ABROAD })
    }

    @Test
    fun `odd hour transactions, configurable`() {
        val t = txn(d("2026-11-02"), inr(300), "Swiggy", hour = 3, category = "food")
        assertTrue(det.scan(history + t, listOf(t)).any { it.type == AnomalyType.ODD_HOUR })
        val off = AnomalyDetector(FIXED, config = AnomalyConfig(oddHourStart = 0, oddHourEnd = 0))
        assertTrue(off.scan(history + t, listOf(t)).none { it.type == AnomalyType.ODD_HOUR })
        val wrap = AnomalyDetector(FIXED, config = AnomalyConfig(oddHourStart = 23, oddHourEnd = 2))
        val late = txn(d("2026-11-02"), inr(300), "Swiggy", hour = 23, minute = 30, category = "food")
        assertTrue(wrap.scan(history + late, listOf(late)).any { it.type == AnomalyType.ODD_HOUR })
    }

    @Test
    fun `card not present heuristics`() {
        val t = txn(d("2026-11-02"), inr(8000), "WWW.GADGETSHOP.COM", account = "cc", original = Money.of("95", CurrencyCode.USD), merchantRaw = "WWW.GADGETSHOP.COM")
        val flags = det.scan(history + t, listOf(t))
        assertNotNull(flags.firstOrNull { it.type == AnomalyType.CARD_NOT_PRESENT && it.severity == Severity.HIGH })
        val local = txn(d("2026-11-02"), inr(250), "Swiggy Online", account = "cc", category = "food")
        assertTrue(det.scan(history + local, listOf(local)).none { it.type == AnomalyType.CARD_NOT_PRESENT })
    }

    @Test
    fun `card testing pattern`() {
        val tiny = txn(d("2026-11-02"), inr("1"), "Shady Store", hour = 10)
        val big = txn(d("2026-11-02"), inr(15000), "Shady Store", hour = 10, minute = 20)
        assertTrue(det.scan(listOf(tiny, big), listOf(big)).any { it.type == AnomalyType.CARD_TESTING })
    }
}

class YearInReviewTest {
    private val cats = listOf(Category("food", "Food"), Category("cafe", "Cafes", parentId = "food"), Category("travel", "Travel"), Category("groc", "Groceries"))
    private val trips = listOf(Trip("t1", "Lisbon", CurrencyCode.EUR, "PT", d("2026-09-10"), d("2026-09-17")))
    private val yir = YearInReview(FIXED, cats, trips)

    private val txns = listOf(
        txn("2026-01-01", 1000, "Starbucks", category = "cafe"),
        txn("2026-01-02", 1000, "Starbucks", category = "cafe"),
        txn("2026-01-02", 500, "DMart", category = "groc"),
        txn("2026-01-02", 500, "Starbucks", category = "cafe"),
        txn(d("2026-09-12"), inr(6000), "Hotel", category = "travel", trip = "t1", original = eur("65")),
        txn(d("2026-10-01"), inr(1000), "Shake Shack", category = "food", original = Money.of("12", CurrencyCode.USD)),
        txn(d("2026-11-04"), inr(100000), "Salary", TxnType.INCOME),
    )

    @Test
    fun `amount-free stats`() {
        val y = yir.generate(2026, txns)
        assertEquals(listOf(CategoryShare("Travel", 60), CategoryShare("Food", 35), CategoryShare("Groceries", 5)), y.topCategories)
        assertEquals("Starbucks", y.mostVisitedMerchant)
        assertEquals(3, y.mostVisitedCount)
        assertEquals(listOf("PT", "US"), y.countries)
        assertEquals(listOf("Portugal", "United States"), y.countryNames)
        assertEquals(d("2026-01-02"), y.busiestDay)
        assertEquals(3, y.busiestDayCount)
        assertEquals(6, y.transactionCount)
        // Longest streak: Jan 3 .. Sep 11
        assertEquals(d("2026-01-03"), y.noSpendStreakStart)
        assertEquals(d("2026-09-11"), y.noSpendStreakEnd)
        assertEquals(252, y.longestNoSpendStreakDays)
        assertTrue(y.shareLines.none { it.contains("₹") || it.contains("$") || it.contains("€") }, y.shareLines.toString())
    }

    @Test
    fun `streak for the current year stops at today`() {
        val y = yir.generate(2026, listOf(txn("2026-10-01", 100, "X")))
        assertEquals(d("2026-01-01"), y.noSpendStreakStart)
        assertEquals(d("2026-09-30"), y.noSpendStreakEnd)
        val empty = yir.generate(2026, emptyList())
        assertEquals(309, empty.longestNoSpendStreakDays) // Jan 1 .. Nov 5
        assertNull(empty.busiestDay)
    }
}

class CategorizerTest {
    private val rules = listOf(
        CategoryRule("r1", "Uber is transport", 10, listOf(RuleCondition.MerchantContains("uber")), "transport", setOf("ride")),
        CategoryRule("r2", "Food apps", 5, listOf(RuleCondition.MerchantRegex("^(swiggy|zomato)")), "food"),
        CategoryRule("r3", "Big card spend", 1, listOf(RuleCondition.AmountRange(min = inr(10000)), RuleCondition.AccountIs(setOf("cc"))), null, setOf("big"), stopProcessing = false),
        CategoryRule("r4", "Uber Eats", 20, listOf(RuleCondition.MerchantContains("uber eats")), "food", stopProcessing = false),
        CategoryRule("r5", "Salary", 3, listOf(RuleCondition.DirectionIs(Flow.IN), RuleCondition.MerchantContains("acme")), "salary"),
        CategoryRule("r6", "Email receipts", 0, listOf(RuleCondition.ChannelIs(setOf(Channel.EMAIL))), null, setOf("email")),
    )

    @Test
    fun `rules apply by priority`() {
        val c = Categorizer(rules)
        val s = c.categorize(txn("2026-11-01", 250, "UBER *TRIP"))
        assertEquals("transport", s.categoryId)
        assertEquals(SuggestionSource.RULE, s.source)
        assertEquals(setOf("ride"), s.tags)
        assertFalse(s.needsReview)
        // Higher priority Uber Eats wins category, continues to collect tags of r1
        val e = c.categorize(txn("2026-11-01", 400, "Uber Eats"))
        assertEquals("food", e.categoryId)
        assertEquals(listOf("r4", "r1"), e.ruleIds)
        assertEquals(setOf("ride"), e.tags)
    }

    @Test
    fun `regex amount account direction and channel conditions`() {
        val c = Categorizer(rules)
        assertEquals("food", c.categorize(txn("2026-11-01", 300, "Zomato Ltd")).categoryId)
        val big = c.categorize(txn(d("2026-11-01"), inr(20000), "Croma", account = "cc"))
        assertEquals(setOf("big"), big.tags)
        assertNull(big.categoryId)
        assertTrue(big.needsReview)
        assertEquals("salary", c.categorize(txn("2026-11-01", 50000, "ACME Corp", TxnType.INCOME)).categoryId)
        assertNull(c.categorize(txn("2026-11-01", 50000, "ACME Corp")).categoryId)
        val mail = txn("2026-11-01", 10, "Random").copy(sourceChannels = setOf(Channel.EMAIL))
        assertEquals(setOf("email"), c.categorize(mail).tags)
    }

    @Test
    fun `learning from corrections with confidence`() {
        val c = Categorizer()
        val t = txn("2026-11-01", 500, "Blue Tokai Coffee")
        assertEquals(SuggestionSource.NONE, c.categorize(t).source)
        c.learn(t, "cafe")
        val one = c.categorize(t)
        assertEquals("cafe", one.categoryId)
        assertEquals(0.5, one.confidence, 1e-9)
        assertTrue(one.needsReview)
        c.learn(t, "cafe"); c.learn(t, "cafe")
        val three = c.categorize(txn("2026-11-02", 300, "BLUE TOKAI COFFEE"))
        assertEquals(0.75, three.confidence, 1e-9)
        assertFalse(three.needsReview)
        c.learn(t, "food")
        assertEquals(0.6, c.categorize(t).confidence, 1e-9) // 3/4 * 4/5
    }

    @Test
    fun `strong learned mapping overrides a rule`() {
        val c = Categorizer(rules)
        val t = txn("2026-11-01", 250, "Uber")
        repeat(4) { c.learn(t, "business_travel") }
        val s = c.categorize(t)
        assertEquals("business_travel", s.categoryId)
        assertEquals(SuggestionSource.LEARNED, s.source)
    }

    @Test
    fun `needs review export, apply, import and export`() {
        val c = Categorizer(rules)
        val txns = listOf(txn("2026-11-01", 250, "Uber"), txn("2026-11-01", 999, "Mystery Shop"), txn("2026-11-01", 50, "Chai", category = "food"))
        val review = c.needsReview(txns)
        assertEquals(listOf("Mystery Shop"), review.map { it.first.merchant })
        val applied = c.apply(txns[0])
        assertEquals("transport", applied.categoryId)
        assertTrue("ride" in applied.tags)
        c.learnFrom(txns)
        val exported = c.exportLearned()
        assertEquals(mapOf("food" to 1), exported["chai"])
        val c2 = Categorizer()
        c2.importLearned(exported)
        assertEquals("food", c2.categorize(txn("2026-11-02", 40, "Chai")).categoryId)
    }

    @Test
    fun `disabled rules are skipped`() {
        val c = Categorizer(listOf(rules[0].copy(enabled = false)))
        assertNull(c.categorize(txn("2026-11-01", 250, "Uber")).categoryId)
    }
}
