package app.ledgerly.core.insights

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.Category
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Currency

/** Aggregate-only fact that may be shared with an on-device LLM (never individual transactions). */
data class AggregateFact(val label: String, val value: String)

/** Context handed to an LLM translator: names and aggregates only. */
data class LlmContext(
    val today: LocalDate,
    val categoryNames: List<String>,
    val merchantNames: List<String>,
    val tripNames: List<String>,
    val accountNames: List<String>,
    val aggregates: List<AggregateFact>,
) {
    /** JSON schema the translator must emit. */
    val schema: String get() = QueryJson.SCHEMA
}

/**
 * Hook for an on-device model (e.g. Gemini Nano via ML Kit GenAI / AICore). Implementations return a JSON object
 * matching [QueryJson.SCHEMA], or null if unavailable. Output is always validated by [QueryJson.parse].
 */
fun interface LlmQueryTranslator {
    fun translate(question: String, context: LlmContext): String?
}

/** Strict validator turning translator JSON into a [Query]. */
object QueryJson {
    const val SCHEMA = """{"metric":"SUM|COUNT|AVG|MAX|MIN|LIST|TOP_MERCHANTS|TOP_CATEGORIES|COMPARE","merchant":string?,"category":string?,"tag":string?,"currency":"ISO4217"?,"account":string?,"trip":string?,"country":"ISO3166-alpha2"?,"direction":"SPEND|INCOME|ALL"?,"range":{"start":"YYYY-MM-DD","end":"YYYY-MM-DD"}|"phrase"?,"compareRange":same?,"groupBy":"NONE|MONTH|CATEGORY|MERCHANT"?,"limit":1-100?,"outputCurrency":"ISO4217"?,"subscriptionsOnly":bool?}"""

    private val allowedKeys = setOf(
        "metric", "merchant", "category", "tag", "currency", "account", "trip", "country", "direction", "range",
        "compareRange", "groupBy", "limit", "outputCurrency", "subscriptionsOnly",
    )

