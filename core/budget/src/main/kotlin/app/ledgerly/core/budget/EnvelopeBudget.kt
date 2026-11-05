package app.ledgerly.core.budget

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Money

data class Envelope(val id: Id, val name: String, val categoryIds: Set<Id>, val target: Money? = null)

/** Money movement between envelopes; null side = "to be assigned". */
data class EnvelopeMove(val from: Id?, val to: Id?, val amount: Money, val note: String? = null)

data class EnvelopeStatus(
    val envelope: Envelope,
    val carried: Money,
    val assigned: Money,
    val activity: Money,
    val available: Money,
    /** % of target funded (carried + assigned), null without target. */
    val targetFundedPercent: Double?,
) {
    val isOverspent: Boolean get() = available.isNegative
}

/**
 * Zero-based / envelope budget for one period, as an immutable value: every operation returns a new state.
 * "To be assigned" = income + carried TBA − assigned. Every rupee should get a job (TBA → 0).
 */
data class EnvelopeBudget(
    val currency: CurrencyCode,
    val envelopes: List<Envelope>,
    val income: Money = Money.zero(currency),
    val assigned: Map<Id, Money> = emptyMap(),
    val carried: Map<Id, Money> = emptyMap(),
    val activity: Map<Id, Money> = emptyMap(),
    /** TBA carried from the previous period (negative after uncovered overspending). */
    val toBeAssignedCarry: Money = Money.zero(currency),
    val moves: List<EnvelopeMove> = emptyList(),
    /** Spend in categories not mapped to any envelope (should be surfaced to the user). */
    val unmappedActivity: Money = Money.zero(currency),
) {
    init {
        require(envelopes.map { it.id }.toSet().size == envelopes.size) { "Duplicate envelope ids" }
        val cats = envelopes.flatMap { it.categoryIds }
        require(cats.toSet().size == cats.size) { "A category can belong to only one envelope" }
    }

    private val zero get() = Money.zero(currency)
    private fun get(m: Map<Id, Money>, id: Id) = m[id] ?: zero
    private fun envelope(id: Id) = envelopes.firstOrNull { it.id == id } ?: throw NoSuchElementException("No envelope $id")

    val toBeAssigned: Money get() = income + toBeAssignedCarry - MoneyMath.sum(assigned.values, currency)

    fun available(id: Id): Money { envelope(id); return get(carried, id) + get(assigned, id) - get(activity, id) }

    fun status(id: Id): EnvelopeStatus {
        val e = envelope(id)
        val funded = get(carried, id) + get(assigned, id)
        return EnvelopeStatus(e, get(carried, id), get(assigned, id), get(activity, id), available(id),
            e.target?.let { MoneyMath.percent(funded, it) })
    }

    fun statuses(): List<EnvelopeStatus> = envelopes.map { status(it.id) }

    fun overspent(): List<EnvelopeStatus> = statuses().filter { it.isOverspent }

    fun addIncome(amount: Money): EnvelopeBudget {
        require(amount.currency == currency && amount.isPositive) { "Income must be positive $currency" }
        return copy(income = income + amount)
    }

    /** Assign from TBA (negative amount un-assigns back to TBA). Cannot assign more than TBA unless [allowNegativeTba]. */
    fun assign(id: Id, amount: Money, allowNegativeTba: Boolean = false): EnvelopeBudget {
        envelope(id); require(amount.currency == currency)
        if (amount.isPositive && !allowNegativeTba) require(amount <= toBeAssigned) { "Only ${MoneyMath.human(toBeAssigned)} left to assign" }
        if (amount.isNegative) require(-amount <= available(id)) { "Cannot unassign more than is available" }
        return copy(
            assigned = assigned + (id to get(assigned, id) + amount),
            moves = moves + if (amount.isPositive) EnvelopeMove(null, id, amount) else EnvelopeMove(id, null, -amount),
        )
    }

    /** Move available money between envelopes. The source must have at least [amount] available. */
    fun move(from: Id, to: Id, amount: Money, note: String? = null): EnvelopeBudget {
        require(from != to) { "Same envelope" }
        envelope(to); require(amount.currency == currency && amount.isPositive) { "Amount must be positive" }
        require(available(from) >= amount) { "${envelope(from).name} has only ${MoneyMath.human(available(from))} available" }
        return copy(
            assigned = assigned + (from to get(assigned, from) - amount) + (to to get(assigned, to) + amount),
            moves = moves + EnvelopeMove(from, to, amount, note),
        )
    }

    /** Cover an overspent envelope from another one (defaults to the full overspend, capped by what the source has). */
    fun coverOverspend(overspentId: Id, fromId: Id, amount: Money? = null): EnvelopeBudget {
        val deficit = -available(overspentId)
        require(deficit.isPositive) { "${envelope(overspentId).name} is not overspent" }
        val amt = amount ?: MoneyMath.min(deficit, available(fromId))
        return move(fromId, overspentId, amt, "cover overspend")
    }

    /**
     * Set this period's activity from net spend per category. A category maps to the envelope holding it or its
     * nearest ancestor ([parentOf]); anything else goes to [unmappedActivity].
     */
    fun withActivity(spendByCategory: Map<Id?, Money>, parentOf: (Id) -> Id? = { null }): EnvelopeBudget {
        val catToEnv = envelopes.flatMap { e -> e.categoryIds.map { it to e.id } }.toMap()
        val act = HashMap<Id, Money>()
        var unmapped = zero
        for ((cat, amt) in spendByCategory) {
            var c = cat; var env: Id? = null; val seen = HashSet<Id>()
            while (c != null && env == null && seen.add(c)) { env = catToEnv[c]; if (env == null) c = parentOf(c) }
            if (env == null) unmapped += amt else act[env] = (act[env] ?: zero) + amt
        }
        return copy(activity = act, unmappedActivity = unmapped)
    }

    /**
     * Start the next period: positive balances carry over in their envelope; overspending (negative balances)
     * is taken out of next period's TBA, as is any unmapped spend.
     */
    fun rollover(): EnvelopeBudget {
        val st = statuses()
        val deficits = MoneyMath.sum(st.filter { it.isOverspent }.map { -it.available }, currency)
        return EnvelopeBudget(
            currency = currency,
            envelopes = envelopes,
            carried = st.filter { it.available.isPositive }.associate { it.envelope.id to it.available },
            toBeAssignedCarry = toBeAssigned - deficits - unmappedActivity,
        )
    }

    companion object {
        fun of(currency: CurrencyCode, vararg envelopes: Envelope) = EnvelopeBudget(currency, envelopes.toList())
    }
}
