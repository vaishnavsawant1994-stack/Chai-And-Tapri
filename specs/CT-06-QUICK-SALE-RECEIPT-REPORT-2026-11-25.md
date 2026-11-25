# CT-06 Quick Sale receipt and order handoff — 25 November 2026

## Delivered

After a successful Quick Sale checkout, CT-06 now keeps an on-screen receipt for the latest recorded sale. It reads the committed order and payment ledger from the same local transaction model, lists line quantities and sale total, and labels tender evidence accurately:

- Cash is shown as staff-declared.
- UPI attempts remain pending and explicitly not verified.
- UPI receipts remain staff-declared and not provider-verified.
- Udhar is identified as a receivable, not collected money.
- Remaining due is shown when present.

The receipt's Open Order action opens CT-09 Order Details for the persisted order. No second sale, payment, stock movement or audit entry is created by viewing the receipt.

## Verification

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; 80 Kotlin JVM tests passed, 0 failed.
- Focused API 37 emulator test `quickSaleUiRecordsCashAndUpiPendingAsDifferentPaymentStates`: **1/1 passed**. It performs cash checkout and UPI-pending checkout in the actual UI, checks the tender labels, reconciles persisted order/payment/stock state, then opens the latest order details from the receipt.
- `lintDebug`: **BUILD SUCCESSFUL**, 183 warnings reported; no lint task failure. Report: `android/app/build/reports/lint-results-debug.html`.
- Full serial-pinned default API 37 instrumentation regression after both current CT-06/CT-07 changes: **139/139 passed**, 0 failures, 0 errors, 0 skipped. JUnit: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.

Synthetic emulator data only. This is a connected Quick Sale → receipt → order detail slice; CT-06 remains partial. It does not qualify physical scanners, provider-verified payments, all tender paths, browser visuals, field use, production readiness, or the full Android screen set.
