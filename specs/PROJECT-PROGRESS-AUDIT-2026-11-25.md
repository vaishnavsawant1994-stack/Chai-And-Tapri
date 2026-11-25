# Chai And Tapri OS — Progress Addendum

**Updated:** 25 November 2026

This addendum records work completed after the 24 November progress audit. It does not replace broader product scope or mark the MVP complete.

## CT-09 cancel unhanded remainder

The Order Details screen now allows managers/owners to cancel only the unhanded remainder of an open or partially handed-over order. It requires a bounded reason, explicit review and confirmation, and a current revision. A focused API 37 UI test passed 1/1 and verified the delivered-sale, stock, payment, and audit state; Kotlin JVM passed **84/84**, APK tasks passed, and lint reported 0 errors / 187 warnings. The full API 37 suite was not rerun after this slice; Node tests were not rerun. Evidence: [CT-09 report](CT-09-CANCEL-UNHANDED-REMAINDER-REPORT-2026-11-25.md) and [focused XML](../test-evidence/2026-11-25/ct09-cancel-remainder-focused-instrumentation.xml). The UI does not refund or restock; use the separate refund flow when appropriate.

## CT-09 correction reason capture

Order Details correction of an unhanded quantity now requires a manager/owner-entered reason, validated before mutation and retained in the audited event/outbox. CT-07 reviewed voice removal requests the same reason and rechecks the proposal identity before commit. Kotlin JVM **83/83**; API 37 connected instrumentation **140/140** (0 failures/errors/skips); debug and Android-test APK builds passed; `lintDebug` passed with 0 errors / 187 warnings. Node was not rerun for this Android-only slice. Evidence: [CT-09 report](CT-09-CORRECTION-REASON-REPORT-2026-11-25.md), [full instrumentation XML](../test-evidence/2026-11-25/ct09-correction-reason-full-instrumentation.xml). CT-09 remains partial; hardware, field, independent security, cross-device recovery, deployment and production gates remain open.

## CT-08 multi-line fulfillment

Live Orders can hand over quantities from several product lines in one validated transaction. Focused API 37 multi-line and single-line UI cases passed 1/1 each; Kotlin JVM 82/82; APK and lint tasks passed; lint reports 186 warnings. Full suite: 139/140, with one Order Details launch timeout that passed focused retest; the broad suite is not green/qualified. See [CT-08 report](CT-08-MULTI-LINE-PARTIAL-HANDOVER-REPORT-2026-11-25.md).

## CT-06 Quick Sale receipt handoff

Quick Sale now shows a receipt sourced from the committed order and payment ledger after checkout and provides a direct route to Order Details. Focused API 37 cash/UPI UI-to-ledger-to-order-details test passed **1/1**; the full default Android regression passed **139/139**; Kotlin JVM **80/80**; APK tasks passed; `lintDebug` passed with 183 warnings. Details: [CT-06 receipt report](CT-06-QUICK-SALE-RECEIPT-REPORT-2026-11-25.md).

## CT-07 transcript review safety

The native counter now invalidates its pending structured command as soon as the recognized/typed transcript changes. The UI tells the operator to propose again. This prevents Apply from committing a command that was parsed from text different from the text currently visible for review.

Evidence: [CT-07 reviewed transcript report](CT-07-REVIEWED-TRANSCRIPT-INVALIDATION-REPORT-2026-11-25.md).

## Current verification

- Kotlin JVM tests: **80 passed, 0 failed**; debug and instrumentation APK build tasks passed via `testDebugUnitTest assembleDebug assembleDebugAndroidTest`.
- Focused API 37 emulator instrumentation: **1/1 passed** for stale proposal invalidation, no mutation on stale Apply, and successful re-proposal of the edited one-item command.
- Lint: **passed**, 183 warnings reported; no lint task failure.
- The focused test uses a synthetic owner and product. Its initial failed run was due to a missing test catalog fixture; the fixture was added and the passing run is the current result.

## Product gates unchanged

CT-07 remains a one-utterance, review-before-apply prototype. Continuous recognition, physical Bluetooth microphone routing, noisy-shop speech qualification, full MVP screen coverage, browser visual verification, independent security review and production deployment remain unqualified. The recent fixture test does not change those statuses.

The broader last recorded test baselines are in [the 24 November audit](PROJECT-PROGRESS-AUDIT-2026-11-24.md) and subsequent CAT-052/Android qualification entries in [PROJECT_PLAN.md](../PROJECT_PLAN.md). They are historical suite results, not rerun by this focused change.

## CT-05 Shop Home daily metrics — 25 November 2026

Implemented and emulator-tested a date-scoped activity section using the existing ledger projection. Kotlin JVM 82/82; APK builds pass; focused API 37 Shop Home instrumentation 1/1; full API 37 connected instrumentation 140/140 (0 failures/errors/skips); lint 0 errors / 186 warnings. Date attribution remains device-local; this does not qualify a full daily close, profit reporting, or the complete screen. Details: [CT-05 report](CT-05-SHOP-HOME-DAILY-METRICS-REPORT-2026-11-25.md).
