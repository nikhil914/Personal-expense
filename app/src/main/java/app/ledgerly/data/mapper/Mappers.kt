package app.ledgerly.data.mapper

import app.ledgerly.core.model.Account
import app.ledgerly.core.model.Category
import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.SourceEvent
import app.ledgerly.core.model.Split
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.Trip
import app.ledgerly.data.db.AccountEntity
import app.ledgerly.data.db.CategoryEntity
import app.ledgerly.data.db.ReviewState
import app.ledgerly.data.db.SourceEventEntity
import app.ledgerly.data.db.TransactionEntity
import app.ledgerly.data.db.TransactionSplitEntity
import app.ledgerly.data.db.TripEntity
import java.time.Instant

/** Encodes string sets into a single column using the ASCII unit separator (never typed by users). */
object SetCodec {
    private const val SEP = '\u001F'

    fun encode(values: Collection<String>): String =
        values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(SEP.toString())

    fun decode(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList() else value.split(SEP).filter { it.isNotEmpty() }

    fun encodeChannels(channels: Collection<Channel>): String =
        encode(channels.sortedBy { it.ordinal }.map { it.name })

    fun decodeChannels(value: String?): Set<Channel> =
        decode(value).mapNotNull { name -> Channel.entries.firstOrNull { it.name == name } }.toSet()
}

private fun money(minor: Long, currency: String) = Money(minor, CurrencyCode.of(currency))

// ---- Account ----

fun AccountEntity.toModel(): Account {
    val ccy = CurrencyCode.of(currency)
    return Account(
        id = id,
        name = name,
        type = type,
        currency = ccy,
        institution = institution,
        last4 = SetCodec.decode(last4).toSet(),
        openingBalance = Money(openingBalanceMinor, ccy),
        creditLimit = creditLimitMinor?.let { Money(it, ccy) },
        includeInNetWorth = includeInNetWorth,
        archived = archived,
    )
}

fun Account.toEntity(createdAt: Instant = Instant.now()): AccountEntity = AccountEntity(
    id = id,
    name = name,
    type = type,
    currency = currency.code,
    institution = institution,
    last4 = SetCodec.encode(last4),
    openingBalanceMinor = openingBalance.minor,
    creditLimitMinor = creditLimit?.minor,
    includeInNetWorth = includeInNetWorth,
    archived = archived,
    createdAt = createdAt,
)

// ---- Category ----

fun CategoryEntity.toModel(): Category = Category(id = id, name = name, parentId = parentId, isIncome = isIncome)

fun Category.toEntity(sortOrder: Int = 0): CategoryEntity =
    CategoryEntity(id = id, name = name, parentId = parentId, isIncome = isIncome, sortOrder = sortOrder)

// ---- Trip ----

fun TripEntity.toModel(): Trip = Trip(
    id = id,
    name = name,
    currency = CurrencyCode.of(currency),
    countryCode = countryCode,
    start = startDate,
    end = endDate,
    budget = if (budgetMinor != null && budgetCurrency != null) money(budgetMinor, budgetCurrency) else null,
)

fun Trip.toEntity(active: Boolean = end == null, autoDetected: Boolean = false): TripEntity = TripEntity(
    id = id,
    name = name,
    currency = currency.code,
    countryCode = countryCode,
    startDate = start,
    endDate = end,
    budgetMinor = budget?.minor,
    budgetCurrency = budget?.currency?.code,
    autoDetected = autoDetected,
    active = active,
)

// ---- SourceEvent ----

/** Returns null when the raw body was purged by the retention policy (nothing left to parse). */
fun SourceEventEntity.toModel(): SourceEvent? {
    val text = body ?: return null
    return SourceEvent(
        id = id,
        channel = channel,
        sender = sender,
        packageName = packageName,
        title = title,
        body = text,
        receivedAt = receivedAt,
    )
}

// ---- Transaction ----

fun TransactionSplitEntity.toModel(): Split =
    Split(categoryId = categoryId, amount = money(amountMinor, currency), note = note)

fun Split.toEntity(transactionId: String): TransactionSplitEntity = TransactionSplitEntity(
    transactionId = transactionId,
    categoryId = categoryId,
    amountMinor = amount.minor,
    currency = amount.currency.code,
    note = note,
)

fun TransactionEntity.toModel(
    splits: List<TransactionSplitEntity> = emptyList(),
    sourceEventIds: List<String> = emptyList(),
): Transaction = Transaction(
    id = id,
    accountId = accountId,
    type = type,
    status = status,
    occurredAt = occurredAt,
    original = money(originalMinor, originalCurrency),
    account = money(accountMinor, accountCurrency),
    base = money(baseMinor, baseCurrency),
    fxRateApplied = fxRateApplied,
    marketRate = marketRate,
    rateSource = rateSource,
    rateDate = rateDate,
    fxFee = if (fxFeeMinor != null && fxFeeCurrency != null) money(fxFeeMinor, fxFeeCurrency) else null,
    merchant = merchant,
    merchantRaw = merchantRaw,
    categoryId = categoryId,
    splits = splits.map { it.toModel() },
    tags = SetCodec.decode(tags).toSet(),
    note = note,
    reference = reference,
    accountLast4 = accountLast4,
    transferGroupId = transferGroupId,
    refundOfId = refundOfId,
    tripId = tripId,
    sourceEventIds = sourceEventIds,
    sourceChannels = SetCodec.decodeChannels(sourceChannels),
    isReviewed = isReviewed,
    deletedAt = deletedAt,
)

/**
 * Maps a core transaction to its row. App-only columns (review state, duplicate link, confidence,
 * timestamps) are taken from [existing] when updating so a round trip never loses them.
 */
fun Transaction.toEntity(
    existing: TransactionEntity? = null,
    reviewState: ReviewState = existing?.reviewState ?: ReviewState.OK,
    duplicateOfId: String? = existing?.duplicateOfId,
    parseConfidence: Double? = existing?.parseConfidence,
    now: Instant = Instant.now(),
): TransactionEntity = TransactionEntity(
    id = id,
    accountId = accountId,
    type = type,
    status = status,
    occurredAt = occurredAt,
    originalMinor = original.minor,
    originalCurrency = original.currency.code,
    accountMinor = account.minor,
    accountCurrency = account.currency.code,
    baseMinor = base.minor,
    baseCurrency = base.currency.code,
    fxRateApplied = fxRateApplied,
    marketRate = marketRate,
    rateSource = rateSource,
    rateDate = rateDate,
    fxFeeMinor = fxFee?.minor,
    fxFeeCurrency = fxFee?.currency?.code,
    merchant = merchant,
    merchantRaw = merchantRaw,
    categoryId = categoryId,
    tags = SetCodec.encode(tags),
    note = note,
    reference = reference,
    accountLast4 = accountLast4,
    transferGroupId = transferGroupId,
    refundOfId = refundOfId,
    tripId = tripId,
    sourceChannels = SetCodec.encodeChannels(sourceChannels),
    reviewState = reviewState,
    duplicateOfId = duplicateOfId,
    parseConfidence = parseConfidence,
    isReviewed = isReviewed,
    deletedAt = deletedAt,
    createdAt = existing?.createdAt ?: now,
    updatedAt = now,
)
