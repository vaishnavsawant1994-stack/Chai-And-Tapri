# CT-11/CT-12 split-tender refund slice — 23 November 2026

## Result

Added an API 37 emulator instrumentation journey from Sales History into the audited refund form using synthetic data. The sale is ₹24, funded by ₹5 staff-declared cash and ₹19 customer credit. Returning one delivered ₹12 item allocates the refund against the outstanding receivable first.

The same UI journey then records an authorized ₹8 amount correction and explicitly checks the staff-declared cash-payout confirmation. Across both audited actions, the ₹19 receivable is reversed first and only the final ₹1 is returned as staff-declared cash. Final state: ₹20 refunded, ₹4 net cash retained, no customer credit or due; the item return is recorded; original stock remains reduced by the two sold units; no restock movement is created; the ledger has a ₹12 item credit reversal, a ₹7 further credit reversal, and a ₹1 cash refund; audit history retains the corrections.

## Verification

- Focused API 37 Android instrumentation: 1/1 passed (59.631 seconds), XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.
- Kotlin JVM suite: 68/68 passed.
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`, and `testDebugUnitTest` completed successfully. Lint: 0 errors, 175 warnings.

## Qualification boundary

This qualifies one synthetic emulator allocation path across item return and amount correction from the connected Sales History → Refund flow. It does not qualify all refund allocations, cancellations, role permissions, physical-device behavior, real shop use, or production accounting. CT-11 and CT-12 remain partial modules. The ₹1 cash payout is explicitly staff-declared, not externally verified. No stock was restocked because returnability policy is not configured for this flow.
