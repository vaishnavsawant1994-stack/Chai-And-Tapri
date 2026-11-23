# CT-06 / CT-24 Quick Sale to Customer Repayment Report — 23 November 2026

## Implemented workflow

Extended the Android instrumentation scenario to cover the connected operator journey: select a saved customer in Quick Sale, post a synthetic ₹15 sale with ₹5 staff-declared cash and ₹10 Udhar, reopen persisted state, navigate to Customer Credit, record a ₹4 cash repayment, then reopen the encrypted local model and reconcile the result.

## Verified result

Focused API 37 emulator instrumentation passed **1/1** (`quickSaleSplitTenderFlowsThroughCustomerRepaymentAndReconcilesBalance`), with zero failures, errors or skips. The JUnit result is `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.

Persisted assertions: original sale ₹15; cash collections ₹9 (₹5 sale cash plus ₹4 repayment); outstanding customer receivable ₹6; order outstanding credit ₹6; order due ₹0; one ₹4 customer-repayment payment event marked `staff_declared`; matching audit event; stock remains 2 after selling 1 from opening stock 3. The order's `creditPaise` is an outstanding balance that decreases on repayment; the CustomerCredit ledger retains original amount and repaid amount separately. No external payment verification is claimed.

## Scope and remaining work

This is one synthetic UI journey, not full CT-06/CT-21/CT-24 qualification. No full Android suite, Kotlin JVM suite, APK build, lint, PostgreSQL suite, real customer data, physical device, browser visual review or production workflow was run for this test-only change. Customer selection, multi-credit allocation, repayment reversal, refunds after repayment, concurrency and offline/background operation require separate coverage. Full customer management and all six project waves remain incomplete.
