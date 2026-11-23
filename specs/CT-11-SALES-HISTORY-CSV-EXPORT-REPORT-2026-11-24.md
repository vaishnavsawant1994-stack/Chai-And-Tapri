# CT-11 Sales History CSV Export — 24 November 2026

## Delivered

Sales History now exports the currently filtered local order set through Android's Storage Access Framework. The export contains one row per order, order creation timestamp when available, item summary, fulfillment/payment state, paise totals, cancellation state, and a summary of the recorded payment events. It includes an evidence note that timestamps come from the device clock and staff-declared tenders are not provider-verified.

Customer identifiers and internal payment-entry IDs are not included. CSV cells are quoted, embedded quotes escaped, and formula-leading text is prefixed to reduce spreadsheet formula execution risk. Export is read-only and cancellation or destination failure does not mutate business records.

## Verification

- Kotlin JVM suite: **75 passed, 0 failed**. Includes two `SalesHistoryCsvTest` cases for payment evidence/privacy and escaping/formula handling.
- Android API 37 focused instrumentation: **1 passed, 0 failed/skipped**. `filteredHistoryOpensSafExportAndLeavesLedgerUnchanged` created a synthetic UPI-pending order, filtered history, opened the real SAF picker, saved a CSV, observed success, and compared the complete encrypted persistence snapshot before and after.
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`: passed; lint reports **0 errors / 176 warnings**.
- Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` and `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.SalesHistoryCsvTest.xml` (generated local build outputs).

## Limits

This is a synthetic emulator path, not manual usability or browser visual qualification. CSV rendering is covered by JVM tests; the instrumentation test verifies the actual picker/save callback and read-only ledger behavior, but does not independently reopen the downloaded file. CT-11 and the overall Android app remain partial. CAT-046 browser review, physical-device/field testing, complete financial reporting, and production readiness remain open.
