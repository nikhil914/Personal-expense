package app.ledgerly.core.insights

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.Category
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Trip
import java.time.Clock
import java.time.LocalDate
import java.util.Currency
import java.util.Locale

/** Words the parser can resolve to entities. Built from the user's data; nothing leaves the device. */
data class AskVocabulary(
    /** Merchant display names (raw or normalised). */
    val merchants: Collection<String> = emptyList(),
    val categories: Collection<Category> = emptyList(),
    val accounts: Collection<Account> = emptyList(),
    val trips: Collection<Trip> = emptyList(),
    val tags: Collection<String> = emptyList(),
)

/** Built-in category synonym groups. The first word of each group is its canonical term. */
object CategorySynonyms {
    val groups: List<List<String>> = listOf(
        listOf("food", "dining", "restaurant", "restaurants", "eating out", "takeout", "takeaway", "food delivery", "meals"),
        listOf("groceries", "grocery", "supermarket", "supermarkets", "vegetables"),
        listOf("transport", "transportation", "commute", "cab", "cabs", "taxi", "taxis", "rides", "travel local"),
        listOf("fuel", "petrol", "diesel"),
        listOf("shopping", "clothes", "clothing", "apparel"),
        listOf("entertainment", "movies", "movie"),
        listOf("bills", "utilities", "utility", "electricity", "internet bill", "phone bill"),
        listOf("rent", "housing"),
        listOf("health", "medical", "medicine", "medicines", "pharmacy", "doctor"),
        listOf("travel", "flights", "flight", "hotels", "hotel"),
        listOf("education", "courses", "tuition"),
        listOf("coffee", "cafe", "cafes"),
        listOf("subscriptions", "subscription"),
    )

    /** Group containing [term] (case-insensitive), if any. */
    fun groupOf(term: String): List<String>? {
        val t = term.lowercase().trim()
        return groups.firstOrNull { g -> t in g }
    }
}

