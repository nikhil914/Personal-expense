package app.ledgerly.engine

import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType
import app.ledgerly.data.db.LedgerlyDatabase
import app.ledgerly.data.db.RuleEntity
import app.ledgerly.data.db.RuleField
import app.ledgerly.data.mapper.SetCodec

/**
 * Categorisation order: user rules (by priority) -> known merchant table -> type default.
 * Rules are created from user corrections ("Always categorise Zomato as Food").
 */
class RuleBasedCategoriser(private val db: LedgerlyDatabase) : Categoriser {

    override suspend fun categorise(txn: Transaction, event: SourceEvent?): CategoryResult? {
        val merchantText = listOfNotNull(txn.merchant, txn.merchantRaw).joinToString(" ").lowercase()
        for (rule in db.ruleDao().enabledByPriority()) {
            val subject = when (rule.field) {
                RuleField.MERCHANT -> merchantText
                RuleField.SENDER -> listOfNotNull(event?.sender, event?.packageName).joinToString(" ").lowercase()
                RuleField.TEXT -> listOfNotNull(event?.title, event?.body).joinToString(" ").lowercase()
            }
            if (subject.isNotEmpty() && matches(rule, subject)) {
                val categoryId = rule.categoryId ?: continue
                return CategoryResult(categoryId, rule.merchantName, SetCodec.decode(rule.addTags).toSet(), rule.id, "rule")
            }
        }
        if (merchantText.isNotEmpty()) {
            for (m in db.merchantDao().getAll()) {
                val aliases = SetCodec.decode(m.aliases) + m.name.lowercase()
                if (aliases.any { it.isNotEmpty() && merchantText.contains(it) }) {
                    val categoryId = m.defaultCategoryId ?: continue
                    return CategoryResult(categoryId, merchantName = m.name, source = "merchant")
                }
            }
        }
        val byType = when (txn.type) {
            TxnType.ATM_WITHDRAWAL -> "cash.atm"
            TxnType.INCOME -> "income.other"
            TxnType.REFUND -> "income.refund"
            TxnType.TRANSFER -> "transfer"
            else -> null
        }
        return byType?.let { CategoryResult(it, source = "type-default") }
    }

    private fun matches(rule: RuleEntity, subject: String): Boolean =
        if (rule.isRegex) runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(subject) }.getOrDefault(false)
        else subject.contains(rule.pattern.lowercase())
}