    fun parse(json: String, today: LocalDate): Result<Query> = runCatching {
        val root = MiniJson.parse(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        require(root is Map<*, *>) { "Top-level JSON must be an object" }
        val unknown = root.keys.map { it.toString() } - allowedKeys
        require(unknown.isEmpty()) { "Unknown keys: $unknown" }
        fun str(k: String): String? {
            val v = root[k] ?: return null
            require(v is String) { "$k must be a string" }
            val t = v.trim()
            require(t.length in 1..64) { "$k length must be 1..64" }
            require(t.none { it.isISOControl() }) { "$k has control characters" }
            return t
        }
        fun <E : Enum<E>> enumOf(k: String, values: Array<E>, default: E): E {
            val s = str(k) ?: return default
            return values.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: throw IllegalArgumentException("Invalid $k: $s")
        }
        fun currency(k: String): CurrencyCode? = str(k)?.let { c ->
            val up = c.uppercase()
            require(up.length == 3) { "Invalid $k" }
            runCatching { Currency.getInstance(up) }.getOrElse { throw IllegalArgumentException("Unknown currency $up") }
            CurrencyCode(up)
        }
        fun range(k: String): DateRange? {
            val v = root[k] ?: return null
            return when (v) {
                is String -> TimeExpressionParser(today).parsePhrase(v) ?: throw IllegalArgumentException("Unparseable $k phrase: $v")
                is Map<*, *> -> {
                    val s = LocalDate.parse(v["start"] as? String ?: throw IllegalArgumentException("$k.start missing"))
                    val e = LocalDate.parse(v["end"] as? String ?: throw IllegalArgumentException("$k.end missing"))
                    require(!e.isBefore(s)) { "$k end before start" }
                    require(s.year in 1990..today.year + 1) { "$k out of bounds" }
                    DateRange(s, e, (v["label"] as? String)?.take(40) ?: "between ${s.shortLabel()} and ${e.shortLabel()}")
                }
                else -> throw IllegalArgumentException("$k must be object or phrase")
            }
        }
        val limit = root["limit"]?.let { v ->
            require(v is Number) { "limit must be a number" }
            val n = v.toDouble()
            require(n == Math.floor(n) && n in 1.0..100.0) { "limit must be an integer 1..100" }
            n.toInt()
        }
        val country = str("country")?.uppercase()?.also { require(it.length == 2 && it in java.util.Locale.getISOCountries()) { "Invalid country" } }
        val subs = root["subscriptionsOnly"]?.let { require(it is Boolean) { "subscriptionsOnly must be boolean" }; it as Boolean } ?: false
        val metric = enumOf("metric", Metric.entries.toTypedArray(), Metric.SUM)
        var q = Query(
            metric = metric,
            merchant = str("merchant"), category = str("category"), tag = str("tag"), currency = currency("currency"),
            account = str("account"), trip = str("trip"), country = country,
            direction = enumOf("direction", DirectionFilter.entries.toTypedArray(), DirectionFilter.SPEND),
            range = range("range"), compareRange = range("compareRange"),
            groupBy = enumOf("groupBy", GroupBy.entries.toTypedArray(), GroupBy.NONE),
            limit = limit, outputCurrency = currency("outputCurrency"), subscriptionsOnly = subs, confidence = 0.8,
        )
        if (metric == Metric.COMPARE) {
            require(q.range != null && q.compareRange != null) { "COMPARE needs range and compareRange" }
        }
        if ((metric == Metric.TOP_MERCHANTS || metric == Metric.TOP_CATEGORIES) && q.limit == null) q = q.copy(limit = 5)
        q
    }
}

/**
 * On-device "chat with your money". Parses with the rule engine first; if the rule parser is unsure and an
 * [LlmQueryTranslator] is configured, asks it for JSON (sharing only names and aggregates) and validates it.
 */
class AskEngine(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val categories: Collection<Category> = emptyList(),
    private val accounts: Collection<Account> = emptyList(),
    private val trips: Collection<Trip> = emptyList(),
    private val converter: MoneyConverter? = null,
    private val llm: LlmQueryTranslator? = null,
    /** Below this rule-parser confidence the LLM (if any) is consulted. */
    private val llmThreshold: Double = 0.6,
) {
    fun vocabulary(transactions: Collection<Transaction>) = AskVocabulary(
        merchants = transactions.mapNotNull { it.merchant }.distinct(),
        categories = categories,
        accounts = accounts,
        trips = trips,
        tags = transactions.flatMap { it.tags }.distinct(),
    )

    fun parse(question: String, transactions: Collection<Transaction>): Query {
        val rule = QueryParser(clock, vocabulary(transactions)).parse(question)
        if (rule.confidence >= llmThreshold || llm == null) return rule
        val ctx = llmContext(transactions)
        val json = runCatching { llm!!.translate(question, ctx) }.getOrNull() ?: return rule
        return QueryJson.parse(json, LocalDate.now(clock)).getOrNull() ?: rule
    }

    fun ask(question: String, transactions: Collection<Transaction>): Answer {
        val q = parse(question, transactions)
        return QueryExecutor(clock, categories, accounts, trips, converter).execute(q, transactions)
    }

    /** Names + monthly/category totals only. No individual transactions, notes or references. */
    fun llmContext(transactions: Collection<Transaction>): LlmContext {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val cur = dominantBaseCurrency(transactions)
        val spend = transactions.filter { it.isSpend() && it.base.currency == cur }
        val monthly = spend.groupBy { YearMonth.from(it.localDate(zone)) }.toSortedMap().entries.toList().takeLast(6)
            .map { (ym, ts) -> AggregateFact("spend $ym", MoneyFormat.format(Money(ts.sumOf { it.baseAbs.minor }, cur))) }
        val byCat = categories.associateBy { it.id }
        val cats = spend.filter { YearMonth.from(it.localDate(zone)) == YearMonth.from(today) }
            .groupBy { it.categoryId?.let { id -> byCat[id]?.name } ?: "Uncategorised" }
            .map { (n, ts) -> AggregateFact("this month $n", MoneyFormat.format(Money(ts.sumOf { it.baseAbs.minor }, cur))) }
        val merchants = spend.groupBy { it.merchantKey() }.filterKeys { it != null }
            .entries.sortedByDescending { e -> e.value.size }.take(50)
            .map { e -> e.value.last().merchant ?: MerchantNormalizer.display(e.key!!) }
        return LlmContext(today, categories.map { it.name }, merchants, trips.map { it.name }, accounts.map { it.name }, monthly + cats)
    }
}

/** Minimal JSON reader (objects, arrays, strings, numbers, booleans, null). */
internal object MiniJson {
    fun parse(s: String): Any? {
        val p = P(s)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "Trailing characters at ${p.i}" }
        return v
    }

    private class P(val s: String) {
        var i = 0
        var depth = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unexpected '$c' at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "Bad literal at $i" }
            i += w.length; return v
        }
        fun num(): Number {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C'); '/' -> sb.append('/'); '\\' -> sb.append('\\'); '"' -> sb.append('"')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("Bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            require(++depth < 20) { "Too deep" }
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; depth--; return m }
            while (true) {
                ws()
                require(s[i] == '"') { "Expected key at $i" }
                val k = str()
                ws(); require(s[i] == ':') { "Expected ':' at $i" }; i++
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> { depth--; return m }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at ${i - 1}")
                }
            }
        }
        fun arr(): List<Any?> {
            require(++depth < 20) { "Too deep" }
            i++
            val l = mutableListOf<Any?>()
            ws()
            if (s[i] == ']') { i++; depth--; return l }
            while (true) {
                l += value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> { depth--; return l }
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
        }
    }
}
