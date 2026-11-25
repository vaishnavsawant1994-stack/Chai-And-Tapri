# Chai And Tapri OS — Progress Addendum

**Updated:** 25 November 2026

This addendum records work completed after the 24 November progress audit. It does not replace broader product scope or mark the MVP complete.

## CT-09 pre-fulfillment price correction

An owner/manager can adjust an order-line unit price only before any fulfillment or financial event. The UI requires a paise-precise decimal rupee price, reason, review, explicit confirmation, and current revision. It changes the line snapshot only; catalogue price, stock, payment and credit ledgers stay unchanged. The reason is local audit data and is excluded from the outbound projection. Kotlin JVM **86/86**; focused API 37 UI **1/1**; debug and Android-test APK builds passed; lint 0 errors / 187 warnings. Full Android and Node suites were not rerun. Evidence: [report](CT-09-ORDER-PRICE-CORRECTION-REPORT-2026-11-25.md), [focused XML](../test-evidence/2026-11-25/ct09-price-correction-focused-instrumentation.xml). Repricing after partial fulfillment remains unsupported.

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

## CT-15 owner-configured low-stock thresholds

Inventory Overview now shows active products at or below owner-configured thresholds only when physical stock is confirmed. Unknown stock is omitted with a disclosure; the alert is not a purchase recommendation. Focused API 37 instrumentation passed 1/1, including an unchanged persisted snapshot assertion. Kotlin JVM 86/86; debug and instrumentation APK tasks passed; lint 0 errors / 187 warnings. Node and full connected instrumentation were not rerun. See [CT-15 report](CT-15-LOW-STOCK-THRESHOLD-REPORT-2026-11-25.md) and [focused XML](../test-evidence/2026-11-25/ct15-inventory-low-stock-focused-instrumentation.xml). CT-15 remains partial; hardware, security review and production gates remain open.

## CT-16 stock count history CSV export

Added a local audit-history CSV export using Android's SAF picker. It preserves unavailable legacy quantities as unknown, uses explicit device-clock/actor caveats, safely quotes formula-leading text, and does not mutate inventory. The focused API 37 save-picker test passed 1/1; Kotlin JVM 88/88; debug/test APK builds passed; lint 0 errors / 189 warnings. Node suite passed 52/52; the full Android instrumentation suite was attempted but incomplete. The real saved synthetic file is inspected at [CSV evidence](../test-evidence/2026-11-25/ct16-count-history-export/ct16-count-history-synthetic.csv); full scope and limitations are in the [CT-16 report](CT-16-STOCK-COUNT-HISTORY-CSV-REPORT-2026-11-25.md).

## 25 November continuation � CT-13/14 base-unit labels

This supersedes only the product-catalogue unit detail in the audit above. Products now store a validated sell/count base-unit label visible through Quick Sale/cart/receipt, order and stock workflows, and count-history CSV. SQLite v30?v31 migration is additive and defaults legacy products to `units`; migration evidence confirms existing sale and stock records persist. Labels cannot be changed once ledger activity exists. No purchase-unit conversion or product variants are implemented. Verification: Kotlin JVM 89/89; focused API 37 Quick Sale UI test 1/1; focused migration test 1/1; debug and instrumentation APK builds PASS; lint 0 errors/189 warnings. Node suite passed 52/52; the full Android instrumentation suite was attempted but incomplete. Full report/evidence: [CT-13/14 base-unit report](CT-13-14-BASE-UNIT-REPORT-2026-11-25.md), `../test-evidence/2026-11-25/ct13-base-unit/`. CT-13/14 and Wave 2 remain partial; no production/field qualification is implied.

Broad Android regression caveat for this slice: 28 recorded cases passed and one existing cashier sign-in UI test failed with an empty JUnit body before the emulator disappeared from ADB. The suite was incomplete; two AVD restart attempts did not restore a test device. Do not treat this as a full-regression pass. Detailed evidence: ../test-evidence/2026-11-25/ct13-base-unit/full-suite-attempt.txt.

## Subsequent continuation - CT-13/14 base-unit metadata sync - 25 November 2026

This supersedes the local-only statement above. Optional sell/count labels now travel with accepted transaction projection events and appear in owner product-performance and inventory projections. The backend validates labels and locks the first accepted value per product ID; older unlabeled events remain compatible. This is not full product-catalogue synchronization. Latest source verification: backend Node 31 passed / 5 PostgreSQL skips / 0 failed; Kotlin JVM 90/90; prototype Node 52/52; Android debug and instrumentation APK builds passed; lint 0 errors / 189 warnings. Connected Android regression remains failed/incomplete: 28 cases passed before the staff sign-in UI test encountered an Android System UI ANR. Exact failure is documented in [the slice report](CT-13-14-UNIT-SYNC-OWNER-DASHBOARD-REPORT-2026-11-25.md). No live PostgreSQL integration run was included in this slice.

Verification correction for this addendum: after adding the cashier unit-label authorization case, the final backend suite is **32 passed, 0 failed, 5 PostgreSQL-dependent skipped** (37 total). Kotlin JVM remains 90/90; prototype remains 52/52; Android debug and instrumentation APK builds passed; lint 0 errors / 189 warnings. The earlier 31-pass line above is superseded.

PostgreSQL qualification update for CT-13/14: a fresh loopback-only PostgreSQL 17 cluster with migrations 001-004 and a restricted runtime role passed **38/38, 0 failures, 0 skips**. The new integration case verified accepted unit metadata in owner inventory and product projections, duplicate retry, rejected relabel, stale revision conflict and preservation of the accepted value. The database cluster was stopped. Logs: `test-evidence/2026-11-25/cat13-unit-sync-postgres/`. No connected Android UI test was completed for this metadata sync slice; broad regression remains incomplete after the separately recorded System UI ANR.
