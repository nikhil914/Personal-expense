# core:fx notes

## Model change requests (core:model untouched; worked around locally)
- `Transaction` has no FX fee percentage field; `EnrichmentResult.fxFeePct` carries it. Consider `fxFeePct: BigDecimal?`.
- `Transaction` has no merchant country; `TripDetector` / `DccDetector` take a `merchantCountry: (Transaction) -> String?` resolver.
  Consider `merchantCountry: String?` on `ParsedTransaction` and `Transaction`.
- No model type for foreign-cash lots; `CashPoolLedger` keeps them in memory. Persist `CashLot` + open-lot state in Room.

## Conventions
- `FxRate`: 1 `base` = `rate` `quote`. All intermediate maths uses `MathContext(20, HALF_EVEN)`; money rounding
  is HALF_EVEN at the target currency exponent (via `Money.of`).
- `RateQuote.isStale` = rate date older than requested date by more than `staleToleranceDays` (default 0).
- Fees are expressed in the account currency: `fxFee = account - original * market`.

## Known gaps
- `TripDetector` cannot tell online foreign-currency purchases (Netflix in USD) from travel by itself; pass `ignore`.
- `TripSummary` ignores amounts with no home-currency representation unless `toHome` is supplied (listed in `unconverted`).
- `CashPoolLedger` is not thread-safe and has no persistence/serialisation.
