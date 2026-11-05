package app.ledgerly.core.insights

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType

/** A condition on a transaction. All conditions of a rule must match. */
sealed class RuleCondition {
    abstract fun matches(t: Transaction): Boolean

    data class MerchantContains(val text: String) : RuleCondition() {
        override fun matches(t: Transaction): Boolean =
            listOfNotNull(t.merchant, t.merchantRaw).any { it.contains(text, ignoreCase = true) }
    }

    data class MerchantRegex(val pattern: String) : RuleCondition() {
        private val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        override fun matches(t: Transaction): Boolean = listOfNotNull(t.merchant, t.merchantRaw).any { regex.containsMatchIn(it) }
    }

    /** Inclusive range on the absolute base amount; bounds must share the transaction's base currency. */
    data class AmountRange(val min: Money? = null, val max: Money? = null) : RuleCondition() {
        override fun matches(t: Transaction): Boolean {
            val a = t.baseAbs
            if (min != null && (min.currency != a.currency || a.minor < min.minor)) return false
            if (max != null && (max.currency != a.currency || a.minor > max.minor)) return false
            return true
        }
    }

    data class AccountIs(val accountIds: Set<Id>) : RuleCondition() {
        override fun matches(t: Transaction): Boolean = t.accountId in accountIds
    }

    data class ChannelIs(val channels: Set<Channel>) : RuleCondition() {
        override fun matches(t: Transaction): Boolean = t.sourceChannels.any { it in channels }
    }

    data class DirectionIs(val flow: Flow) : RuleCondition() {
        override fun matches(t: Transaction): Boolean = t.flow() == flow
    }

    data class TypeIs(val types: Set<TxnType>) : RuleCondition() {
        override fun matches(t: Transaction): Boolean = t.type in types
    }

    data class NoteContains(val text: String) : RuleCondition() {
        override fun matches(t: Transaction): Boolean = t.note?.contains(text, ignoreCase = true) == true
    }
}

data class CategoryRule(
    val id: Id,
    val name: String,
    /** Higher runs first. */
    val priority: Int = 0,
    val conditions: List<RuleCondition>,
    val setCategoryId: Id? = null,
    val addTags: Set<String> = emptySet(),
    val enabled: Boolean = true,
    /** Stop evaluating lower-priority rules after this one matches. */
    val stopProcessing: Boolean = true,
    val confidence: Double = 0.95,
) {
    fun matches(t: Transaction): Boolean = enabled && conditions.isNotEmpty() && conditions.all { it.matches(t) }
}

enum class SuggestionSource { RULE, LEARNED, NONE }

data class CategorySuggestion(
    val categoryId: Id?,
    val tags: Set<String>,
    val confidence: Double,
    val source: SuggestionSource,
    val ruleIds: List<Id> = emptyList(),
    val needsReview: Boolean,
)

data class CategorizerConfig(
    /** Below this confidence a suggestion is flagged "needs review". */
    val reviewThreshold: Double = 0.6,
    /** Learned mapping overrides a rule when its share and observations reach these. */
    val overrideShare: Double = 0.8,
    val overrideMinObservations: Int = 3,
)

/**
 * Rules engine + learning from user corrections (merchant -> category frequency table).
 * Thread-safe for concurrent [categorize]/[learn].
 */
class Categorizer(
    rules: List<CategoryRule> = emptyList(),
    private val config: CategorizerConfig = CategorizerConfig(),
) {
    private val rules = rules.sortedByDescending { it.priority }
    private val table = HashMap<String, HashMap<Id, Int>>()
    private val lock = Any()

    fun categorize(t: Transaction): CategorySuggestion {
        var category: Id? = null
        var ruleConf = 0.0
        val tags = linkedSetOf<String>()
        val fired = mutableListOf<Id>()
        for (r in rules) {
            if (!r.matches(t)) continue
            fired += r.id
            tags += r.addTags
            if (category == null && r.setCategoryId != null) { category = r.setCategoryId; ruleConf = r.confidence }
            if (r.stopProcessing) break
        }
        val learned = learnedFor(t)
        if (category != null) {
            if (learned != null && learned.first != category && learned.third >= config.overrideMinObservations && learned.second >= config.overrideShare) {
                val conf = confidenceOf(learned.second, learned.third)
                return CategorySuggestion(learned.first, tags, conf, SuggestionSource.LEARNED, fired, conf < config.reviewThreshold)
            }
            return CategorySuggestion(category, tags, ruleConf, SuggestionSource.RULE, fired, ruleConf < config.reviewThreshold)
        }
        if (learned != null) {
            val conf = confidenceOf(learned.second, learned.third)
            return CategorySuggestion(learned.first, tags, conf, SuggestionSource.LEARNED, fired, conf < config.reviewThreshold)
        }
        return CategorySuggestion(null, tags, 0.0, if (fired.isEmpty()) SuggestionSource.NONE else SuggestionSource.RULE, fired, true)
    }

    /** Confidence = share x n/(n+1): one observation gives 0.5, three consistent ones 0.75. */
    private fun confidenceOf(share: Double, n: Int): Double = share * n / (n + 1.0)

    /** (category, share, total observations) for the transaction's merchant. */
    private fun learnedFor(t: Transaction): Triple<Id, Double, Int>? {
        val key = t.merchantKey() ?: return null
        synchronized(lock) {
            val counts = table[key] ?: return null
            val total = counts.values.sum()
            if (total == 0) return null
            val best = counts.maxWith(compareBy<Map.Entry<Id, Int>> { it.value }.thenBy { it.key })
            return Triple(best.key, best.value.toDouble() / total, total)
        }
    }

    /** Records a user correction / confirmation. */
    fun learn(t: Transaction, categoryId: Id) {
        val key = t.merchantKey() ?: return
        learn(key, categoryId)
    }

    fun learn(merchantKey: String, categoryId: Id, weight: Int = 1) {
        synchronized(lock) { table.getOrPut(merchantKey) { HashMap() }.merge(categoryId, weight, Int::plus) }
    }

    /** Bootstrap from already-categorised history. */
    fun learnFrom(history: Collection<Transaction>) {
        for (t in history) { val c = t.categoryId; if (c != null && t.isActive()) learn(t, c) }
    }

    /** Returns [t] with category (if unset) and tags applied. */
    fun apply(t: Transaction, overwrite: Boolean = false): Transaction {
        val s = categorize(t)
        val cat = if (t.categoryId == null || overwrite) s.categoryId ?: t.categoryId else t.categoryId
        return t.copy(categoryId = cat, tags = t.tags + s.tags)
    }

    /** Transactions whose suggestion is below the review threshold (the "needs review" export). */
    fun needsReview(transactions: Collection<Transaction>): List<Pair<Transaction, CategorySuggestion>> =
        transactions.filter { it.isActive() && it.categoryId == null }.map { it to categorize(it) }.filter { it.second.needsReview }

    fun exportLearned(): Map<String, Map<Id, Int>> = synchronized(lock) { table.mapValues { it.value.toMap() } }

    fun importLearned(data: Map<String, Map<Id, Int>>) {
        synchronized(lock) { data.forEach { (k, v) -> v.forEach { (c, n) -> table.getOrPut(k) { HashMap() }.merge(c, n, Int::plus) } } }
    }
}
