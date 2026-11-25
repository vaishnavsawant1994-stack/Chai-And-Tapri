# CT-24 customer repayment tender support — 25 November 2026

## Delivered

Customer repayments can now be recorded as either cash or UPI from Customer Credit and Customer Details. The operator chooses the tender before entering the amount. Both options remain staff-declared; the UPI wording explicitly says it is not provider-verified. The existing allocation logic continues to settle the oldest eligible receivables and updates the corresponding order balances atomically.

The payment ledger stores the selected tender. Only cash repayments are associated with an active cash drawer and included in physical cash totals. UPI repayments do not increase the cash drawer. No database migration was needed: the durable payment entry already has method and status fields, and the repayment sync contract transmits its tender through the existing payment-entry projection. The prototype transaction engine now supports the same cash/UPI method contract.

## Verification

- Kotlin JVM: **92/92 passed** (`testDebugUnitTest`). The repayment test covers cash and UPI tenders, staff-declared status, invalid tender rejection, allocation, overpayment rejection and resulting customer/order balances.
- Focused API 37 emulator instrumentation: **1/1 passed**, `SqliteCounterPersistenceTest.quickSaleSplitTenderFlowsThroughCustomerRepaymentAndReconcilesBalance`. It selects UPI in the native customer-credit UI, records a partial repayment, verifies the reduced receivable and persisted UPI ledger event, and verifies cash totals remain unchanged. XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.
- Prototype Node suite: **52/52 passed**.
- Backend Node suite without a configured database: **33 passed, 6 PostgreSQL-dependent skipped, 0 failed**.
- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug`: **passed**. Lint: **0 errors, 189 warnings**.
- `git diff --check`: passed.

## Qualification limits

The complete Android instrumentation regression was not rerun for this slice. The focused emulator result is synthetic and emulator-only. UPI remains a staff declaration; no provider verification or bank integration was added. CT-24 remains partial, and the product, security, cross-device recovery, physical-device, field and release gates remain open.

## Broad Android regression follow-up

A post-change `connectedDebugAndroidTest` attempt was started after the focused test passed, but the full runner did not produce JUnit output. The AVD remained on `LocalAccessActivity`; emulator logs showed Android launcher ANRs and heavy kernel CPU use. I stopped the unresponsive run; it has no completed pass/fail count and is **incomplete**, not a suite pass. The focused CT-24 instrumentation test and JVM/build/lint results above remain valid.
