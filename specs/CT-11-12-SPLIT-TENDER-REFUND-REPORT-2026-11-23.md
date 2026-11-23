# CT-11/CT-12 split-tender refund slice — 23 November 2026

## Result

Added an API 37 emulator instrumentation journey from Sales History into the audited refund form using synthetic data. The sale is ₹24, funded by ₹5 staff-declared cash and ₹19 customer credit. Returning one delivered ₹12 item allocates the refund against the outstanding receivable first.

The test verifies that after the refund: total refunded is ₹12; collected cash remains ₹5; customer credit and order credit balance are ₹7; the order due is zero; one item is marked returned; original stock remains reduced by the two sold units; no refund-restock movement is created; the payment ledger contains a ₹12 credit reversal and no cash-refund entry; and the audit history contains the refund.

## Verification

- Focused API 37 Android instrumentation: 1/1 passed (35.771 seconds), XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.
- Kotlin JVM suite passed as part of the same Gradle invocation; current XML reports 56 CounterModel, 3 customer picker, 2 stock-count review, 1 daily report projection, 2 CSV, 1 expense summary and 3 stock-count history tests (68 total).
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`, and `testDebugUnitTest` completed successfully. Lint: 0 errors; warnings remain (the run summary did not print a refreshed count).

## Qualification boundary

This qualifies one synthetic emulator refund allocation and the connected Sales History → Refund flow. It does not qualify all refund allocations, cash-payout confirmation, cancellations, role permissions, physical-device behavior, real shop use, or production accounting. CT-11 and CT-12 remain partial modules. No cash refund was issued in this scenario; the existing receivable absorbed the item refund. No stock was restocked because returnability policy is not configured for this flow.
