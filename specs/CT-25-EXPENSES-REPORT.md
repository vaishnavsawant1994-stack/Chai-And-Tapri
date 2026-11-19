# CT-25 Expenses — Implementation Report

**Date:** 29 October 2026  
**Status:** Partial local prototype; selected validation, encrypted persistence, screen rendering, manual form submission and process-restart persistence are tested.

## Implemented

- Native Expenses screen reachable from Counter and Shop Home.
- Operating expense categories for rent, electricity, wages, transport, cleaning, repair and other.
- Exact rupee input converted to integer paise; accepted methods are cash, UPI and card.
- All methods are labeled staff-declared. No external payment verification is claimed.
- Optional note limited to 160 characters.
- Append-only expense records, command idempotency, audit event and sync outbox entry posted atomically through the existing write-barrier-protected persistence path.
- Encrypted SQLCipher schema v11 added the expenses table; current schema v12 adds optional drawer-session attribution. Migrations are additive; v8-to-v12 migration preserves existing order data and creates procurement, expense and drawer tables.
- Local lifetime total and reverse-chronological history are shown. Dates and daily reports are not yet available.
- Supplier purchases/payments are kept separate from operating expenses. Owner withdrawals and cash-register opening/closing are not represented as expenses.

## Verification

Latest local full run, 29 October 2026:

| Check | Result |
|---|---:|
| Node | 48/48 passed |
| Kotlin JVM | 27/27 passed |
| API 37 emulator instrumentation | 19/19 passed; no failures/errors/skips |
| Debug and instrumentation APK builds | Passed |
| Android lint | 0 errors, 70 warnings |

New coverage checks input validation, duplicate command behavior, rollback on persistence failure, encrypted-database reopen, audit event persistence, additive migration and rendering of a synthetic expense on the native screen. On the API 37 emulator, the expense form was manually submitted with synthetic rent ₹250.50, cash (staff-declared), and a synthetic note. The updated lifetime total and history appeared. After force-stopping and relaunching the app, the same total and record remained visible.

Evidence: [saved screen](../test-evidence/2026-10-29/ct25-saved.png), [post-restart screen](../test-evidence/2026-10-29/ct25-restart.png), [post-restart UI hierarchy](../test-evidence/2026-10-29/ct25-restart.xml), [Kotlin JVM report](../android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml), and [Android instrumentation report](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml). The manual record is synthetic emulator data, not a real shop expense.

## Limits and next step

Expense date attribution is not exposed in the current record model. There is no recurring expense workflow, attachment/evidence capture, category management, approval, export, staff identity, or authenticated sync. The lifetime total is not a daily figure and does not establish complete expense coverage or profit. Implement CT-26 cash drawer and shift events next, then CT-28 closing with counted-versus-expected cash and explicit unresolved variances.


## Later schema extension

Schema v12 added optional session attribution for cash expenses; schema v13 added the separate cash-session closing review. The expense record model and limitations above remain unchanged.
