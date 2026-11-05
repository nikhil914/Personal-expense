package app.ledgerly.core.fx

import app.ledgerly.core.model.CurrencyCode
import app.ledgerly.core.model.Money
import app.ledgerly.core.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/** Transaction-like input for [MultiCurrencyEnricher]. */
data class EnrichmentInput(
    /** What the merchant charged. */
    val original: Money,
    /** Amount the bank reported in the account currency, if known (e.g. "USD 45.00 (INR 4,212.30)"). */
    val bankAccountAmount: Money?,
    val accountCurrency: CurrencyCode,
    val baseCurrency: CurrencyCode,
    val date: LocalDate,
)

data class EnrichmentResult(
    val original: Money,
    val account: Money,
    val base: Money,
    /** account / original (null when same currency). */
    val fxRateApplied: BigDecimal?,
    /** Market original->account rate (null when same currency or unavailable). */
    val marketRate: BigDecimal?,
    val rateDate: LocalDate?,
    val rateSource: String?,
    /** account - original*market, in account currency. Null if not computable (no bank amount or no rate). */
    val fxFee: Money?,
    /** Fee as % of the market value, scale 2. */
    val fxFeePct: BigDecimal?,
    val stale: Boolean,
    /** True when [account] was estimated from the market rate because the bank amount was unknown. */
    val accountEstimated: Boolean,
)

/** Fills account/base amounts and FX fee fields for a multi-currency transaction. */
class MultiCurrencyEnricher(private val rates: RateStore) {
    private val converter = Converter(rates)

    fun enrich(input: EnrichmentInput): EnrichmentResult {
        val o = input.original
        input.bankAccountAmount?.let {
            require(it.currency == input.accountCurrency) { "bankAccountAmount must be in ${input.accountCurrency}" }
        }
        var stale = false
        var rateDate: LocalDate? = null
        var rateSource: String? = null

        val account: Money
        var market: BigDecimal? = null
        var applied: BigDecimal? = null
        var fee: Money? = null
        var feePct: BigDecimal? = null
        var estimated = false

        if (o.currency == input.accountCurrency) {
            account = input.bankAccountAmount ?: o
        } else {
            val q = rates.lookup(o.currency, input.accountCurrency, input.date)
            if (q != null) {
                market = q.rate; rateDate = q.rateDate; rateSource = q.source; stale = q.isStale
            }
            val bank = input.bankAccountAmount
            account = when {
                bank != null -> bank
                market != null -> { estimated = true; Converter.applyRate(o, market, input.accountCurrency) }
                else -> throw RateUnavailableException(o.currency, input.accountCurrency, input.date)
            }
            if (!o.isZero) applied = account.toDecimal().divide(o.toDecimal(), 6, RoundingMode.HALF_EVEN)
            if (bank != null && market != null) {
                val fair = Converter.applyRate(o, market, input.accountCurrency)
                fee = bank - fair
                if (!fair.isZero) {
                    feePct = fee.toDecimal().multiply(BigDecimal(100))
                        .divide(fair.toDecimal(), 2, RoundingMode.HALF_EVEN)
                }
            }
        }

        val base = when (input.baseCurrency) {
            account.currency -> account
            o.currency -> o
            else -> {
                val c = converter.convert(account, input.baseCurrency, input.date)
                stale = stale || c.stale
                if (rateDate == null) { rateDate = c.rateDate; rateSource = c.source }
                c.result
            }
        }
        return EnrichmentResult(o, account, base, applied, market, rateDate, rateSource, fee, feePct, stale, estimated)
    }

    /** Convenience: enrich a model [Transaction] in place of its multi-currency fields. */
    fun enrich(
        txn: Transaction,
        bankAccountAmount: Money?,
        accountCurrency: CurrencyCode,
        baseCurrency: CurrencyCode,
        zone: ZoneId,
    ): Transaction {
        val r = enrich(
            EnrichmentInput(txn.original, bankAccountAmount, accountCurrency, baseCurrency,
                txn.occurredAt.atZone(zone).toLocalDate())
        )
        return txn.copy(
            account = r.account, base = r.base, fxRateApplied = r.fxRateApplied, marketRate = r.marketRate,
            rateSource = r.rateSource, rateDate = r.rateDate, fxFee = r.fxFee,
        )
    }
}
