# CT-09 â€” Order Details Implementation Report

**Updated:** 1 November 2026  
**Status:** Partial, read-only native detail flow. The timestamped timeline change is build and source-tested; the latest UI revision has not been rerun on an emulator.

## Delivered

- Order Details is linked from Live Orders and Sales History and reads the selected order from encrypted local persistence.
- It displays fulfillment/payment state, revision, order lines, ordered/delivered/returned quantities, unit and delivered values, collections, customer credit, refunds, and outstanding due.
- It shows order-linked payment/credit entries, stock movements, and matching persisted audit events.
- Audit rows now include the event's persisted `created_at` value, rendered in the device's local timezone as `Local time: dd MMM yyyy, HH:mm:ss`.
- The timeline labels the timestamp as device-clock local time and warns it may be inaccurate and is not server-verified. It does not imply synchronized or authoritative time.
- The screen remains read-only. Refunds and corrections use validated engine operations; historical records are not silently edited.
- Unknown order numbers show an explicit empty/error state and do not create replacement data.

## Verification

- Node: 52/52 passed (`test-evidence/2026-11-01/ct09-audit-timeline-node.log`).
- Kotlin JVM: 48/48 passed after the barrier hardening continuation (45 `CounterModelTest` + 3 `CustomerAccountPickerTest`), 0 failures/errors.
- Debug APK and instrumentation APK build tasks passed; debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- Android lint passed with 0 errors and 141 warnings.
- Android instrumentation sources and APK compiled, but no device was connected to ADB for the focused rerun. The latest full API 37 run (75/75) predates both the latest SAF cleanup and this timestamped timeline change; it must not be treated as runtime qualification of this revision.
- Gradle output for the latest combined source checks: `test-evidence/2026-11-01/cat051b-barrier-gradle.log`; the earlier CT-09-specific run is `test-evidence/2026-11-01/ct09-audit-timeline-gradle.log`.

## Remaining scope

The screen still lacks order-line addition, price edits, detailed correction reasons, full refund/cancellation workflows from Order Details, search/filter, receipt/export, server history, trusted server time, and cross-device ordering guarantees. CT-09 and the broader product remain partial and are not production-qualified.

## Superseding CT-09 correction slice - 2 November 2026

Order Details now offers a manager/owner action to correct the unhanded quantity on an open order. It requires explicit confirmation, uses the current order revision, and routes through the existing `remove` transaction command. The persistence boundary rejects the same command from a cashier. Audit history records the correction and authenticated actor. The flow does not modify already handed-over quantities, payment events, or stock movements; it cannot remove the final remaining line (the order must instead follow its cancellation path).

API 37 emulator coverage exercised the actual correction button and dialog, verified the remaining line and incremented revision, and checked unchanged payment and inventory ledgers. The actor test also covers cashier rejection and manager acceptance. Current full run: Android instrumentation 88/88; Kotlin JVM 56/56; Node 52/52; backend 11 passed / 4 PostgreSQL-dependent tests skipped; APK builds PASS; lint 0 errors / 161 warnings. Evidence: `../test-evidence/2026-11-02/ct09-order-line-correction-full-instrumentation.xml`, `../test-evidence/2026-11-02/ct09-order-line-correction-full-gradle.log`, and the CT-03 authorization evidence in `../test-evidence/2026-11-02/ct03-manager-customer-profile-verification.md`.

This closes only one edit path: adding an item from details, changing prices/discounts, detailed correction reasons, and correction of handed-over items are not implemented here. CT-09 remains partial; role/action policy and real-shop usability review remain open.

## Superseding order-item addition slice - 2 November 2026

Open orders in TAKEN, PREPARING, or READY status now expose `Add item to order` for an authenticated operator. The picker uses active products with owner-confirmed positive prices; a quantity dialog requires explicit confirmation and states that adding the line neither hands it over nor records payment. The command uses the canonical catalogue price, a unique command ID, and the order's current revision. The transaction engine updates the order and actor-attributed audit history; sale/payment and stock posting do not occur at line-add time. The action is hidden for other fulfillment states and unauthenticated users.

An API 37 UI regression verifies cashier access to this limited add path, product selection and confirmation, total/revision changes, actor attribution, and the absence of payment and stock events. It passed focused instrumentation 1/1. That regression also caught and fixed an Android dialog composition defect: setting both a message and a list on one `AlertDialog` hid the product rows. The order total now appears in the dialog title so the configured list remains visible.

The latest full 89-case Android run completed with 87 pass and 2 failures: `startActivitySync` exceeded its 45-second timeout when launching Device Permissions and Cloud Sync. Each screen subsequently passed its focused API 37 test (1/1); evidence: `../test-evidence/2026-11-02/device-permissions-screen-focused.xml` and `../test-evidence/2026-11-02/cloud-sync-owner-screen-focused.xml`. Earlier full attempts also exposed accessibility timing issues in split payment and Quick Sale dialogs. The split test now taps coordinates and waits for dialog transitions; its focused rerun passed 1/1 (`../test-evidence/2026-11-02/payment-screen-split-cash-udhar-focused.xml`). Quick Sale cash/UPI pending also passed 1/1, with retained XML at `../test-evidence/2026-11-02/quick-sale-cash-upi-pending-focused.xml`. The separate current-source build/JVM/lint run passed: debug and instrumentation APK tasks, Kotlin JVM 56/56, lint 0 errors / 161 warnings. Node was 52/52; backend 11 passed / 4 PostgreSQL-dependent tests skipped. The full instrumentation suite is not green; the emulator remains resource constrained and full-suite stability qualification is open. Detailed result: `../test-evidence/2026-11-02/ct09-add-item-android-verification.md`.

## Remaining scope

Price edits/discounts, detailed correction reasons, editing handed-over items, expanded refund/cancellation workflows and search/filter/export remain unimplemented here. CT-09 and the broader product remain partial; role policy, real-shop usability and regression stability qualification remain open.