/** Rule-based, offline natural-language → [Query] parser. */
class QueryParser(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val vocabulary: AskVocabulary = AskVocabulary(),
) {
    private val currencyWords = mapOf(
        "euro" to "EUR", "euros" to "EUR", "dollar" to "USD", "dollars" to "USD", "rupee" to "INR", "rupees" to "INR",
        "pound" to "GBP", "pounds" to "GBP", "yen" to "JPY", "dirham" to "AED", "dirhams" to "AED",
        "₹" to "INR", "$" to "USD", "€" to "EUR", "£" to "GBP",
    )
    /** ISO codes that are also everyday English words. */
    private val ambiguousCodes = setOf("ALL", "TOP", "CUP", "MOP", "AMD", "BAM", "MAD", "CAD", "PEN", "TRY", "GEL", "LAK")
    private val numberWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "twenty" to 20)

    private val stopWords = setOf(
        "i", "me", "my", "we", "our", "the", "a", "an", "did", "do", "does", "spend", "spent", "spending", "much", "how",
        "what", "was", "is", "are", "total", "in", "on", "at", "of", "for", "all", "so", "far", "money", "expenses",
        "expense", "transactions", "transaction", "this", "that", "trip", "times", "time", "many", "order", "ordered",
        "orders", "pay", "paid", "buy", "bought", "from", "to", "with", "and", "per", "by", "get", "got", "there", "it",
        "have", "has", "had", "been", "be", "use", "used", "using", "payments", "payment", "purchases", "purchase",
        "charges", "charge", "show", "list", "tell", "give", "can", "you", "please", "average", "avg", "biggest",
        "largest", "smallest", "top", "merchants", "categories", "compare", "vs", "versus", "month", "months", "week",
        "year", "day", "days", "where", "when", "which", "who", "most", "least", "number", "count", "often", "every",
        "each", "monthly", "weekly", "yearly", "highest", "lowest", "max", "min", "maximum", "minimum", "sum", "costs",
        "cost", "costing", "lot", "about", "over", "under", "go", "went", "single", "one",
    )

    fun parse(question: String): Query {
        val today = LocalDate.now(clock)
        var text = " " + question.lowercase(Locale.ROOT)
            .replace(Regex("[?!]"), " ")
            .replace(Regex("(?<=\\D)[.,;:](?=\\D|$)"), " ")
            .replace(Regex("\\s+"), " ").trim() + " "
        var understood = 0
        var q = Query()

        // Output currency: "in EUR", "in euros", "to usd"
        Regex("\\b(?:in|into|to)\\s+(₹|\\$|€|£|[a-z]{3}\\b|euros?\\b|dollars?\\b|rupees?\\b|pounds?\\b|yen\\b|dirhams?\\b)").findAll(text).toList()
            .lastOrNull { resolveCurrency(it.groupValues[1]) != null }?.let { m ->
                q = q.copy(outputCurrency = resolveCurrency(m.groupValues[1]))
                text = text.replaceRange(m.range, " "); understood++
            }
        // Currency filter: "USD transactions", "paid in usd" (after output currency, "paid in" wins as filter)
        Regex("\\b([a-z]{3})\\s+(?:transactions|purchases|charges|spend|spending|expenses|payments)\\b").find(text)?.let { m ->
            resolveCurrency(m.groupValues[1])?.let { c -> q = q.copy(currency = c); text = text.replaceRange(m.range, " "); understood++ }
        }

        // Compare
        val compareSplit = Regex("\\s(?:vs\\.?|versus|compared to|compared with|against)\\s").find(text)
        if (Regex("\\bcompare\\b").containsMatchIn(text) || compareSplit != null) {
            q = q.copy(metric = Metric.COMPARE); understood++
            if (compareSplit != null) {
                val left = text.substring(0, compareSplit.range.first)
                val right = text.substring(compareSplit.range.last + 1)
                val tp = TimeExpressionParser(today)
                val r1 = tp.find(left); val r2 = tp.find(right)
                if (r1 != null) { q = q.copy(range = r1.range); text = text.replaceFirst(r1.matchedText, " ") }
                if (r2 != null) { q = q.copy(compareRange = r2.range); text = text.replaceFirst(r2.matchedText, " ") }
                text = text.replaceFirst(compareSplit.value, " ")
            }
            if (q.range == null) {
                val ym = java.time.YearMonth.from(today)
                q = q.copy(range = DateRange(ym.atDay(1), ym.atEndOfMonth(), "this month"))
            }
            if (q.compareRange == null) {
                val r = q.range!!
                val len = java.time.temporal.ChronoUnit.DAYS.between(r.start, r.end) + 1
                val prev = if (r.start.dayOfMonth == 1 && r.end == r.end.withDayOfMonth(r.end.lengthOfMonth())) {
                    val months = java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(r.start), java.time.YearMonth.from(r.end)) + 1
                    val s = r.start.minusMonths(months)
                    DateRange(s, r.start.minusDays(1), "the previous period")
                } else DateRange(r.start.minusDays(len), r.start.minusDays(1), "the previous period")
                q = q.copy(compareRange = prev)
            }
        }

        // Grouping (before time so "per month" isn't misread)
        Regex("\\b(?:by|per|each|every)\\s+month\\b|\\bmonthly\\b|\\bmonth by month\\b|\\bmonth-wise\\b").find(text)?.let {
            q = q.copy(groupBy = GroupBy.MONTH); text = text.replaceRange(it.range, " "); understood++
        }
        Regex("\\b(?:by|per)\\s+categor(?:y|ies)\\b|\\bcategory-wise\\b").find(text)?.let {
            q = q.copy(groupBy = GroupBy.CATEGORY); text = text.replaceRange(it.range, " "); understood++
        }
        Regex("\\b(?:by|per)\\s+merchants?\\b").find(text)?.let {
            q = q.copy(groupBy = GroupBy.MERCHANT); text = text.replaceRange(it.range, " "); understood++
        }

        // Time
        if (q.metric != Metric.COMPARE) {
            TimeExpressionParser(today).find(text)?.let { m ->
                q = q.copy(range = m.range); text = text.replaceFirst(m.matchedText, " "); understood++
            }
        }

        // Metric
        val top = Regex("\\b(?:top|best|biggest|largest|main)\\s*(\\d+|${numberWords.keys.joinToString("|")})?\\s+(merchants?|stores?|shops?|places|payees|vendors|brands|categor(?:y|ies))\\b").find(text)
        val metricFound = when {
            q.metric == Metric.COMPARE -> true
            top != null -> {
                val n = top.groupValues[1].let { it.toIntOrNull() ?: numberWords[it] }
                val isCat = top.groupValues[2].startsWith("categor")
                q = q.copy(metric = if (isCat) Metric.TOP_CATEGORIES else Metric.TOP_MERCHANTS, limit = n ?: 5)
                text = text.replaceRange(top.range, " "); true
            }
            Regex("\\bwhich categor(?:y|ies)\\b|\\bwhat categor(?:y|ies)\\b").containsMatchIn(text) ->
                { q = q.copy(metric = Metric.TOP_CATEGORIES, limit = 5); true }
            Regex("\\bwhere (?:do|did) i spend\\b|\\bwhere does my money go\\b|\\bwhich merchants?\\b|\\bwhat merchants?\\b").containsMatchIn(text) ->
                { q = q.copy(metric = Metric.TOP_MERCHANTS, limit = 5); true }
            Regex("\\bhow many\\b|\\bhow often\\b|\\bnumber of\\b|\\bcount\\b|\\bno\\. of\\b").containsMatchIn(text) ->
                { q = q.copy(metric = Metric.COUNT); true }
            Regex("\\baverage\\b|\\bavg\\b|\\bmean\\b|\\btypical(?:ly)?\\b|\\bnormally\\b|\\busually\\b").containsMatchIn(text) ->
                { q = q.copy(metric = Metric.AVG); true }
            Regex("\\b(?:biggest|largest|highest|most expensive|max(?:imum)?|costliest|priciest)\\b").containsMatchIn(text) ->
                { q = q.copy(metric = Metric.MAX); true }
            Regex("\\b(?:smallest|lowest|cheapest|min(?:imum)?|least expensive)\\b").containsMatchIn(text) ->
                { q = q.copy(metric = Metric.MIN); true }
            Regex("\\bhow much\\b|\\btotal\\b|\\bsum\\b|\\bwhat did i (?:spend|earn|pay)\\b|\\bspent\\b|\\bspend\\b|\\bearn(?:ed)?\\b").containsMatchIn(text) ->
                { q = q.copy(metric = Metric.SUM); true }
            Regex("^\\s*(?:list|show|display|which|what are|what were)\\b|\\blist\\b|\\bsubscriptions?\\b").containsMatchIn(text) ->
                { q = q.copy(metric = Metric.LIST); true }
            else -> false
        }
        if (metricFound) understood++
        if (q.metric == Metric.LIST || q.metric == Metric.SUM) {
            Regex("\\blast\\s+(\\d+)\\s+(?:transactions|expenses|purchases|payments)\\b").find(text)?.let {
                q = q.copy(metric = Metric.LIST, limit = it.groupValues[1].toInt()); text = text.replaceRange(it.range, " ")
            }
        }

        // Subscriptions
        if (Regex("\\bsubscriptions?\\b|\\brecurring (?:payments|charges|bills)\\b").containsMatchIn(text)) {
            q = q.copy(subscriptionsOnly = true)
            text = text.replace(Regex("\\bsubscriptions?\\b|\\brecurring (?:payments|charges|bills)\\b"), " ")
        }

        // Direction
        if (Regex("\\b(?:earn(?:ed)?|income|received|receive|salary|got paid|credited|inflows?|made)\\b").containsMatchIn(text)) {
            q = q.copy(direction = DirectionFilter.INCOME); understood++
            text = text.replace(Regex("\\b(?:earn(?:ed)?|income|received|receive|salary|got paid|credited|inflows?)\\b"), " ")
        } else if (Regex("\\b(?:all transactions|everything)\\b").containsMatchIn(text)) {
            q = q.copy(direction = DirectionFilter.ALL)
        }

        // Tag
        Regex("#([\\p{L}0-9_-]+)|\\btagged\\s+(?:as\\s+|with\\s+)?([\\p{L}0-9_-]+)").find(text)?.let { m ->
            q = q.copy(tag = m.groupValues[1].ifEmpty { m.groupValues[2] }); text = text.replaceRange(m.range, " "); understood++
        }

        // Trip
        for (trip in vocabulary.trips.sortedByDescending { it.name.length }) {
            val name = trip.name.lowercase()
            val words = name.split(Regex("\\s+")).filter { it.length >= 3 && it.any(Char::isLetter) && it != "trip" }
            val full = Regex("\\b${Regex.escape(name)}(?:\\s+trip)?\\b")
            val hit = full.find(text) ?: words.firstNotNullOfOrNull { w -> Regex("\\b${Regex.escape(w)}(?:\\s+trip)?\\b").find(text) }
            if (hit != null) {
                q = q.copy(trip = trip.name); text = text.replaceRange(hit.range, " "); understood++; break
            }
        }
        if (q.trip == null) {
            Regex("\\b([a-z]+)\\s+trip\\b").find(text)?.let { m ->
                val w = m.groupValues[1]
                if (w !in stopWords && w != "last" && w != "my") { q = q.copy(trip = w); text = text.replaceRange(m.range, " "); understood++ }
            }
        }

        // Country
        if (q.trip == null) {
            for ((name, code) in countryNames) {
                val m = Regex("\\b${Regex.escape(name)}\\b").find(text) ?: continue
                q = q.copy(country = code); text = text.replaceRange(m.range, " "); understood++; break
            }
        }

        // Account
        Regex("\\b(?:card|account)\\s+(?:ending\\s+(?:in\\s+|with\\s+)?)?(?:x+|\\*+)?(\\d{4})\\b|\\bx{1,4}(\\d{4})\\b").find(text)?.let { m ->
            q = q.copy(account = m.groupValues[1].ifEmpty { m.groupValues[2] }); text = text.replaceRange(m.range, " "); understood++
        }
        if (q.account == null) {
            val candidates = vocabulary.accounts.flatMap { a -> listOfNotNull(a.name, a.institution).map { it.lowercase() to a.name } }
                .sortedByDescending { it.first.length }
            for ((phrase, name) in candidates) {
                val m = Regex("\\b${Regex.escape(phrase)}\\b(?:\\s+(?:card|account|credit card|debit card|wallet))?").find(text) ?: continue
                q = q.copy(account = name); text = text.replaceRange(m.range, " "); understood++; break
            }
        }

        // Merchant (vocabulary, longest first)
        val merchantCandidates = vocabulary.merchants.flatMap { m ->
            listOfNotNull(m.lowercase().trim(), MerchantNormalizer.normalize(m)).map { it to m }
        }.filter { it.first.length >= 2 }.distinctBy { it.first }.sortedByDescending { it.first.length }
        var merchantHit: Pair<String, IntRange>? = null
        for ((phrase, display) in merchantCandidates) {
            val m = Regex("\\b${Regex.escape(phrase).replace(" ", "\\E\\s+\\Q")}(?:'s)?\\b").find(text) ?: continue
            if (phrase in stopWords) continue
            merchantHit = display to m.range; break
        }

        // Category (exact vocabulary name, then synonyms)
        var categoryHit: Pair<String, IntRange>? = null
        for (c in vocabulary.categories.sortedByDescending { it.name.length }) {
            val m = Regex("\\b${Regex.escape(c.name.lowercase())}\\b").find(text) ?: continue
            categoryHit = c.name to m.range; break
        }
        if (categoryHit == null) {
            loop@ for (g in CategorySynonyms.groups) for (w in g.sortedByDescending { it.length }) {
                val m = Regex("\\b${Regex.escape(w)}\\b").find(text) ?: continue
                if (q.subscriptionsOnly && g.first() == "subscriptions") continue
                categoryHit = (vocabulary.categories.firstOrNull { cat -> categoryMatchesTerm(cat.name, w) }?.name ?: w) to m.range
                break@loop
            }
        }
        if (merchantHit != null && categoryHit != null) {
            // Prefer the longer match if they overlap; otherwise keep both.
            val overlap = merchantHit.second.first <= categoryHit.second.last && categoryHit.second.first <= merchantHit.second.last
            if (overlap) {
                if (merchantHit.second.count() >= categoryHit.second.count()) categoryHit = null else merchantHit = null
            }
        }
        merchantHit?.let { q = q.copy(merchant = it.first); text = text.replaceRange(it.second, " ".repeat(it.second.count())); understood++ }
        categoryHit?.let {
            q = q.copy(category = it.first)
            if (text.length >= it.second.last + 1) text = text.replaceRange(it.second, " ".repeat(it.second.count()))
            understood++
        }

        // Fallback: free text after a preposition is treated as a merchant name.
        var usedFallback = false
        if (q.merchant == null && q.category == null) {
            Regex("\\b(?:on|at|from|with|to|ordered|order|bought|paid|in)\\s+([\\p{L}0-9&' ]+)").findAll(text).forEach { m ->
                if (q.merchant != null) return@forEach
                val words = m.groupValues[1].trim().split(Regex("\\s+")).filter { it.isNotBlank() && it !in stopWords && !it.all(Char::isDigit) }
                if (words.isNotEmpty()) {
                    val term = words.take(3).joinToString(" ")
                    val g = CategorySynonyms.groupOf(term)
                    q = if (g != null) q.copy(category = g.first()) else q.copy(merchant = term)
                    usedFallback = true
                }
            }
        }
        if (q.metric == Metric.LIST && q.subscriptionsOnly && q.range == null) understood++

        val confidence = when {
            understood == 0 -> 0.2
            !metricFound && q.merchant == null && q.category == null && q.range == null -> 0.3
            usedFallback -> 0.7
            else -> 1.0
        }
        return q.copy(confidence = confidence)
    }

    private fun resolveCurrency(token: String): CurrencyCode? {
        currencyWords[token]?.let { return CurrencyCode(it) }
        if (token.length != 3 || !token.all { it in 'a'..'z' }) return null
        val up = token.uppercase(Locale.ROOT)
        if (up in ambiguousCodes) return null
        if (TimeExpressionParser(LocalDate.now(clock)).parsePhrase(token) != null) return null // "in oct"
        return runCatching { Currency.getInstance(up); CurrencyCode(up) }.getOrNull()
    }

    companion object {
        /** English country names (lowercase) → ISO alpha-2, longest names first. */
        val countryNames: List<Pair<String, String>> by lazy {
            val extra = listOf("usa" to "US", "america" to "US", "uk" to "GB", "england" to "GB", "uae" to "AE", "dubai" to "AE")
            (Locale.getISOCountries().map { regionLocale(it).getDisplayCountry(Locale.ENGLISH).lowercase() to it }
                .filter { it.first.length > 3 && it.first !in setOf("chad", "jersey", "georgia", "turkey", "jordan", "guinea") } + extra)
                .sortedByDescending { it.first.length }
        }

        internal fun categoryMatchesTerm(categoryName: String, term: String): Boolean {
            val name = categoryName.lowercase()
            val t = term.lowercase().trim()
            if (name == t) return true
            val nameWords = name.split(Regex("[^\\p{L}0-9]+")).filter { it.isNotBlank() }.toSet()
            if (t in nameWords) return true
            val group = CategorySynonyms.groupOf(t) ?: return false
            return group.any { w -> w in nameWords || name.contains(w) && w.contains(' ') }
        }
    }
}
