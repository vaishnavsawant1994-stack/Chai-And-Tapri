# CT-06 / CT-24 Quick Sale to Customer Repayment Report — 23 November 2026

## Implemented workflow

Extended the Android instrumentation scenario to cover the connected operator journey: select a saved customer in Quick Sale, post a synthetic ₹15 sale with ₹5 staff-declared cash and ₹10 Udhar, reopen persisted state, navigate to Customer Credit, record a ₹4 cash repayment, then reopen the encrypted local model and reconcile the result.

## Verified result

Focused API 37 emulator instrumentation passed **1/1** (`quickSaleSplitTenderFlowsThroughCustomerRepaymentAndReconcilesBalance`), with zero failures, errors or skips. The JUnit result is `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.

Persisted assertions: original sale ₹15; cash collections ₹9 (₹5 sale cash plus ₹4 repayment); outstanding customer receivable ₹6; order outstanding credit ₹6; order due ₹0; one ₹4 customer-repayment payment event marked `staff_declared`; matching audit event; stock remains 2 after selling 1 from opening stock 3. The order's `creditPaise` is an outstanding balance that decreases on repayment; the CustomerCredit ledger retains original amount and repaid amount separately. No external payment verification is claimed.

## Scope and remaining work

This is one synthetic UI journey, not full CT-06/CT-21/CT-24 qualification. No full Android suite, Kotlin JVM suite, APK build, lint, PostgreSQL suite, real customer data, physical device, browser visual review or production workflow was run for this test-only change. Customer selection, multi-credit allocation, repayment reversal, refunds after repayment, concurrency and offline/background operation require separate coverage. Full customer management and all six project waves remain incomplete.

## Post-change Android regression — 23 November 2026

After the focused UI test, `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` completed with **BUILD SUCCESSFUL**. Kotlin JVM reports **69 tests, 0 failures**. Debug and instrumentation APK tasks passed; lint reports **0 errors, 176 warnings**. This command did not rerun connected instrumentation, Node/prototype/backend suites, or PostgreSQL integration. The connected UI evidence remains the focused 1/1 test above.

## Broad Android regression attempt — 23 November 2026

A current-source run of `scripts/run-isolated-connected-tests.cmd emulator-5554` ended **FAIL** after 39m13s. Gradle's HTML report showed 93 tests, 37 failures, 0 errors, 0 skips (36m25s test duration). The wrapper at that time excluded only `LiveBackendSyncE2ETest`; the two `LiveBackgroundSync*E2ETest` classes also ran without their required isolated endpoint/phase and reported assumption failures. Several UI cases reported Android file-picker/launcher unresponsiveness, and the emulator's Package Manager service disappeared during cleanup. The new repayment scenario itself failed in that overloaded run because `Split Tea` was not found in the accessibility tree.

After a cold restart of the dedicated AVD, rerunning the repayment scenario alone passed **1/1** (JUnit `TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`, 16.375 seconds). This supports a device-load instability explanation but does not prove the full-suite failures are all infrastructure-only. The isolated-run wrapper now excludes all three opt-in endpoint-dependent test classes; that wrapper change has not yet been qualified by another broad run. The latest focused XML overwrote the broad JUnit XML, so the Gradle summary above is the retained command output, not a preserved broad-run XML artifact. Full Android regression remains **not qualified**.
