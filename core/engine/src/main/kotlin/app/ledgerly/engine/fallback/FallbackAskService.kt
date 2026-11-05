package app.ledgerly.engine.fallback

import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType
import app.ledgerly.engine.AskAnswer
import app.ledgerly.engine.AskContext
import app.ledgerly.engine.AskService
import app.ledgerly.engine.FinancialPeriod
import app.ledgerly.engine.LlmBackend
import app.ledgerly.util.MoneyFormat
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Keyword-driven "chat with your money" until the core:insights NL engine is wired
 * (TODO(core-insights)). Answers are computed locally; when Gemini Nano is available it is used only
 * to phrase an answer from locally computed figures, never to see raw messages.
 */
class FallbackAskService(private val llm: LlmBackend) : AskService {

    private data class Range(val label: String, val start: LocalDate, val end: LocalDate)

    override suspend fun ask(question: String, context: AskContext): AskAnswer {
        val q = question.lowercase().trim()
        val range = rangeFor(q, context)
        val inRange = context.transactions.filter {
            !it.isDeleted && it.base.currency == context.baseCurrency &&
                it.occurredAt.atZone(context.zone).toLocalDate().let { d -> !d.isBefore(range.start) && d.isBefore(range.end) }
        }
        val expenses = inRange.filter { it.type == TxnType.EXPENSE || it.type == TxnType.ATM_WITHDRAWAL }
        val zero = Money.zero(context.baseCurrency)
        fun sum(list: List<Transaction>) = list.fold(zero) { acc, t -> acc + t.base }

        // "how much did I spend on food / at zomato"
        Regex("(?:on|at|for)\\s+([a-z0-9&' .-]+?)(?:\\s+(?:this|last|in|today|yesterday)\\b|\\?|$)").find(q)?.let { m ->
            val term = m.groupValues[1].trim()
            if (term.isNotEmpty() && (q.contains("spend") || q.contains("spent") || q.contains("how much"))) {
                val catIds = context.categories.filter { it.name.lowercase().contains(term) || it.id.substringBefore('.') == term }
                    .flatMap { c -> listOf(c.id) + context.categories.filter { it.parentId == c.id }.map { it.id } }.toSet()
                val matches = expenses.filter { t ->
                    (t.categoryId != null && t.categoryId in catIds) ||
                        (t.merchant ?: t.merchantRaw ?: "").lowercase().contains(term)
                }
                val total = sum(matches)
                return AskAnswer(
                    "You spent ${MoneyFormat.format(total)} on \"$term\" ${range.label} across ${matches.size} transaction${if (matches.size == 1) "" else "s"}.",
                    matches.map { it.id },
                )
            }
        }
        if (q.contains("biggest") || q.contains("largest") || q.contains("top expense")) {
            val top = expenses.sortedByDescending { it.base.minor }.take(5)
            if (top.isEmpty()) return AskAnswer("No expenses ${range.label}.")
            val lines = top.joinToString("\n") { "• ${MoneyFormat.format(it.base)} ${it.merchant ?: it.merchantRaw ?: "Unknown"}" }
            return AskAnswer("Your largest expenses ${range.label}:\n$lines", top.map { it.id })
        }
        if (q.contains("income") || q.contains("earn")) {
            val income = inRange.filter { it.type == TxnType.INCOME }
            return AskAnswer("Income ${range.label}: ${MoneyFormat.format(sum(income))}.", income.map { it.id })
        }
        if (q.contains("spend") || q.contains("spent") || q.contains("total")) {
            return AskAnswer("You spent ${MoneyFormat.format(sum(expenses))} ${range.label} (${expenses.size} transactions).", expenses.map { it.id })
        }

        // Unknown question: optionally let the on-device model phrase an answer over local aggregates.
        if (llm.isAvailable()) {
            val byCategory = expenses.groupBy { it.categoryId ?: "uncategorised" }
                .mapValues { (_, v) -> sum(v) }.entries.sortedByDescending { it.value.minor }.take(10)
            val facts = byCategory.joinToString("\n") { (k, v) -> "$k: ${v.toDecimal().toPlainString()} ${v.currency}" }
            val prompt = "You are a personal finance assistant. Using ONLY these facts about spending ${range.label}:\n$facts\n" +
                "Answer briefly: $question"
            llm.generate(prompt)?.let { return AskAnswer(it.trim(), usedLlm = true) }
        }
        return AskAnswer(
            "I can answer questions like:\n• How much did I spend on food this month?\n• How much at Zomato last month?\n" +
                "• What were my biggest expenses this week?\n• What was my income this month?",
        )
    }

    private fun rangeFor(q: String, ctx: AskContext): Range {
        val today = ctx.today
        val period = FinancialPeriod.current(today, ctx.monthStartDay)
        return when {
            q.contains("last month") -> period.previous().let { Range("last month", it.start, it.end) }
            q.contains("this week") -> today.with(DayOfWeek.MONDAY).let { Range("this week", it, it.plusDays(7)) }
            q.contains("last week") -> today.with(DayOfWeek.MONDAY).minusDays(7).let { Range("last week", it, it.plusDays(7)) }
            q.contains("yesterday") -> Range("yesterday", today.minusDays(1), today)
            q.contains("today") -> Range("today", today, today.plusDays(1))
            q.contains("this year") -> Range("this year", today.withDayOfYear(1), today.withDayOfYear(1).plusYears(1))
            q.contains("last year") -> today.withDayOfYear(1).minusYears(1).let { Range("last year", it, it.plusYears(1)) }
            else -> Range("this month", period.start, period.end)
        }
    }
}
