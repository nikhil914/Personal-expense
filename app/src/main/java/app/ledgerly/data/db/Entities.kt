package app.ledgerly.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.ledgerly.core.model.AccountType
import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.ParseStatus
import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/*
 * Room schema mirroring the Ledgerly data model. All money is stored as integer minor units plus an
 * ISO 4217 code column. The whole database file is encrypted with SQLCipher (see LedgerlyDatabase).
 * Set-valued fields (tags, last4s, channels) are stored as unit-separator-delimited strings and
 * converted in data/mapper/Mappers.kt, which keeps TypeConverters simple and unambiguous.
 */

enum class ReviewState { OK, NEEDS_REVIEW, POSSIBLE_DUPLICATE }

enum class AuditActor { USER, PARSER, RULE, DEDUP, SYSTEM }


enum class BudgetPeriod { MONTHLY, WEEKLY, YEARLY, TRIP }

enum class BudgetMethod { CATEGORY, ENVELOPE, FIFTY_THIRTY_TWENTY, FLEXIBLE }


enum class RuleField { MERCHANT, SENDER, TEXT }

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: AccountType,
    val currency: String,
    val institution: String? = null,
    /** Unit-separator-delimited last-4 digits that identify this account in messages. */
    val last4: String = "",
    val openingBalanceMinor: Long = 0,
    val creditLimitMinor: Long? = null,
    val includeInNetWorth: Boolean = true,
    val archived: Boolean = false,
    val createdAt: Instant = Instant.now(),
)

/** Per-currency balance inside one account (multi-currency forex cards, Wise/Revolut wallets). */
@Entity(
    tableName = "sub_balances",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "currency"], unique = true)],
)
data class SubBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val currency: String,
    val balanceMinor: Long,
    val label: String? = null,
    val updatedAt: Instant = Instant.now(),
)

@Entity(
    tableName = "transactions",
    indices = [
        Index("occurredAt"), Index("categoryId"), Index("accountId"), Index("deletedAt"),
        Index("tripId"), Index("reviewState"), Index("reference"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String?,
    val type: TxnType,
    val status: TxnStatus = TxnStatus.POSTED,
    val occurredAt: Instant,
    // What the merchant charged.
    val originalMinor: Long,
    val originalCurrency: String,
    // What hit the account, in the account currency.
    val accountMinor: Long,
    val accountCurrency: String,
    // Reporting (base) currency.
    val baseMinor: Long,
    val baseCurrency: String,
    val fxRateApplied: BigDecimal? = null,
    val marketRate: BigDecimal? = null,
    val rateSource: String? = null,
    val rateDate: LocalDate? = null,
    val fxFeeMinor: Long? = null,
    val fxFeeCurrency: String? = null,
    val merchant: String? = null,
    val merchantRaw: String? = null,
    val categoryId: String? = null,
    val tags: String = "",
    val note: String? = null,
    val reference: String? = null,
    val accountLast4: String? = null,
    val transferGroupId: String? = null,
    val refundOfId: String? = null,
    val tripId: String? = null,
    /** Channels that contributed (merged sources badge), unit-separator-delimited Channel names. */
    val sourceChannels: String = "",
    val reviewState: ReviewState = ReviewState.OK,
    val duplicateOfId: String? = null,
    val parseConfidence: Double? = null,
    val isReviewed: Boolean = false,
    val deletedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Entity(
    tableName = "transaction_splits",
    foreignKeys = [ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("transactionId"), Index("categoryId")],
)
data class TransactionSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: String,
    val categoryId: String,
    val amountMinor: Long,
    val currency: String,
    val note: String? = null,
)

/**
 * Raw captured message. Doubles as the capture queue: rows with processed = false are pending.
 * [body] is nulled by the retention job; [contentHash] prevents double-capture of the same message.
 */
@Entity(
    tableName = "source_events",
    indices = [Index(value = ["contentHash"], unique = true), Index("linkedTxnId"), Index("processed"), Index("parseStatus")],
)
data class SourceEventEntity(
    @PrimaryKey val id: String,
    val channel: Channel,
    val sender: String?,
    val packageName: String?,
    val title: String?,
    val body: String?,
    val receivedAt: Instant,
    val contentHash: String,
    val processed: Boolean = false,
    val parseStatus: ParseStatus? = null,
    val parserId: String? = null,
    val confidence: Double? = null,
    val linkedTxnId: String? = null,
    val error: String? = null,
    val purgedAt: Instant? = null,
    val capturedAt: Instant = Instant.now(),
)

/** Append-only audit log. The DAO exposes no update/delete and DB triggers abort any attempt. */
@Entity(tableName = "transaction_events", indices = [Index("transactionId"), Index("at")])
data class TransactionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: String,
    val type: AuditEventType,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val actor: AuditActor,
    val at: Instant = Instant.now(),
    val note: String? = null,
)

@Entity(tableName = "categories", indices = [Index("parentId")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val isIncome: Boolean = false,
    val icon: String? = null,
    val colorHex: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

@Entity(tableName = "rules", indices = [Index("priority")])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val field: RuleField,
    val pattern: String,
    val isRegex: Boolean = false,
    val categoryId: String? = null,
    val merchantName: String? = null,
    val addTags: String = "",
    val accountId: String? = null,
    val priority: Int = 100,
    val enabled: Boolean = true,
    val createdFromTxnId: String? = null,
    val createdAt: Instant = Instant.now(),
)

@Entity(tableName = "merchants", indices = [Index(value = ["name"], unique = true)])
data class MerchantEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Unit-separator-delimited lowercase aliases matched against raw merchant strings. */
    val aliases: String = "",
    val defaultCategoryId: String? = null,
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val method: BudgetMethod = BudgetMethod.CATEGORY,
    val currency: String,
    /** Optional overall cap; when null the budget total is the sum of its lines. */
    val totalMinor: Long? = null,
    val rollover: Boolean = false,
    val tripId: String? = null,
    val active: Boolean = true,
    val createdAt: Instant = Instant.now(),
)

@Entity(
    tableName = "budget_lines",
    foreignKeys = [ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["budgetId", "categoryId"], unique = true)],
)
data class BudgetLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val budgetId: String,
    val categoryId: String,
    val limitMinor: Long,
    val rolloverMinor: Long = 0,
)

