package app.ledgerly.core.dedup

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.Id
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnType
import java.time.Duration

enum class MatchReason {
    SAME_SOURCE_EVENT, EXACT_REFERENCE, REFERENCE_MATCH_AMOUNT_MISMATCH, REFERENCE_CONFLICT,
    EXACT_TEXT_REDELIVERY, NOTIFICATION_UPDATE, SAME_CHANNEL_ALREADY_PRESENT,
    SAME_AMOUNT, SAME_ACCOUNT_AMOUNT, CROSS_CURRENCY_AMOUNT,
    SAME_ACCOUNT, SAME_LAST4, WITHIN_TIME_WINDOW,
    MERCHANT_SIMILAR, MERCHANT_DIFFERENT, MERCHANT_UNKNOWN, DIFFERENT_CHANNEL,
    AMBIGUOUS, AUTH_SETTLEMENT,
}

sealed interface MatchResult {
    /** No match: create a new transaction. */
    data object NewTransaction : MatchResult

    /** Same payment: merge the candidate into [txnId]. */
    data class MergeInto(val txnId: Id, val confidence: Double, val reasons: List<MatchReason>) : MatchResult

    /** Maybe the same payment: show a user-review card. */
    data class PossibleDuplicate(val txnId: Id, val confidence: Double, val reasons: List<MatchReason>) : MatchResult

    /** Candidate settles the pending card authorisation [txnId]: update it via [AuthSettlementLinker.apply]. */
    data class SettleAuth(val txnId: Id, val confidence: Double, val reasons: List<MatchReason>) : MatchResult
}

data class DedupConfig(
    /** Match window for real-time channels (SMS / notification). */
    val timeWindow: Duration = Duration.ofMinutes(15),
    /** Wider window when either side came only from EMAIL / IMPORT (statement) channels. */
    val wideWindow: Duration = Duration.ofDays(3),
    val wideChannels: Set<Channel> = setOf(Channel.EMAIL, Channel.IMPORT),
    /** Same channel + same text (or notification update) within this window = exact duplicate. */
    val redeliveryWindow: Duration = Duration.ofMinutes(5),
    /** Max distance for an exact reference (UPI ref / RRN / auth code) match. */
    val referenceWindow: Duration = Duration.ofDays(7),
    val mergeThreshold: Double = 0.75,
    val reviewThreshold: Double = 0.5,
    /** If the runner-up merge-level score is within this margin, downgrade to review. */
    val ambiguityMargin: Double = 0.05,
    val wAmount: Double = 0.35,
    val wAccount: Double = 0.20,
    val wTime: Double = 0.25,
    val wMerchant: Double = 0.20,
    val wMerchantUnknown: Double = 0.05,
    val wDifferentChannel: Double = 0.10,
    val penaltyMerchantDifferent: Double = 0.25,
    val penaltySameChannel: Double = 0.50,
    val penaltyReferenceConflict: Double = 0.30,
    val merchantSimilarAt: Double = 0.80,
    val merchantDifferentBelow: Double = 0.50,
)

/** Debit/credit of an existing transaction. The model has no direction field, so this is inferred by default. */
fun interface DirectionResolver {
    /** Null = unknown (compatible with both). */
    fun of(t: Transaction): TxnDirection?

    companion object {
        val DEFAULT = DirectionResolver { t ->
            when (t.type) {
                TxnType.INCOME, TxnType.REFUND -> TxnDirection.CREDIT
                TxnType.EXPENSE, TxnType.ATM_WITHDRAWAL -> TxnDirection.DEBIT
                TxnType.TRANSFER, TxnType.ADJUSTMENT -> null
            }
        }
    }
}

/**
 * "One payment, one entry": decides whether a [Candidate] is a new transaction, the same payment as an
 * existing one (merge), or possibly the same (review card).
 *
 * @param sourceText raw text of a stored source event (for exact-duplicate detection).
 * @param sourcePackage notification package of a stored source event (for notification updates).
 */
