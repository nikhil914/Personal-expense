# core:dedup notes

## Model change requests (core:model untouched; worked around locally)
- `Transaction` has no `direction`. `DirectionResolver.DEFAULT` infers it from `type` (INCOME/REFUND = credit,
  EXPENSE/ATM = debit, TRANSFER/ADJUSTMENT unknown; TransferDetector then falls back to the amount sign).
  Consider adding `direction: TxnDirection` to `Transaction`.
- `Transaction` has no `balanceAfter` or line items. `Merger`/`AuthSettlementLinker` return them in `MergeResult`.
- Raw source text / notification package are not on `Transaction`; `DedupEngine` takes `sourceText(id)` and
  `sourcePackage(id)` lookups (backed by the SourceEvent table in the app).
- No notification key on `SourceEvent`; notification updates are detected heuristically (same channel + same app,
  same amount, merchant similar, within `redeliveryWindow`).

## Integration order (per new ParsedTransaction)
1. `Candidate.from(parsed, resolvedAccountId, rawText, packageName)`.
2. `DedupEngine.match(candidate, recentTransactions)`:
   - `MergeInto` -> `Merger.merge(existing, candidate, lockedFields)`; if `needsFxReenrich`, rerun `core:fx` `MultiCurrencyEnricher`.
   - `SettleAuth` -> `AuthSettlementLinker.apply(existing, candidate, lockedFields)` (then re-enrich FX).
   - `PossibleDuplicate` -> review card.
   - `NewTransaction` -> insert, then run `TransferDetector.detectFor` and `RefundLinker.link`.

## Known gaps
- Two genuinely identical purchases within `redeliveryWindow` (5 min) with byte-identical SMS text will merge.
- Merchant similarity is lexical only (no alias dictionary such as "IHCL" = "Taj").
