package app.ledgerly.core.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

typealias Id = String

enum class Channel { SMS, NOTIFICATION, EMAIL, OCR, MANUAL, IMPORT, BANK_API }

enum class TxnDirection { DEBIT, CREDIT }

enum class TxnType { EXPENSE, INCOME, TRANSFER, REFUND, ADJUSTMENT, ATM_WITHDRAWAL }

enum class TxnStatus { PENDING, POSTED, FAILED, VOID }

enum class AccountType { BANK, CREDIT_CARD, CASH, WALLET, PREPAID, FOREX_CARD, LOAN, INVESTMENT, CRYPTO, RECEIVABLE, PAYABLE }

enum class ParseStatus { PARSED, NEEDS_REVIEW, IGNORED_NOT_FINANCIAL, IGNORED_OTP, IGNORED_PROMO, FAILED, INFO_ONLY }

/** Kind of financial message recognised by the classifier. */
enum class MessageKind {
    DEBIT, CREDIT, REFUND, REVERSAL, ATM_WITHDRAWAL, UPI_SENT, UPI_RECEIVED,
    CARD_AUTH, CARD_SETTLEMENT, EMI, AUTOPAY_MANDATE, BILL_DUE, STATEMENT,
    SALARY, BALANCE, FX_MARKUP, FAILED_TXN, OTP, PROMO, UNKNOWN
}

/** A raw captured event (SMS / notification / email / ...). Raw payload kept for audit. */
data class SourceEvent(
    val id: Id,
    val channel: Channel,
    val sender: String?,          // SMS sender id or email from
    val packageName: String?,     // notification source app
    val title: String? = null,
    val body: String,
    val receivedAt: Instant,
)

/** Output of the parser for one source event. */
data class ParsedTransaction(
    val sourceEventId: Id,
    val channel: Channel,
    val kind: MessageKind,
    val direction: TxnDirection?,
    val amount: Money?,
    /** Amount in the account currency if message shows both (e.g. "USD 45.00 (INR 3,912.30)"). */
    val accountAmount: Money? = null,
    val accountLast4: String? = null,
    val institution: String? = null,
    val merchantRaw: String? = null,
    val reference: String? = null,       // UPI ref / RRN / auth code
    val balanceAfter: Money? = null,
    val occurredAt: Instant,
    val dueDate: LocalDate? = null,      // for BILL_DUE / STATEMENT
    val minimumDue: Money? = null,
    val confidence: Double,
    val parserId: String,                // template id or "ml" / "heuristic"
    val status: ParseStatus,
)

data class Account(
    val id: Id,
    val name: String,
    val type: AccountType,
    val currency: CurrencyCode,
    val institution: String? = null,
    val last4: Set<String> = emptySet(),
    val openingBalance: Money = Money.zero(currency),
    val creditLimit: Money? = null,
    val includeInNetWorth: Boolean = true,
    val archived: Boolean = false,
)

data class Category(
    val id: Id,
    val name: String,
    val parentId: Id? = null,
    val isIncome: Boolean = false,
)

data class Split(val categoryId: Id, val amount: Money, val note: String? = null)

/**
 * Canonical transaction. Multi-currency fields:
 * - original: what the merchant charged
 * - account: what hit the account (in account currency)
 * - base: user's reporting currency
 */
data class Transaction(
    val id: Id,
    val accountId: Id?,
    val type: TxnType,
    val status: TxnStatus = TxnStatus.POSTED,
    val occurredAt: Instant,
    val original: Money,
    val account: Money = original,
    val base: Money = original,
    val fxRateApplied: BigDecimal? = null,
    val marketRate: BigDecimal? = null,
    val rateSource: String? = null,
    val rateDate: LocalDate? = null,
    val fxFee: Money? = null,
    val merchant: String? = null,
    val merchantRaw: String? = null,
    val categoryId: Id? = null,
    val splits: List<Split> = emptyList(),
    val tags: Set<String> = emptySet(),
    val note: String? = null,
    val reference: String? = null,
    val accountLast4: String? = null,
    val transferGroupId: Id? = null,
    val refundOfId: Id? = null,
    val tripId: Id? = null,
    val sourceEventIds: List<Id> = emptyList(),
    val sourceChannels: Set<Channel> = emptySet(),
    val isReviewed: Boolean = false,
    val deletedAt: Instant? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null
}

data class Trip(
    val id: Id,
    val name: String,
    val currency: CurrencyCode,
    val countryCode: String?,
    val start: LocalDate,
    val end: LocalDate?,
    val budget: Money? = null,
)
