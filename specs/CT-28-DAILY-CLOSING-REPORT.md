# CT-28 Daily Closing — Session Review Implementation Report

**Date:** 29 October 2026  
**Status:** Partial native session-close review implemented; emulator-rendered, persisted and manually exercised. Not a complete calendar-day close.

## Implemented

- Native CT-28 review screen is reachable from Counter and Shop Home.
- Lists up to ten most recent closed cash-drawer sessions and lets the operator select one.
- Reports the selected session's opening cash, staff-declared cash sales and repayments, cash refunds, cash expenses, supplier settlements, explicit cash-in/out, expected cash, physical count and variance.
- Current open orders, pending UPI and customer receivables appear separately as current shop follow-ups. The screen explicitly says these are not limited to the selected session.
- The operator can persist one review acknowledgement and optional note per closed session. It is an audit/review marker only; it does not alter cash, collect UPI, clear a variance, close an order or settle credit.
- SQLCipher schema v13 adds an additive `cash_closing_reviews` table. Existing v12 cash session data remains intact.

## Verification

Latest full run on API 37 emulator, 29 October 2026:

| Check | Result |
|---|---:|
| Node | 48/48 passed |
| Kotlin JVM | 28/28 passed |
| Android instrumentation | 21/21 passed; 0 failures/errors/skips |
| Debug and instrumentation APK builds | Passed |
| Android lint | 0 errors, 74 warnings |
| v8→v13 migration | Passed; synthetic order/payment rows preserved |
| Manual UI and restart | Opened and counted synthetic ₹50.00 session, acknowledged review, force-stopped/relaunched; acknowledgement and ₹0 variance remained visible |

Automated coverage also creates a synthetic cash sale, cash expense, pending UPI order and open draft. The UI projection asserts ₹63.00 expected, ₹60.00 counted, unresolved -₹3.00 variance, one open order and ₹15.00 pending UPI; review acknowledgement persists across database reopen.

Evidence: [post-restart closing screen](../test-evidence/2026-10-29/ct28-restart.png), [post-restart hierarchy](../test-evidence/2026-10-29/ct28-restart.xml), [pre-acknowledgement screen](../test-evidence/2026-10-29/ct28-closing.png), [Kotlin report](../android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml), and [Android instrumentation report](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml).

## Limits and next steps

This is session-based, not calendar-day accounting. Sales and cash events outside a drawer session are unlinked and excluded. The current unresolved order, pending-UPI and receivable figures are current shop totals, not session-period totals. No stock count, staff identity/approval, denomination breakdown, close-out export, accounting-period lock, owner review or multi-register workflow exists. Staff declarations are not independently verified. CT-27 staff shifts, accurate daily attribution and broader daily reporting remain to be built; do not use this as a production close process.

## Superseding continuation — schema v14

CT-27 now adds partial cash-linked shift/handover data to drawer sessions. Staff and receiving labels are operator-entered, not authenticated identities. CT-28 remains a selected-session review and still does not provide calendar-day attribution, stock closing counts, approvals, accounting-period controls, or complete daily reporting. Current schema v14 adds the shift metadata while keeping the close-review record compatible.
