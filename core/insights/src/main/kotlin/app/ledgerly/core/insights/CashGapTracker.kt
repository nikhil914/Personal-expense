package app.ledgerly.core.insights

import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class CashGapConfig(
    /** Days after the withdrawal on which to prompt. */
    val promptDays: List<Int> = listOf(1, 3, 7),
    /** Unaccounted amount (minor units) at or below which the pool counts as explained. */
    val minUnaccountedMinor: Long = 10_000,
    /** Tags that mark an expense as paid in cash. */
    val cashTags: Set<String> = setOf("cash"),
    val snoozeDuration: Duration = Duration.ofDays(1),
    /** Stop prompting for withdrawals older than this. */
    val maxAgeDays: Long = 30,
)

/** A cash pool created by one ATM withdrawal, drawn down FIFO by cash expenses in the same currency. */
data class CashPool(
    val withdrawalId: Id,
    val withdrawnAt: Instant,
    val withdrawnOn: LocalDate,
    val amount: Money,
    val spent: Money,
    val markedMisc: Boolean,
    val spentTransactionIds: List<Id>,
) {
    val unaccounted: Money get() = if (markedMisc) Money.zero(amount.currency) else (amount - spent).let { if (it.isNegative) Money.zero(it.currency) else it }
}

enum class CashGapAction { LOG_EXPENSE, SNOOZE, DISMISS, MARK_MISC_CASH }

data class CashGapPrompt(
    val withdrawalId: Id,
    val withdrawnOn: LocalDate,
    val withdrawn: Money,
    val unaccounted: Money,
    /** The prompt stage (one of [CashGapConfig.promptDays]). */
    val stageDay: Int,
    val message: String,
    val actions: List<CashGapAction> = CashGapAction.entries,
)

/** Persistable user-interaction state (owned by the app; immutable updates). */
data class CashGapState(
    val snoozedUntil: Map<Id, Instant> = emptyMap(),
    val dismissed: Set<Id> = emptySet(),
    val markedMisc: Set<Id> = emptySet(),
    /** Stages already shown per withdrawal. */
    val shownStages: Map<Id, Set<Int>> = emptyMap(),
)

/**
 * Tracks cash from ATM withdrawals and prompts the user to log unaccounted cash at configurable intervals.
 * @param accountTypes account id -> type; expenses on CASH accounts (or tagged cash) draw pools down.
 */
