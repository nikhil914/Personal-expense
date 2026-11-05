# core:audit notes

## Model change requests (core:model untouched; worked around locally)
- `Transaction` has no `direction`. `Reconciler` infers sign from `type` (EXPENSE/ATM_WITHDRAWAL out,
  INCOME/REFUND in, TRANSFER/ADJUSTMENT use the stored sign of `account`). Override with the `signedAmount`
  constructor parameter. A `direction: TxnDirection` field on `Transaction` would remove the guesswork.
- No `mergedIntoId` on `Transaction`; merge provenance lives in `MergeRecord` (held by `TransactionLedger`)
  and in MERGED/UNMERGED events (`relatedIds`). The app must persist `MergeRecord`s (Room) for unmerge after restart.

## Design
- `TransactionLedger` is the only write path; it emits `LedgerOperation`s that `UndoManager` listens to.
- `TransactionEvent` carries extra fields beyond the spec: `lockOverride`, `relatedIds`, `note`.
- `AuditLog` has no mutation API; `InMemoryAuditLog` freezes diff lists on append and returns read-only copies.
- Purge keeps history (PURGED event); purge is not undoable.
- Undo of update reverts only the diffed fields (later edits to other fields survive).

## Known gaps
- Merge records, period locks, undo stacks and ignored-inbox state are in memory; the app needs persistence.
- Period locks are global, not per account.
- Reconciler compares balances in account currency (`Transaction.account`); `openingAt` cut-off is optional.
