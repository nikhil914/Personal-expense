package app.ledgerly.core.parser

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.TxnDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What to do with financial-looking messages from senders/apps that are not allow-listed. */
enum class UnknownSenderPolicy {
    /** Drop as IGNORED_NOT_FINANCIAL. */
    IGNORE,
    /** Parse, but cap confidence so the result lands in the review queue. */
    REVIEW,
}

/** Tunables for [TransactionParser]. */
data class ParserConfig(
    val defaultCurrency: CurrencyCode = CurrencyCode.INR,
    val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    /** Currency for a bare "$". */
    val dollarCurrency: CurrencyCode = CurrencyCode.USD,
    val dayFirstDates: Boolean = true,
    /** Results at or above this confidence are PARSED; below are NEEDS_REVIEW. */
    val parsedThreshold: Double = 0.75,
    /** The LLM fallback runs when the best deterministic result is below this confidence. */
    val llmThreshold: Double = 0.75,
    val unknownSmsSenderPolicy: UnknownSenderPolicy = UnknownSenderPolicy.REVIEW,
    val unknownPackagePolicy: UnknownSenderPolicy = UnknownSenderPolicy.IGNORE,
    /** Confidence cap applied under [UnknownSenderPolicy.REVIEW]. */
    val unknownSenderConfidenceCap: Double = 0.6,
)

/** Classifier verdict. */
data class Classification(
    val kind: MessageKind,
    val direction: TxnDirection?,
    /** 0..1: how sure the classifier is about [kind]. */
    val confidence: Double,
    /** Short machine-readable reasons, useful for debugging and tests. */
    val signals: List<String> = emptyList(),
) {
    val isIgnored: Boolean get() = kind == MessageKind.OTP || kind == MessageKind.PROMO
}

/** Fields extracted by any stage (template, LLM, heuristic). */
data class Extraction(
    val kind: MessageKind,
    val direction: TxnDirection?,
    val amount: Money?,
    val accountAmount: Money? = null,
    val accountLast4: String? = null,
    val merchantRaw: String? = null,
    val reference: String? = null,
    val balanceAfter: Money? = null,
    val occurredAt: Instant? = null,
    val dueDate: LocalDate? = null,
    val minimumDue: Money? = null,
    val institution: String? = null,
    val confidence: Double,
    val parserId: String,
) {
    /** Fills null fields from [other] (does not change kind/confidence/parserId). */
    fun fillFrom(other: Extraction?): Extraction {
        if (other == null) return this
        return copy(
            direction = direction ?: other.direction,
            amount = amount ?: other.amount,
            accountAmount = accountAmount ?: other.accountAmount?.takeIf { amount == null || it.currency != amount.currency },
            accountLast4 = accountLast4 ?: other.accountLast4,
            merchantRaw = merchantRaw ?: other.merchantRaw,
            reference = reference ?: other.reference,
            balanceAfter = balanceAfter ?: other.balanceAfter,
            occurredAt = occurredAt ?: other.occurredAt,
            dueDate = dueDate ?: other.dueDate,
            minimumDue = minimumDue ?: other.minimumDue,
            institution = institution ?: other.institution,
        )
    }
}

/** Kinds that represent money actually moving (ledger entries). */
object Kinds {
    val LEDGER: Set<MessageKind> = setOf(
        MessageKind.DEBIT, MessageKind.CREDIT, MessageKind.REFUND, MessageKind.REVERSAL,
        MessageKind.ATM_WITHDRAWAL, MessageKind.UPI_SENT, MessageKind.UPI_RECEIVED, MessageKind.CARD_AUTH,
        MessageKind.CARD_SETTLEMENT, MessageKind.EMI, MessageKind.SALARY, MessageKind.FX_MARKUP,
    )

    /** Financial but informational: no ledger entry should be created (status INFO_ONLY when confident). */
    val INFO: Set<MessageKind> = setOf(
        MessageKind.BILL_DUE, MessageKind.STATEMENT, MessageKind.BALANCE, MessageKind.AUTOPAY_MANDATE,
        MessageKind.FAILED_TXN,
    )

    fun defaultDirection(kind: MessageKind): TxnDirection? = when (kind) {
        MessageKind.DEBIT, MessageKind.ATM_WITHDRAWAL, MessageKind.UPI_SENT, MessageKind.CARD_AUTH,
        MessageKind.CARD_SETTLEMENT, MessageKind.EMI, MessageKind.FX_MARKUP, MessageKind.AUTOPAY_MANDATE -> TxnDirection.DEBIT
        MessageKind.CREDIT, MessageKind.REFUND, MessageKind.REVERSAL, MessageKind.UPI_RECEIVED, MessageKind.SALARY -> TxnDirection.CREDIT
        else -> null
    }
}
