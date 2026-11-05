# core/parser notes

No core/model change was required. Possible future model changes (worked around locally):

- `ParsedTransaction` has no field for the normalised merchant name / category hint.
  Workaround: `TransactionParser.parseDetailed(event)` returns `ParseResult.merchant`
  (`NormalizedMerchant(name, categoryHint, ...)`). Consider adding `merchant` and
  `categoryHint` to `ParsedTransaction`.
- `ParsedTransaction` has no available-credit-limit field; "Avl Limit" amounts on card
  alerts are recognised (so they are never mistaken for the transaction amount) but not exposed.
- Status semantics: informational kinds (BILL_DUE, STATEMENT, BALANCE, AUTOPAY_MANDATE,
  FAILED_TXN) get `INFO_ONLY` when confident, so downstream should only create ledger entries
  for `PARSED` (see `Kinds.LEDGER`).
