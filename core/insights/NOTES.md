# core:insights notes

## Model gaps (worked around locally; core/model left unchanged)

1. **`Transaction` has no direction field.** Direction comes from `TxnType`: EXPENSE/ATM_WITHDRAWAL are outflows, INCOME/REFUND are inflows. TRANSFER/ADJUSTMENT are neutral unless `base` is negative (see `Flow` in `Common.kt`). Amounts are assumed to be positive magnitudes, and code uses `abs()` everywhere. Suggestion: add `direction: TxnDirection` to `Transaction`.
2. **No merchant country / card-present flag.** `AnomalyDetector` infers "abroad" from `original.currency != home currency` or from the trip's `countryCode`. It infers card-not-present from online keywords in the merchant string plus a card account. Suggestion: add `merchantCountry: String?` and `cardPresent: Boolean?` (the parser often knows "ECOM"/"POS").
3. **No normalised merchant key on `Transaction`.** `MerchantNormalizer` here is a lightweight local normaliser. If `core:parser` exposes a canonical merchant id, prefer it (`Transaction.merchant` is used first).
4. **FX fees.** `MonthlyReviewGenerator` takes `fxFeesTotal` as input (from `core:fx`). Otherwise it sums `Transaction.fxFee` in the base currency.
5. **Budgets and goals** are simple inputs (`Map<categoryId, Money>` monthly limits, `SavingsGoal`), so the module does not depend on `core:budget`.
6. **Usage data** for unused-subscription nudges is an input map (`serviceId | merchantKey -> lastUsed`). On Android this would come from UsageStatsManager.
7. **Cash-gap state** (`CashGapState`) is an immutable value. The app persists it (Room/DataStore).

## Conventions
- Every time-dependent class takes a `java.time.Clock`. Local dates use `clock.zone`.
- Aggregations use `Transaction.base`. Transactions in a non-dominant base currency are skipped in sums.
- The AskEngine output currency uses `original`/`account` when it already matches the requested currency. Otherwise it uses the injected `MoneyConverter`. With no converter, it falls back to the base currency and adds a note.
- The LLM hook (`LlmQueryTranslator`) only ever sees names (categories, top merchants, trips, accounts) and monthly/category totals (`AskEngine.llmContext`). Its JSON is strictly validated by `QueryJson.parse`.