class CashGapTracker(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val accountTypes: Map<Id, AccountType> = emptyMap(),
    private val config: CashGapConfig = CashGapConfig(),
) {
    private val zone get() = clock.zone

    fun isCashExpense(t: Transaction): Boolean =
        t.isActive() && t.type == TxnType.EXPENSE &&
            ((t.accountId != null && accountTypes[t.accountId] == AccountType.CASH) ||
                t.tags.any { tag -> config.cashTags.any { it.equals(tag, ignoreCase = true) } })

    fun pools(transactions: Collection<Transaction>, state: CashGapState = CashGapState()): List<CashPool> {
        val now = clock.instant()
        val withdrawals = transactions.filter { it.isActive() && it.type == TxnType.ATM_WITHDRAWAL && !it.occurredAt.isAfter(now) }
            .sortedBy { it.occurredAt }
        val remaining = withdrawals.associate { it.id to it.original.abs().minor }.toMutableMap()
        val spentBy = withdrawals.associate { it.id to mutableListOf<Id>() }
        val spentMinor = withdrawals.associate { it.id to 0L }.toMutableMap()
        val expenses = transactions.filter { isCashExpense(it) && !it.occurredAt.isAfter(now) }.sortedBy { it.occurredAt }
        for (e in expenses) {
            var need = e.original.abs().minor
            for (w in withdrawals) {
                if (need == 0L) break
                if (w.occurredAt.isAfter(e.occurredAt) || w.original.currency != e.original.currency) continue
                if (w.id in state.markedMisc) continue
                val avail = remaining.getValue(w.id)
                if (avail <= 0) continue
                val take = minOf(avail, need)
                remaining[w.id] = avail - take
                spentMinor[w.id] = spentMinor.getValue(w.id) + take
                spentBy.getValue(w.id) += e.id
                need -= take
            }
        }
        return withdrawals.map { w ->
            CashPool(
                w.id, w.occurredAt, w.localDate(zone), w.original.abs(),
                Money(spentMinor.getValue(w.id), w.original.currency), w.id in state.markedMisc, spentBy.getValue(w.id),
            )
        }
    }

    /** Prompts due right now. Call [markShown] after displaying one. */
    fun prompts(transactions: Collection<Transaction>, state: CashGapState = CashGapState()): List<CashGapPrompt> {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val stages = config.promptDays.sorted()
        return pools(transactions, state).mapNotNull { p ->
            if (p.withdrawalId in state.dismissed || p.markedMisc) return@mapNotNull null
            if (p.unaccounted.minor <= config.minUnaccountedMinor) return@mapNotNull null
            val age = ChronoUnit.DAYS.between(p.withdrawnOn, today)
            if (age > config.maxAgeDays) return@mapNotNull null
            state.snoozedUntil[p.withdrawalId]?.let { if (now.isBefore(it)) return@mapNotNull null }
            val shown = state.shownStages[p.withdrawalId].orEmpty()
            val stage = stages.lastOrNull { it <= age } ?: return@mapNotNull null
            if (stage in shown) {
                // A snooze that expired re-surfaces the current stage.
                val snoozeExpired = state.snoozedUntil[p.withdrawalId]?.let { !now.isBefore(it) } ?: false
                if (!snoozeExpired) return@mapNotNull null
            }
            CashGapPrompt(
                p.withdrawalId, p.withdrawnOn, p.amount, p.unaccounted, stage,
                "You withdrew ${MoneyFormat.format(p.amount)} on ${p.withdrawnOn.shortLabel()}. " +
                    "${MoneyFormat.format(p.unaccounted)} is unaccounted for. Log it?",
            )
        }
    }

    /** Record that a prompt was shown: marks its stage and all earlier stages as done. */
    fun markShown(state: CashGapState, prompt: CashGapPrompt): CashGapState {
        val done = config.promptDays.filter { it <= prompt.stageDay }.toSet()
        return state.copy(
            shownStages = state.shownStages + (prompt.withdrawalId to (state.shownStages[prompt.withdrawalId].orEmpty() + done)),
            snoozedUntil = state.snoozedUntil - prompt.withdrawalId,
        )
    }

    fun snooze(state: CashGapState, withdrawalId: Id, duration: Duration = config.snoozeDuration): CashGapState =
        state.copy(snoozedUntil = state.snoozedUntil + (withdrawalId to clock.instant().plus(duration)))

    fun dismiss(state: CashGapState, withdrawalId: Id): CashGapState =
        state.copy(dismissed = state.dismissed + withdrawalId)

    /** "Mark as misc cash": the remainder is treated as spent on miscellaneous items; no further prompts. */
    fun markMiscCash(state: CashGapState, withdrawalId: Id): CashGapState =
        state.copy(markedMisc = state.markedMisc + withdrawalId)

    /** Apply a user action from a prompt (LOG_EXPENSE leaves state unchanged; the logged expense explains the gap). */
    fun apply(state: CashGapState, prompt: CashGapPrompt, action: CashGapAction): CashGapState = when (action) {
        CashGapAction.LOG_EXPENSE -> markShown(state, prompt)
        CashGapAction.SNOOZE -> snooze(markShown(state, prompt), prompt.withdrawalId)
        CashGapAction.DISMISS -> dismiss(state, prompt.withdrawalId)
        CashGapAction.MARK_MISC_CASH -> markMiscCash(state, prompt.withdrawalId)
    }
}
