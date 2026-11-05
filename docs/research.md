# Personal Expense & Budget Tracker: Research and Product Spec

*Research date: 5 November 2026*

Scope: a full budget-tracker app that captures transactions from **SMS, email, app notifications and manual entry**, handles **multiple currencies**, and keeps a **complete, auditable history of every entry**.

---

## 1. Executive summary

- **Automatic capture is the main differentiator, and it is platform-limited.** SMS parsing works only on Android. iOS gives third-party apps no SMS inbox access, so iPhone capture must rely on the Apple Wallet *Transaction* Shortcuts trigger, email, share-sheet, OCR and bank import.
- **The two big permission gates are Google's.**
  - SMS (`READ_SMS` / `RECEIVE_SMS`): Google Play allows an exception for "SMS-based money management (budgeting apps)", but only after a Permissions Declaration review. Google tightened its wording in 2025 and has another update due 27 Jan 2027.
  - Gmail API: `gmail.readonly` is a **restricted** scope. It needs full OAuth verification and an **annual CASA security assessment**. A forwarding address (e.g. `you@inbox.app.com`) avoids this.
- **Multi-currency is where most apps are weak.** Wallet (BudgetBakers) and Spendee handle it best, but per account. YNAB and Monarch are mostly single-currency. Do it per transaction: store the original amount, the account amount, the base amount and the exchange rate used. That is a clear gap you can win.
- **De-duplication across channels is the hard engineering problem.** One card swipe can arrive as an SMS, a bank-app notification and an email. Without a merge engine, users see the same spend three times.
- **Local-first and privacy-first is a selling point.** Open-source trackers (TU Expense Tracker, MyFinanceMate, app_expense_tracker) market "no INTERNET permission, data never leaves the device". Make on-device parsing the default and cloud sync optional, with end-to-end encryption.

---

## 2. Competitive landscape

| App | Platforms | Auto-capture | Multi-currency | Budget style | Notable features |
|---|---|---|---|---|---|
| **YNAB** | iOS, Android, Web | Bank sync (US/EU) | Limited (one currency per budget) | Zero-based ("give every dollar a job") | Age of money, goals, targets, education. About $14.99/mo or $109/yr |
| **Monarch Money** | iOS, Android, Web | Bank sync (Plaid etc.) | Weak, US-focused | Flexible + category | Net worth, investments, household sharing, recurring detection. About $99.99/yr |
| **Copilot** | iOS, Mac | Bank sync | Limited | Category | ML categorisation, polished UI |
| **Wallet (BudgetBakers)** | iOS, Android, Web | Bank sync (Premium), CSV import | Strong: automatic rates, per account | Category + planned payments | Shared wallets, goals, debts, warranties |
| **Spendee** | iOS, Android | Bank sync (paid) | Good: each wallet has its own currency, converted for totals | Category | Shared wallets, travel use |
| **Toshl** | iOS, Android, Web | Bank sync, import | Strong (popular with travellers) | Category | Many currencies incl. crypto, fun UI |
| **Money Manager (Realbyte)** | iOS, Android | Manual, some SMS | Yes | Category | Double-entry style, assets/liabilities, PC sync |
| **Bluecoins** | Android | Manual (lost SMS reading after Play policy) | Yes | Category | Advanced reports, offline |
| **Pockito** | Android | **SMS + payment-app notifications**, no bank login | n/a | Category | Budget alerts, bill reminders |
| **Exp Tracker** | Android | **SMS + email + notifications** from trusted senders only | n/a | Category | No login, no cloud |
| **TU Expense Tracker / MyFinanceMate** | Android (F-Droid, open source) | SMS on device | n/a | Category | Fully offline |
| **Axio / Walnut, Mera Kharcha, FinArt** | Android (India) | SMS (+ notifications) | INR-focused | Category | UPI, bill and credit-card due tracking |
| **Splitwise** | iOS, Android, Web | Manual | Yes, per expense | n/a (splits) | Groups, debt simplification, settle-up |
| **Finny, WalletPal, Flow** | iOS | Apple Pay **Transaction** Shortcut | Finny: multi-currency, offline | Category | "Tap to track" |

**Gap in the market:** no mainstream app combines (a) multi-channel auto-capture with de-duplication, (b) true per-transaction multi-currency, (c) a full audit trail, and (d) local-first privacy.

---

## 3. Data capture channels

