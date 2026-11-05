package app.ledgerly.core.dedup

import app.ledgerly.core.model.Channel
import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.MessageKind
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import app.ledgerly.core.model.TxnDirection
import app.ledgerly.core.model.TxnStatus
import app.ledgerly.core.model.TxnType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

internal val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
internal fun at(d: Int, h: Int, m: Int = 0, s: Int = 0, month: Int = 3): Instant =
    LocalDateTime.of(2026, month, d, h, m, s).toInstant(IST)
internal fun inr(s: String) = Money.of(s, CurrencyCode.INR)
internal fun usd(s: String) = Money.of(s, CurrencyCode.USD)

internal fun cand(
    id: String,
    channel: Channel,
    amount: Money,
    time: Instant,
    merchant: String? = null,
    last4: String? = "4521",
    accountId: String? = "hdfc-cc",
    ref: String? = null,
    kind: MessageKind = MessageKind.DEBIT,
    direction: TxnDirection? = TxnDirection.DEBIT,
    accountAmount: Money? = null,
    text: String? = null,
    pkg: String? = null,
    balance: Money? = null,
) = Candidate(
    sourceEventId = id, channel = channel, kind = kind, direction = direction, amount = amount,
    accountAmount = accountAmount, accountId = accountId, accountLast4 = last4, institution = "HDFC",
    merchantRaw = merchant, reference = ref, balanceAfter = balance, occurredAt = time, rawText = text, packageName = pkg,
)

/** Build the transaction the app would create from a brand-new candidate. */
internal fun Candidate.toTxn(id: String, status: TxnStatus = TxnStatus.POSTED) = Transaction(
    id = id, accountId = accountId, type = suggestedType, status = status, occurredAt = occurredAt,
    original = amount, account = accountAmount ?: amount, base = accountAmount ?: amount,
    merchantRaw = merchantRaw, reference = reference, accountLast4 = accountLast4,
    sourceEventIds = listOf(sourceEventId), sourceChannels = setOf(channel),
)

internal fun txn(
    id: String, amount: Money, time: Instant, merchant: String?, type: TxnType = TxnType.EXPENSE,
    accountId: String? = "hdfc-cc", channels: Set<Channel> = setOf(Channel.SMS), ref: String? = null,
    account: Money = amount, status: TxnStatus = TxnStatus.POSTED, refundOf: String? = null, note: String? = null,
) = Transaction(
    id = id, accountId = accountId, type = type, status = status, occurredAt = time, original = amount, account = account,
    base = account, merchantRaw = merchant, reference = ref, refundOfId = refundOf, note = note,
    sourceEventIds = listOf("ev-$id"), sourceChannels = channels,
)
