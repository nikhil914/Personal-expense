# core:budget notes

## Model change requests (core:model untouched)
- No `Budget`, `Goal` or `RecurringBill` types in core:model; they are defined here (`CategoryBudget`,
  `EnvelopeBudget`, `SavingsGoal`, `SinkingFund`, `RecurringItem`). Move them to core:model if other modules need them.
- `Split.amount` currency is not specified. Splits are used as-is when they are in the base currency and add up
  to `base`; otherwise they are scaled proportionally onto `base`.

## Rules
- Spend uses `Transaction.base`. Transfers (`TRANSFER` or `transferGroupId != null`) and `ADJUSTMENT` are excluded.
  Refunds reduce spend in their own category, or else the refunded transaction's category (via `refundOfId`).
  Deleted, FAILED and VOID are ignored; PENDING counts.
- Pace: today counts as an elapsed day. Weighted projection uses `DayOfWeekWeights.fromHistory`.
- Safe to spend: income dated before today is ignored; overdue unpaid bills are still deducted. Balances are
  assumed to be current (already net of `spentToday`).

## Known gaps
- Budgets are evaluated in a single base currency.
- Recurring expansion supports monthly and weekly only. Bills/income from core:insights must be mapped to `PlannedItem`.
