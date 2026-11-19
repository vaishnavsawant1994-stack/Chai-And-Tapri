# CT-25 Expenses — Implementation Report

**Date:** 29 October 2026  
**Status:** Partial local workflow; dated expense history and device-local daily totals are implemented and covered by JVM, encrypted persistence, snapshot/restore and API 37 emulator regression tests.

## Implemented

- Native Expenses screen reachable from Counter and Shop Home.
- Operating expense categories for rent, electricity, wages, transport, cleaning, repair and other.
- Exact rupee input converted to integer paise; accepted methods are cash, UPI and card.
- All methods are labeled staff-declared. No external payment verification is claimed.
- Optional note limited to 160 characters.
- Append-only expense records, command idempotency, audit event and sync outbox entry posted atomically through the existing write-barrier-protected persistence path.
- Encrypted SQLCipher schema v11 added the expenses table; current schema v12 adds optional drawer-session attribution. Migrations are additive; v8-to-v12 migration preserves existing order data and creates procurement, expense and drawer tables.
- Device-clock timestamp is recorded for new expenses using the existing `created_at` column; no schema migration was needed. Device-local calendar-day totals and dated history are shown with the device timezone explicitly disclosed.
- Legacy rows with missing/zero timestamps remain undated and are reported separately as “date unavailable”; the app does not invent historical dates.
- Snapshot restore preserves an expense timestamp exactly. This timestamp is device-clock supplied and not independently verified; it is not a trusted cross-device business date.
- Supplier purchases/payments are kept separate from operating expenses. Owner withdrawals and cash-register opening/closing are not represented as expenses.

## Verification

Latest current-source full run, 19 November 2026:

| Check | Result |
|---|---:|
| Node | 52/52 passed (unchanged suite; not rerun for this slice) |
| Kotlin JVM | 57/57 passed |
| API 37 emulator instrumentation | 90/90 passed; no failures/errors/skips |
| Debug and instrumentation APK builds | Passed |
| Android lint | 0 errors, 161 warnings |

New JVM coverage verifies grouping by device-local date and separating unknown legacy dates. API 37 coverage verifies exact timestamp persistence and daily summary rendering after encrypted database reopen, and timestamp retention through encrypted portable snapshot restore into a separate store. The full API 37 suite also passed. The test data is synthetic; a fresh manual UI expense submission was not repeated for this date-summary slice.

Evidence: [saved screen](../test-evidence/2026-10-29/ct25-saved.png), [post-restart screen](../test-evidence/2026-10-29/ct25-restart.png), [post-restart UI hierarchy](../test-evidence/2026-10-29/ct25-restart.xml), [Kotlin JVM report](../android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml), and [Android instrumentation report](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml). The manual record is synthetic emulator data, not a real shop expense.

## Limits and next step

Dates reflect the device clock and timezone at entry, which may be wrong or changed; they are not server receipt dates. There is no recurring expense workflow, attachment/evidence capture, category management, approval, export, or expense-specific staff attribution. Expense records/outbox are not end-to-end sync-qualified, and these totals do not establish complete expense coverage or profit. Daily summaries are local convenience reporting, not accounting close or tax reports.


## Later schema extension

Schema v12 added optional session attribution for cash expenses; schema v13 added the separate cash-session closing review. The expense model now carries an optional recorded timestamp; no schema migration was required because `created_at` was already present. Legacy unknown dates remain unknown.
