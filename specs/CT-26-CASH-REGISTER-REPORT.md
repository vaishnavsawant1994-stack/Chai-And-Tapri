# CT-26 Cash Register — Implementation Report

**Date:** 29 October 2026  
**Status:** Partial local workflow implemented; selected model, encrypted persistence, additive migration, emulator screen and manual UI session flow qualified.

## Implemented

- Native Cash Register screen reachable from the Counter and Shop Home.
- Durable drawer sessions with staff-entered opening cash, one active session at a time, cash-in/cash-out events with reasons, and staff-entered physical close count.
- Expected cash is calculated as opening cash plus session-linked cash collections and repayments, minus session-linked cash refunds, cash operating expenses and cash supplier settlements, plus/minus explicit cash drawer movements.
- Cash payment, expense and supplier-payment events are linked to the active drawer session when they are created. Events recorded outside an open session remain unlinked and are excluded from that session; the UI states this clearly.
- Cash-out events cannot exceed currently expected drawer cash. A difference between expected and counted cash remains a visible variance; the system does not silently adjust the books.
- SQLCipher schema v12 additively adds session links to existing financial tables and creates session/movement tables. Historical records stay unlinked; no old shift is inferred.
- Session start, cash movements and close use the existing atomic command, audit, idempotency and outbox commit path. Session state survives database reopen.
- Cash expense is already deducted from expected cash. The Expenses screen warns not to duplicate the same transaction as a separate cash-out movement.

## Verification

Latest full local run, API 37 emulator, 29 October 2026:

| Check | Result |
|---|---:|
| Node | 48/48 passed |
| Kotlin JVM | 28/28 passed |
| Android instrumentation | 20/20 passed; 0 failures/errors/skips |
| Debug and instrumentation APK builds | Passed |
| Android lint | 0 errors, 73 warnings |
| v8→v12 additive migration | Passed; existing order/payment records preserved |
| Manual emulator UI | Opened session with ₹50.00, counted ₹50.00 at close, UI showed expected ₹50.00 and variance ₹0.00 |

Automated tests cover cash sale and cash expense attribution, expected-cash arithmetic, overdraw rejection, unresolved variance, idempotency, encrypted reopen, session history rendering and migration from a synthetic v8 schema.

Evidence: [opened session screenshot](../test-evidence/2026-10-29/ct26-opened.png), [closed session screenshot](../test-evidence/2026-10-29/ct26-closed.png), [closed session hierarchy](../test-evidence/2026-10-29/ct26-closed.xml), [emulator test report](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml), [Kotlin report](../android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml), and [lint report](../android/app/build/reports/lint-results-debug.txt).

## Limits

This is not a full daily cash-control or shift module. The app has no staff identity, multi-register handling, drawer handover, denomination counting, dated report/export, approval, background cash event reconciliation or authenticated sync. Staff-entered cash remains declared, not independently verified. Transactions recorded while no drawer is open are intentionally excluded and must be reconciled separately. Cash expenses and cash-out movements must not be double-entered. CT-27 shifts and CT-28 closing remain incomplete; production accounting and field qualification are not claimed.


## Later schema extension

CT-28 subsequently added the additive schema v13 `cash_closing_reviews` table referencing closed drawer sessions. CT-26 cash-register behavior remains as tested above; latest project test totals are in [CT-28 report](CT-28-DAILY-CLOSING-REPORT.md).

## Superseding schema note - CT-27

SQLCipher schema v14 adds optional operator and handover labels/notes to cash drawer sessions. CT-27 starts and ends shifts through the same audited cash-session transaction path. This extends the cash-register prototype but does not authenticate staff or provide multiple drawers. See [CT-27 report](CT-27-STAFF-SHIFTS-REPORT.md).
