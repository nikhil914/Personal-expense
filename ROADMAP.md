# Ledgerly: Android Implementation Roadmap

An Android-only, local-first expense and budget tracker. It captures transactions automatically from SMS, notifications and email (via the Gmail app's notifications), and also supports manual entry. It is multi-currency at the transaction level and keeps a full audit trail.

## Architecture

```
app/                 Android (Kotlin, Jetpack Compose, Room + SQLCipher, WorkManager, Glance widget)
 ├─ capture/         SmsReceiver, SmsHistoryImporter, TxnNotificationListener (bank/UPI/Gmail apps)
 ├─ data/            Room entities + DAOs, repositories mapping to core models
 ├─ ui/              Compose screens: Home, Transactions, Review inbox, Budgets, Trips, Insights, Ask, Settings
 └─ widget/          "Safe to spend today" Glance widget
core/model           Money (integer minor units, ISO 4217 exponents), domain types
core/parser          classifier, per-bank template engine, heuristic fallback, merchant normaliser, template learning
core/fx              rate store, conversion, FX fee exposer, foreign-cash FIFO pools, trip auto-detect
core/dedup           cross-channel merge engine, transfer + refund linking, auth→settlement
core/audit           event-sourced change log, soft delete/trash, undo, reconciliation + score
core/budget          category/envelope/50-30-20/flexible budgets, rollover, sinking funds, safe-to-spend
core/insights        recurring/subscription detection, cash-flow forecast, can-I-afford, ATM cash-gap prompts,
                     on-device natural-language query engine ("chat with your money"), monthly review
```

The core modules are pure Kotlin/JVM, so they build and are unit-tested without the Android SDK. The `app` module is included automatically when `ANDROID_HOME` or `ANDROID_SDK_ROOT` is set.

## Phases

### Phase 1: Core engine (in progress)
- [ ] `core:model` Money + domain types
- [ ] `core:parser` SMS/notification parsing for major Indian banks, UPI apps, and global cards (Amex, Revolut, Wise, HSBC, Chase-style alerts); OTP/promo filtering; review queue; learning templates from corrections
- [ ] `core:fx` multi-currency: market rate, applied rate, FX fee; foreign cash pools; trip detection
- [ ] `core:dedup` **one payment, one entry** merge engine with source badges
- [ ] `core:audit` immutable change log, trash, undo, reconciliation score
- [ ] `core:budget` budgets + **safe to spend today**
- [ ] `core:insights` subscriptions, forecast, **ATM cash-gap prompt**, **natural-language questions**, can-I-afford

### Phase 2: Android app MVP
- [ ] Room schema (SQLCipher), repositories
- [ ] SMS receiver + history import (with prominent disclosure + consent)
- [ ] NotificationListenerService with a per-app allow-list (bank, UPI, Gmail)
- [ ] Compose UI: onboarding/permissions, home dashboard, transaction list/detail (original message, edit history, merged-sources badge), review inbox, ignored inbox, budgets, trips, settings
- [ ] Biometric app lock, privacy blur
- [ ] CSV/JSON export, encrypted local backup

### Phase 3: Smart features
- [ ] **On-device AI parser fallback** (Gemini Nano via ML Kit GenAI / AICore when available; heuristic engine otherwise)
- [ ] **FX fee exposer** notification after foreign transactions + yearly card-cost report
- [ ] **Trip mode auto-start** when a new foreign currency appears
- [ ] **Safe-to-spend widget** (Glance)
- [ ] Ask screen (chat with your money)
- [ ] Subscription manager, bills calendar, goals, debts/IOUs

### Phase 4: Sharing and sync
- [ ] E2E-encrypted sync, household budgets, Splitwise-style groups
- [ ] Email forwarding inbound parser (server), statement PDF import
- [ ] Remote signed parser-template updates

### Phase 5: Play Store release
- [ ] SMS Permissions Declaration (money-management exception); a notification-only build flavour as fallback
- [ ] Data Safety form, privacy policy, DPDP/GDPR deletion + export
- [ ] Re-check the Play SMS policy update effective 27 Jan 2027