### 3.1 SMS (Android only)

- **APIs:** a `BroadcastReceiver` for `SMS_RECEIVED` for live messages. A one-time `ContentResolver` query on `Telephony.Sms.Inbox` imports history (for example the last 6–12 months).
- **Play policy:** declare the "SMS-based money management" exception in Play Console. Be ready for rejection. Have a **fallback build without SMS permissions** that uses notifications only. Bluecoins lost SMS reading this way.
- **Filtering:** allow-list sender IDs (e.g. `AX-HDFCBK`, `VM-ICICIB`, short codes). Never upload or keep non-financial SMS. Discard them in memory.
- **History import:** offer "scan past messages" during onboarding. This is a big wow moment: a populated dashboard on day 1.

### 3.2 Notification listener (Android)

- **API:** `NotificationListenerService`. The user grants it in Settings → Notification access, which is less restricted on Play than SMS.
- **Covers:** UPI apps (GPay, PhonePe, Paytm), bank apps, card apps (Amex, Revolut, Wise), wallets, food and ride apps (Swiggy, Uber).
- **Rules:** only process the package names the user has enabled. Show a per-app toggle list. Handle grouped and updated notifications so one transaction is not counted twice.
- **Also useful on Android when SMS permission is denied**, because most banks also push app notifications.

### 3.3 Email

Use one of three options, in order of preference:

1. **Forwarding address** (recommended for launch). Each user gets `u123@in.yourapp.com`. They set a Gmail or Outlook filter that forwards bank alerts and receipts. The server parses inbound mail (e.g. SES, Postmark or Mailgun inbound) and needs **no restricted OAuth scope**. Expensify and TripIt use this pattern.
2. **Gmail API** with `gmail.readonly` and a search query (`from:(alerts@bank.com) newer_than:1d`). This is restricted, so it needs verification plus an **annual CASA assessment**. Google's wording is unclear on whether on-device-only processing is exempt, so plan for the assessment.
3. **Microsoft Graph** (`Mail.Read`) for Outlook, and **IMAP with an app password** for Yahoo, iCloud, Zoho and other providers.

Parse: bank alerts, card statements (PDF attachments, which may be password-protected with DOB or PAN patterns), e-commerce order confirmations, invoices and subscriptions.

### 3.4 iOS-specific capture

- **Apple Wallet Transaction trigger** (Shortcuts, iOS 17+). It passes the merchant, amount and card to your App Intent `AddTransaction`. Ship a one-tap installable Shortcut. Reliability has been reported as patchy.
- **Share extension:** share a bank SMS, screenshot or email to the app, which parses it.
- **Live Text / OCR** on screenshots of UPI or bank-app confirmations.
- **App Intents + Siri:** "Hey Siri, spent 12 euros on lunch".
- **Interactive widgets** and **Control Center** controls for quick add.

### 3.5 Manual and other input

- Quick-add with natural language: `"450 uber"`, `"€12.50 coffee yesterday cash"`.
- Voice entry.
- Receipt photo with OCR, which captures line items, tax and merchant.
- Recurring templates and favourites.
- CSV, OFX, QIF and MT940 import. PDF bank-statement import.
- Optional bank aggregation: Plaid (US), TrueLayer or GoCardless (EU/UK open banking), **Account Aggregator** framework (India), Salt Edge (global).
- Wear OS / Apple Watch quick entry.

### 3.6 Parsing pipeline

```
raw event (SMS | notification | email | OCR | manual)
   → store raw payload (encrypted, for audit)
   → sender / package allow-list check
   → classifier: is this a transaction? (debit / credit / OTP / promo / balance / bill-due)
   → extractor (tiered):
        1. per-bank template / regex library (fast, versioned, remotely updatable)
        2. on-device small model / LLM fallback for unknown formats
        3. user review queue → learns new template from correction
   → normalise: amount (minor units), currency (ISO 4217), direction, account (last 4 digits),
                merchant, timestamp, reference (UPI ref / RRN / auth code), balance-after
   → merchant normalisation ("AMZN MKTP IN*2X3" → Amazon)
   → de-duplication / merge engine
   → auto-categorisation (rules → merchant DB → ML)
   → transaction (with link to all source events)
```

**Message types to handle:** debit, credit, refund, reversal, ATM withdrawal, UPI send/receive, card authorisation vs settlement, EMI, autopay mandate, bill due, statement generated, salary credit, balance update, FX-markup charge, and failed transactions (ignore these, or mark them failed).