@Entity(tableName = "recurring")
data class RecurringEntity(
    @PrimaryKey val id: String,
    val name: String,
    val merchant: String?,
    val categoryId: String?,
    val amountMinor: Long,
    val currency: String,
    val cadence: Cadence,
    val intervalDays: Int? = null,
    val nextDue: LocalDate?,
    val lastSeen: LocalDate? = null,
    val isSubscription: Boolean = false,
    val accountId: String? = null,
    val active: Boolean = true,
    val autoDetected: Boolean = false,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val savedMinor: Long = 0,
    val targetDate: LocalDate? = null,
    val isSinkingFund: Boolean = false,
    val categoryId: String? = null,
)

@Entity(tableName = "trips", indices = [Index("active")])
data class TripEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    val countryCode: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val budgetMinor: Long? = null,
    val budgetCurrency: String? = null,
    val autoDetected: Boolean = false,
    val active: Boolean = true,
)

/** Reference rate: 1 [base] = [rate] [quote] on [date]. ECB rows are EUR-based. */
@Entity(tableName = "fx_rates", primaryKeys = ["date", "base", "quote", "source"])
data class FxRateEntity(
    val date: LocalDate,
    val base: String,
    val quote: String,
    val rate: BigDecimal,
    val source: String,
    val fetchedAt: Instant = Instant.now(),
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("transactionId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val uri: String,
    val mimeType: String,
    val ocrText: String? = null,
    val addedAt: Instant = Instant.now(),
)

/** User-learned parser template (from corrections); handed to core:parser's template engine. */
@Entity(tableName = "parser_templates", indices = [Index("senderPattern")])
data class ParserTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val senderPattern: String?,
    val packageName: String?,
    val regex: String,
    /** JSON object mapping regex group names to fields (amount, currency, merchant, last4, ...). */
    val fieldMap: String,
    val sampleBody: String? = null,
    val learnedFromTxnId: String? = null,
    val hits: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
)

@Entity(tableName = "ignored_senders")
data class IgnoredSenderEntity(
    @PrimaryKey val sender: String,
    val channel: Channel,
    val reason: String? = null,
    val addedAt: Instant = Instant.now(),
)

/** Key/value settings that must live inside the encrypted DB (UI preferences live in DataStore). */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value") val value: String,
)