class DedupEngine(
    val config: DedupConfig = DedupConfig(),
    private val sourceText: (Id) -> String? = { null },
    private val sourcePackage: (Id) -> String? = { null },
    private val directions: DirectionResolver = DirectionResolver.DEFAULT,
    private val authLinker: AuthSettlementLinker? = AuthSettlementLinker(),
) {
    data class Scored(val txn: Transaction, val score: Double, val reasons: List<MatchReason>, val distance: Duration)

    fun match(candidate: Candidate, existing: List<Transaction>): MatchResult {
        val live = existing.filter { !it.isDeleted }

        live.firstOrNull { candidate.sourceEventId in it.sourceEventIds }?.let {
            return MatchResult.MergeInto(it.id, 1.0, listOf(MatchReason.SAME_SOURCE_EVENT))
        }

        val ref = Similarity.normaliseReference(candidate.reference)
        if (ref != null) {
            val byRef = live.filter {
                Similarity.normaliseReference(it.reference) == ref &&
                    dist(candidate, it) <= config.referenceWindow && directionCompatible(candidate, it)
            }
            byRef.firstOrNull { amountMatch(candidate, it) != null }?.let {
                return MatchResult.MergeInto(it.id, 0.99, listOfNotNull(MatchReason.EXACT_REFERENCE, amountMatch(candidate, it)))
            }
            byRef.firstOrNull()?.let {
                // Same reference but different amount: could be an auth->settlement.
                authLinker?.find(candidate, listOf(it))?.let { m ->
                    return MatchResult.SettleAuth(m.txnId, m.confidence, m.reasons)
                }
                return MatchResult.PossibleDuplicate(it.id, 0.6, listOf(MatchReason.REFERENCE_MATCH_AMOUNT_MISMATCH))
            }
        }

        authLinker?.let { linker ->
            if (linker.isSettlement(candidate)) {
                linker.find(candidate, live)?.let { return MatchResult.SettleAuth(it.txnId, it.confidence, it.reasons) }
            }
        }

        val scored = live.mapNotNull { score(candidate, it) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.distance })
        val best = scored.firstOrNull() ?: return MatchResult.NewTransaction
        val conf = best.score.coerceIn(0.0, 1.0)
        if (best.reasons.firstOrNull() == MatchReason.EXACT_TEXT_REDELIVERY ||
            best.reasons.firstOrNull() == MatchReason.NOTIFICATION_UPDATE
        ) return MatchResult.MergeInto(best.txn.id, conf, best.reasons)
        return when {
            conf >= config.mergeThreshold -> {
                val runnerUp = scored.getOrNull(1)
                if (runnerUp != null && runnerUp.score >= config.mergeThreshold &&
                    best.score - runnerUp.score < config.ambiguityMargin
                ) MatchResult.PossibleDuplicate(best.txn.id, conf, best.reasons + MatchReason.AMBIGUOUS)
                else MatchResult.MergeInto(best.txn.id, conf, best.reasons)
            }
            conf >= config.reviewThreshold -> MatchResult.PossibleDuplicate(best.txn.id, conf, best.reasons)
            else -> MatchResult.NewTransaction
        }
    }

    /** Score [c] against one existing transaction; null when it cannot be the same payment. */
    fun score(c: Candidate, t: Transaction): Scored? {
        if (!directionCompatible(c, t)) return null
        if (c.accountId != null && t.accountId != null && c.accountId != t.accountId) return null
        if (c.accountLast4 != null && t.accountLast4 != null && c.accountLast4 != t.accountLast4) return null
        val amountReason = amountMatch(c, t) ?: return null

        val d = dist(c, t)
        val wide = c.channel in config.wideChannels ||
            (t.sourceChannels.isNotEmpty() && t.sourceChannels.all { it in config.wideChannels })
        val window = if (wide) config.wideWindow else config.timeWindow
        if (d > window) return null

        val reasons = ArrayList<MatchReason>()
        var s = 0.0
        val sim = if (c.merchantRaw != null && (t.merchantRaw != null || t.merchant != null)) {
            maxOf(Similarity.merchant(c.merchantRaw, t.merchantRaw), Similarity.merchant(c.merchantRaw, t.merchant))
        } else null

        if (c.channel in t.sourceChannels) {
            val text = Similarity.normaliseText(c.rawText)
            if (d <= config.redeliveryWindow) {
                if (text != null && t.sourceEventIds.any { Similarity.normaliseText(sourceText(it)) == text }) {
                    return Scored(t, 1.0, listOf(MatchReason.EXACT_TEXT_REDELIVERY, amountReason), d)
                }
                if (c.channel == Channel.NOTIFICATION && (sim == null || sim >= 0.9)) {
                    val pkgs = t.sourceEventIds.mapNotNull(sourcePackage).toSet()
                    if (c.packageName == null || pkgs.isEmpty() || c.packageName in pkgs) {
                        return Scored(t, 0.9, listOf(MatchReason.NOTIFICATION_UPDATE, amountReason), d)
                    }
                }
            }
            s -= config.penaltySameChannel; reasons += MatchReason.SAME_CHANNEL_ALREADY_PRESENT
        } else {
            s += config.wDifferentChannel; reasons += MatchReason.DIFFERENT_CHANNEL
        }

        s += config.wAmount; reasons += amountReason

        when {
            c.accountId != null && c.accountId == t.accountId -> { s += config.wAccount; reasons += MatchReason.SAME_ACCOUNT }
            c.accountLast4 != null && c.accountLast4 == t.accountLast4 -> { s += config.wAccount; reasons += MatchReason.SAME_LAST4 }
        }

        val frac = if (window.isZero) 1.0 else d.toMillis().toDouble() / window.toMillis()
        s += config.wTime * (1.0 - frac); reasons += MatchReason.WITHIN_TIME_WINDOW

        when {
            sim == null -> { s += config.wMerchantUnknown; reasons += MatchReason.MERCHANT_UNKNOWN }
            sim < config.merchantDifferentBelow -> { s -= config.penaltyMerchantDifferent; reasons += MatchReason.MERCHANT_DIFFERENT }
            else -> { s += config.wMerchant * sim; if (sim >= config.merchantSimilarAt) reasons += MatchReason.MERCHANT_SIMILAR }
        }

        val rc = Similarity.normaliseReference(c.reference)
        val rt = Similarity.normaliseReference(t.reference)
        if (rc != null && rt != null && rc != rt) { s -= config.penaltyReferenceConflict; reasons += MatchReason.REFERENCE_CONFLICT }

        return Scored(t, s, reasons, d)
    }

    private fun dist(c: Candidate, t: Transaction): Duration = Duration.between(c.occurredAt, t.occurredAt).abs()

    private fun directionCompatible(c: Candidate, t: Transaction): Boolean {
        val dt = directions.of(t) ?: return true
        val dc = if (c.isCredit) TxnDirection.CREDIT else (c.direction ?: TxnDirection.DEBIT)
        return dt == dc
    }

    /** How the amounts line up, or null if they don't. Compares absolute values. */
    internal fun amountMatch(c: Candidate, t: Transaction): MatchReason? {
        val ca = c.amount.abs(); val caa = c.accountAmount?.abs()
        val to = t.original.abs(); val ta = t.account.abs()
        return when {
            ca == to -> MatchReason.SAME_AMOUNT
            caa != null && caa.currency == ta.currency && caa == ta -> MatchReason.SAME_ACCOUNT_AMOUNT
            ca.currency == ta.currency && ca == ta -> MatchReason.CROSS_CURRENCY_AMOUNT
            caa != null && caa.currency == to.currency && caa == to -> MatchReason.CROSS_CURRENCY_AMOUNT
            else -> null
        }
    }
}