### 3.7 De-duplication engine (critical)

- **Match key:** same amount and currency, same account or card last 4 digits, timestamps within about ±15 minutes (configurable), and a fuzzy merchant match.
- **Strong key:** when a UPI reference, RRN or auth code is present, an exact match on it wins.
- **Merge, don't drop:** one transaction can have several `source_events`. Keep the richest fields from each (the email has line items, the SMS has the balance, the notification is fastest).
- **Authorisation → settlement:** a hotel or fuel pre-auth may settle for a different amount, or in a different currency with the conversion applied. Update the transaction rather than creating a new one.
- **Refunds:** link them to the original transaction.
- **Transfers between your own accounts:** a debit in A plus a credit in B for the same amount is a transfer, not spending. Detect it and suggest a link.
- **Uncertain matches** go to a "Possible duplicate?" card for the user to resolve.

---

## 4. Multi-currency design

### 4.1 Per-transaction fields

| Field | Example |
|---|---|
| `original_amount`, `original_currency` | 45.00 EUR (what the merchant charged) |
| `account_amount`, `account_currency` | 4,212.30 INR (what hit the card) |
| `fx_rate_applied` | derived 93.607 (bank's actual rate) |
| `fx_markup_fee` | 3.5% + GST, detected from a separate SMS or the statement |
| `base_amount`, `base_currency` | user's home or reporting currency |
| `market_rate`, `rate_source`, `rate_date` | ECB / OpenExchangeRates rate on the transaction date |
| `fx_cost` | account_amount − (original × market_rate): **shows the user what the bank charged for the conversion** |

### 4.2 Rules

- Store money as **integer minor units + ISO 4217 code**. Respect each currency's exponent: JPY has 0 decimals, KWD and BHD have 3. Never use floats. Use `BigDecimal` or `Decimal` for rates.
- **Historical rates:** cache daily rates locally (ECB is free for about 30 currencies; OpenExchangeRates, Fixer or exchangerate.host cover about 170, plus crypto via CoinGecko). Offline mode uses the last cached rate and flags it.
- **Rate choice is per entry:** actual (from the statement), market on the date, or manual override.
- **Accounts can be in any currency.** A Wise or Revolut-style multi-currency account holds several balances (sub-accounts).
- **Cash in a foreign currency:** "bought €300 for ₹27,600" sets a cost basis. Later cash spends draw from that pool at the purchase rate (FIFO or average), so real cost is accurate.
- **Cross-currency transfers** record both legs and show the realised FX gain or loss.
- **Reporting:** any report can be shown in the base currency or the original currency. Net worth gets an "unrealised FX change" line for foreign-currency balances.
- **Budgets:** set in the base currency. Foreign spends count at their `base_amount`. Trip budgets can be in the local currency.
- **Travel mode:** set the trip currency and dates. Manual entries default to that currency. Get a trip summary of total cost in your home currency and FX fees paid.
- **Detect DCC** (dynamic currency conversion), when the merchant charged in your home currency abroad, and educate the user that it is usually worse than paying in the local currency.

---

## 5. Entry tracking and audit trail

- **Event-sourced history.** Every create, edit, categorise, split, merge, delete and restore writes an immutable `transaction_event` row: who, when, which device, which fields changed, and old and new values.
- **Source lineage.** Each transaction links to its raw SMS, notification or email or receipt image. The user can tap "View original message".
- **Soft delete + trash** with 30-day restore. Undo after every action.
- **Edit history view** per transaction ("Category changed from Food to Groceries by you, 3 Nov").
- **Ignored and rejected inbox.** Messages the parser saw but did not turn into transactions stay reviewable, so nothing is silently lost.
- **Reconciliation.** Compare the running balance against "balance after" values from SMS, or against statements, and flag mismatches ("₹1,240 unexplained since 12 Oct").
- **Locks.** Lock a month after reconciling, so edits to closed periods need confirmation.
- **Export the full log** (CSV/JSON), including history, for accountants or tax.

---

## 6. Full feature list

### 6.1 Accounts and wallets
- Account types: bank, credit card, cash, wallet (Paytm, PayPal), prepaid or forex card, loan, investment, crypto, and "owed to me / I owe".
- Multi-currency accounts. Opening balances. Credit limit and utilisation.
- Card ↔ account mapping by last 4 digits, so SMS messages route to the right account.
- Archive or hide accounts. Choose which accounts count in net worth.

### 6.2 Transactions
- Expense, income, transfer, refund, adjustment.
- **Splits:** one transaction across several categories (a supermarket bill split into groceries and household).
- Tags, notes, attachments (receipt, invoice, warranty card), location, payee, payment method.
- Line items from receipts and e-commerce emails.
- Recurring transactions with flexible rules (every 2nd Friday, last business day).
- Bulk edit, multi-select, search with filters (amount ranges, regex, tag, currency, source).
- Natural-language search: "how much did I spend on Uber in Lisbon?"

### 6.3 Categorisation
- Default category tree with 2 levels (Food → Groceries, Restaurants, Delivery). Fully customisable with icons and colours.
- Rules engine: "if merchant contains SWIGGY → Food/Delivery, tag: weekday".
- Merchant database plus ML suggestions that learn from user corrections.
- "Needs review" queue for low-confidence categorisation.

### 6.4 Budgeting
Offer several methods and let the user pick:
- **Category budgets** (monthly, weekly or custom period), with **rollover** of surplus or deficit.
- **Zero-based / envelope budgeting** (YNAB style): assign all income, move money between envelopes.
- **50/30/20** auto split.
- **Pay-yourself-first / "safe to spend"**: income − bills − savings goals = daily allowance.
- **Flexible budgeting** (Monarch style): fixed / flexible / non-monthly.
- Budget templates, copy last month, and AI-suggested budgets from history.
- **Sinking funds** for annual costs (insurance, holidays, festivals) that accrue monthly.
- Alerts at 50/80/100%. Projected overspend ("at this pace you'll exceed Dining by ₹2,300").
- Separate trip and event budgets (wedding, renovation).

### 6.5 Bills, subscriptions and recurring payments
- Automatic detection of recurring charges (Netflix, gym, SIP, EMI).
- **Subscription manager:** price-increase alerts, free-trial ending, unused-subscription nudges, annual cost view.
- Bill reminders from SMS or email ("Your card statement: due ₹18,450 by 20 Nov"). Credit-card due-date tracker.
- Calendar view of upcoming cash flow.

### 6.6 Goals and savings
- Savings goals with target date, monthly contribution needed, and progress.
- Link goals to accounts, or keep them virtual.
- Emergency fund calculator (months of expenses covered).

### 6.7 Debts and loans
- Loans and EMIs with amortisation schedule, interest paid, and prepayment simulator.
- Credit card debt payoff planner (avalanche / snowball).
- Personal IOUs: lent to or borrowed from friends, with reminders.

### 6.8 Shared and group expenses
- **Household / family:** shared budgets, per-member accounts, privacy controls (share totals only).
- **Splitwise-style groups:** split equally, by percentage or by shares. Multi-currency groups. Debt simplification. Settle up via UPI or PayPal deep link.
- Roles: owner, editor, viewer.

### 6.9 Reports and insights
- Dashboard: spent this month vs budget, cash flow, top categories, upcoming bills.
- Charts: category donut, trend lines, income vs expense bars, heat-map calendar, Sankey (income → categories).
- Month-over-month and year-over-year comparison. Average spend per category.
- Merchant leaderboard. Spend by location on a map. Spend by payment method. Spend by currency or country.
- **Net worth** over time (assets − liabilities).
- **Cash-flow forecast** for 30/90 days, from recurring items and history.
- Savings rate. "Lifestyle creep" detector.
- **AI monthly review in plain language:** "You spent 18% more on delivery than in Sept; 3 subscriptions renewed."
- Custom report builder. Export to PDF, CSV or Excel.

### 6.10 Investments (light)
- Track holdings (stocks, mutual funds, crypto, gold, FDs) with manual or imported values. Live prices are optional.
- SIP and dividend detection from SMS or email. Asset allocation chart.

### 6.11 Tax and business
- Tag tax-deductible expenses (e.g. 80C/80D in India, HSA/charity in the US). Year-end tax summary.
- Business vs personal flag. Mileage log. Reimbursable expenses with a claim status.
- Warranty and return-window tracker from receipts.

### 6.12 Notifications and nudges
- Real-time "₹850 at Zomato categorised as Food. Correct?" with action buttons.
- Daily or weekly digest. Unusual-spend alerts (large, duplicate or new merchant). Low-balance alerts.
- Possible-fraud flag: a transaction you didn't initiate, in a foreign country, or at an odd hour.

### 6.13 Security and privacy
- App lock (PIN, biometrics). Auto-lock timeout. Hide amounts (privacy mode, shake to blur).
- Encrypted local DB (SQLCipher). Encrypted backups. **E2E-encrypted sync** (the server never sees plaintext).
- Granular permissions: SMS, notifications and email can each be turned on or off independently, with clear explanations.
- Raw-message retention setting (keep 30 days / 1 year / forever / never). One-tap data export and full account deletion.
- Panic or decoy PIN (optional).

### 6.14 Sync, backup and platforms
- Local-first: works fully offline. Sync across phone, tablet, web and desktop.
- Backup to Google Drive, iCloud or Dropbox, or local file.
- Widgets (quick add, budget left today), Wear OS / watchOS, lock-screen and Live Activity for a running trip budget.

### 6.15 Personalisation and accessibility
- Dark mode, themes, custom icons. Pick the week start day and financial month start (salary day, e.g. the 25th).
- Number formats (Indian lakh/crore grouping vs Western), RTL, many languages.
- Screen reader labels, dynamic type, colour-blind-safe charts.

---

## 7. My additional suggestions (differentiators)

1. **"One swipe, one entry" guarantee.** Market the cross-channel de-duplication engine directly, with a visible "merged from SMS + email" badge.
2. **FX fee exposer.** After every foreign transaction, show "Your bank charged ₹147 (3.5%) above the market rate". Yearly summary: "Your cards cost you ₹6,200 in FX. Card X would have saved ₹4,100."
3. **On-device LLM parser.** Use Gemini Nano on Android AICore or Apple Foundation Models on iOS 26+ to parse unknown bank formats without sending data to a server. Fall back to a template library.
4. **Community template marketplace.** Parsers for new banks are contributed and reviewed (anonymised samples only) and pushed as signed rule updates without an app release.
5. **Reconciliation score.** "98% of your bank balance is explained by tracked transactions." This gamifies completeness.
6. **Cash gap prompt.** When an ATM withdrawal is detected, prompt over the next days: "You withdrew ₹5,000. ₹3,200 is unaccounted for. Log it?"
7. **"Can I afford it?"** Enter a purchase and see its impact on budgets, goals and the cash-flow forecast.
8. **Trip mode, automatic.** Detect a new country from SIM roaming or the transaction currency, suggest starting a trip, then produce a trip report.
9. **Subscription killer.** List of subscriptions with deep links to each cancel page, plus a "renewing in 3 days" alert.
10. **Chat with your money.** Ask in natural language ("What did I spend on gifts in 2025 in EUR?"), answered from the local DB. The LLM only gets aggregates.
11. **Receipt line-item intelligence.** Track grocery item price inflation ("Milk up 12% since March").
12. **Family pocket-money mode.** Kids get a virtual wallet. Parents see a summary only.
13. **Statement import with password vault.** Auto-unlock PDF statements whose password follows common patterns (DOB, PAN prefix) with the user's consent, then reconcile.
14. **Smart home-screen widget:** "Safe to spend today: ₹1,120".
15. **Year in review**, Spotify-Wrapped style, shareable without amounts.
16. **Ethical money insights (optional):** estimated carbon footprint by category.

---

## 8. Core data model (simplified)

```
User(id, base_currency, locale, fin_month_start_day)
Account(id, user_id, name, type, currency, institution, last4[], opening_balance_minor, is_archived)
SubBalance(id, account_id, currency, balance_minor)            -- multi-currency accounts
Transaction(id, user_id, account_id, type, status[pending|posted|failed|void],
            occurred_at, posted_at,
            original_amount_minor, original_currency,
            account_amount_minor, account_currency,
            base_amount_minor, base_currency,
            fx_rate_applied, market_rate, rate_source, rate_date, fx_fee_minor,
            merchant_id, payee_raw, note, location, reference_id,
            transfer_group_id, refund_of_id, trip_id, is_reviewed, deleted_at)
TransactionSplit(id, transaction_id, category_id, amount_minor, note)
SourceEvent(id, user_id, channel[sms|notif|email|ocr|manual|bank_api|import],
            sender, package_name, received_at, raw_payload_encrypted, parse_status,
            parser_version, confidence, transaction_id NULL)
TransactionEvent(id, transaction_id, actor, device_id, at, action, diff_json)   -- audit log
Category(id, parent_id, name, icon, color, kind[expense|income])
Rule(id, conditions_json, actions_json, priority)
Merchant(id, canonical_name, aliases[], default_category_id, logo)
Budget(id, period, method, currency, rollover, ...) / BudgetLine(budget_id, category_id, amount_minor)
Recurring(id, rrule, template_json, next_due)
Goal(id, target_minor, currency, target_date, linked_account_id)
Trip(id, name, currency, start, end, budget_minor)
Group / GroupMember / Share   -- split expenses
FxRate(date, base, quote, rate, source)
Attachment(id, transaction_id, type, uri_encrypted, ocr_json)
```

---

## 9. Recommended architecture and stack

- **Client:** Kotlin Multiplatform (shared logic) with native UI (Jetpack Compose for Android, SwiftUI for iOS), because SMS, notification listener and App Intents are deep native APIs. Flutter is the alternative, but it needs platform channels for capture.
- **Local DB:** SQLite (SQLDelight or Room) with SQLCipher. Use **CRDT or change-log sync** for multi-device and shared budgets.
- **Parsing:** a versioned rules engine (JSON templates with regex and field maps) plus an on-device ML/LLM fallback. Signed remote rule updates.
- **Backend (optional, for sync, email inbound, FX rates and sharing):** Postgres, an object store for encrypted blobs, an email-inbound webhook, an FX rate cron job, and push notifications. E2E encryption with per-user keys, plus key escrow via a recovery phrase.
- **FX:** a daily rate job feeds a rates table, and the client caches a 2-year history.
- **Analytics:** privacy-preserving only (no transaction contents). Parse-failure telemetry sends only bank ID and format hash, after user opt-in.

---

## 10. Compliance and store policy checklist

- **Google Play:** fill in the SMS/Call Log Permissions Declaration (money-management exception), the Notification Listener usage justification, a prominent disclosure + consent screen, and the Data Safety form. Re-check the policy update effective **27 Jan 2027**.
- **Gmail:** restricted-scope verification and annual CASA, or avoid it by using a forwarding address.
- **Apple:** no SMS access. Avoid any workaround that violates the App Store guidelines. Use Shortcuts and App Intents.
- **Data protection:** India **DPDP Act 2023** (consent, purpose limitation, deletion), **GDPR** (EU), **CCPA** (California). Publish a clear privacy policy that states no sale of data.
- **Bank aggregation:** licensing comes through the aggregator (Plaid, an AA/FIU registration in India, AISP under PSD2 via a partner).

---

## 11. Roadmap

| Phase | Scope |
|---|---|
| **MVP (≈3 months)** | Android: manual entry, SMS + notification capture for top 20 banks/apps, review queue, de-duplication v1, categories + rules, monthly category budgets, multi-currency (per-transaction, daily ECB rates), audit log, local encrypted DB, CSV export, app lock |
| **v1.0** | iOS app (Shortcuts, share sheet, OCR), email forwarding parser, recurring/subscription detection, bills calendar, goals, reports dashboard, backup/restore, widgets |
| **v1.5** | E2E sync + web app, household sharing, Splitwise-style groups, trip mode, FX fee exposer, cash-flow forecast, statement PDF import |
| **v2.0** | On-device LLM parser + chat-with-money, envelope/zero-based mode, investments + net worth, bank aggregation (region by region), tax reports, community parser marketplace |

**Monetisation (freemium):** free tier covers manual entry, SMS/notification capture, 1 currency and basic budgets. Premium (about $3–5/month or $25–40/year) adds unlimited currencies, email, sync, sharing, advanced reports, AI insights and receipt OCR. **Never sell data.** Make that a headline promise.

---

## 12. Key risks

- **Play Store rejection of SMS permission.** Mitigate with notification-only mode and a sideload/F-Droid build.
- **Bank message formats change often.** Mitigate with remote template updates, LLM fallback and the review queue.
- **Duplicate or missed transactions damage trust.** Invest early in de-duplication and reconciliation.
- **Security breach of financial data.** Mitigate with local-first design, E2E encryption, a minimal server, and pen tests (CASA covers part of this).
- **iOS feature parity gap.** Be honest in marketing and lean on email, Shortcuts and bank sync on iOS.

---

## Sources

- [Pockito: SMS Expense Tracker (Google Play)](https://play.google.com/store/apps/details?id=com.pockito)
- [Exp Tracker (Google Play)](https://play.google.com/store/apps/details?id=com.ak.exptracker)
- [TU Expense Tracker (F-Droid)](https://f-droid.org/packages/com.tu.expense.manager/)
- [MyFinanceMate (F-Droid)](https://f-droid.org/packages/com.myfinancemate/)
- [Best Expense Tracker Apps in India 2026 (Finny)](https://getfinny.app/blog/best-expense-tracker-apps-india-2026)
- [SMS Expense Tracking Apps 2026 (Finny)](https://getfinny.app/blog/sms-expense-tracking-app)
- [Mera Kharcha blog](https://kharcha-platform.web.app/pages/blog_8.html)
- [Expense tracking apps automation features (Gerald)](https://joingerald.com/learn/money-basics/expense-tracking-apps-automation-features)
- [OwnSpend (GitHub)](https://github.com/Godatcode/OwnSpend)
- [app_expense_tracker wiki (GitHub)](https://github.com/gamehackerever/app_expense_tracker/wiki)
- [ExpenseCC (GitHub)](https://github.com/jojoclt/ExpenseCC)
- [expense-widget (GitHub)](https://github.com/ziacik/expense-widget)
- [Wallet by BudgetBakers](https://budgetbakers.com/en/products/wallet/)
- [Wallet by BudgetBakers Review 2026 (Finny)](https://getfinny.app/blog/wallet-budgetbakers-review-2026)
- [Wallet is the best budgeting app for expats](https://theportablewife.com/living-abroad/best-budgeting-app-for-expats-wallet/)
- [Spendee Review 2026 (MakerStack)](https://makerstack.co/reviews/spendee-review/)
- [Best budget apps (MakeTechEasier)](https://maketecheasier.com/best-budget-apps/)
- [10 Best Budgeting Apps for 2025 (Nasdaq)](https://www.nasdaq.com/articles/10-best-budgeting-apps-2025-stay-control-your-finances)
- [Best app to track multiple currencies (Monavio)](https://monavio-landing.pages.dev/blog/best-app-to-track-multiple-currencies)
- [Multi-currency expense tracker for digital nomads (Finny)](https://getfinny.app/blog/multi-currency-expense-tracker-digital-nomads)
- [9 Best Expense Tracker Apps in Europe 2026 (Freenance)](https://freenance.io/comparisons/best-expense-tracker-apps-europe-2026/)
- [Google Play: Use of SMS or Call Log permission groups](https://support.google.com/googleplay/android-developer/answer/9047303)
- [Google Play: permissions exceptions table](https://support.google.com/googleplay/android-developer/answer/17225965)
- [Google Play: SMS/Call Log policy update](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)
- [Google Play: 2025 clarification on financial apps reading SMS](https://support.google.com/googleplay/android-developer/answer/15899442)
- [Bluecoins: Google policy removing SMS permissions](https://bluecoinsapp.com/?p=9286)
- [Gmail API OAuth scopes reference (Nylas)](https://developer.nylas.com/docs/cookbook/use-cases/build/google-oauth-scopes/)
- [Gmail restricted scope and CASA on-device question (Google Developer forum)](https://discuss.google.dev/t/gmail-restricted-scope-is-the-casa-assessment-required-when-mail-is-processed-on-device-and-only-a-derived-subscription-list-reaches-our-server/398372)
- [iOS 17 Transaction Shortcut (iDrop News)](https://www.idropnews.com/ios-17/this-new-ios-17-shortcut-will-help-you-keep-track-of-your-spending/197589/)
- [iOS 17 Shortcuts features (AppleInsider)](https://appleinsider.com/inside/ios-17/tips/how-to-use-ios-17s-new-shortcuts-features-to-control-displays-record-spending-and-more)
- [Apple Pay expense tracker shortcuts (WalletPal)](https://walletpalapp.github.io/apple-pay-expense-tracker-shortcuts.html)
- [Flow iOS Shortcuts guide](https://flow-github-io.pages.dev/guide/ios-shortcuts)
- [Auto-log spending on app open (Finny)](https://getfinny.app/blog/auto-log-spending-open-app-automation-2026)

*Note: many comparison sources are vendor blogs. Verify prices and features on each vendor's site before relying on them.*
