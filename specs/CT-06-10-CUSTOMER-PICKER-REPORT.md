# CT-06/CT-10 Saved Customer Selection — Implementation Report

**Date:** 31 October 2026  
**Status:** Implemented; unit-tested; emulator-qualified for the Quick Sale Udhar path.

## Delivered

- Added one shared customer-account picker used by Quick Sale, Payment, and Live Orders Udhar workflows.
- The picker combines saved customer profiles with legacy customer IDs already present in the credit ledger, sorts deterministically, supports search, and shows outstanding balances in rupees.
- Selecting an account records credit against its existing ID. The picker does not create accounts implicitly; when no accounts exist, it offers a route to the customer directory.
- Quick Sale remains a counter flow: item selection, checkout, Udhar, select saved customer, commit. Payment and Live Orders preserve their existing due amount and expected-order-revision checks.
- Removed the free-form customer-ID field from the Live Orders credit prompt and now use the same picker as the other entry points.

## Verification

- `CustomerAccountPickerTest`: 3/3 JVM tests passed (merging profile and legacy-ledger accounts, deterministic de-duplication/sorting, and rupee label formatting).
- `quickSaleUdharSelectsSavedCustomerInsteadOfTypingAnAccountId`: passed on the API 37 emulator. The test creates synthetic profile, credit and stock data, completes a Quick Sale through the actual UI, selects the saved customer and verifies the order is Udhar, the chosen customer ID is attached, and stock decreases by exactly one item.
- `quickSaleUiRecordsCashAndUpiPendingAsDifferentPaymentStates`: passed on the API 37 emulator. It completes two Quick Sale checkouts through the real UI, then verifies cash is recorded as staff-declared, UPI is still pending with no collected amount, both orders are persisted, and each sale deducts stock once.
- `quickSaleUiRecordsSplitCashAndUdharInOneAtomicSale`: passed on the API 37 emulator. It enters a partial cash amount, selects an existing customer and verifies one completed order with a staff-declared cash payment, a receivable for the exact remainder, no unexplained due, and one stock deduction.
- `liveOrdersUdharSelectsSavedCustomerAndSettlesOrderDue`: passed on the API 37 emulator. It creates, prepares and hands over a synthetic order, then uses Live Orders to select a saved customer and record full Udhar. It verifies the selected customer owns the receivable, order due becomes zero, and the handover reduced stock once.
- `quickSaleSplitPostsCashAndCustomerCreditAtomicallyAndIsIdempotent` and `quickSaleSplitRejectsInvalidTenderAndRollsBackStorageFailure`: passed in the Kotlin JVM suite. They verify split allocation, stock/payment/customer-credit effects, duplicate suppression, invalid amounts/accounts, and rollback on injected persistence failure.
- Final full current-source verification after split tender and repayment-test correction: prototype Node tests 49/49; Kotlin JVM tests 40/40; Android API 37 instrumentation 69/69; debug and instrumentation APK builds passed; lint 0 errors / 139 warnings. Android test XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`. Kotlin XML is under `android/app/build/test-results/testDebugUnitTest/`.
- Backend suite: 5 passed, 4 PostgreSQL-dependent tests skipped because the integration database was not running.

The first Quick Sale UI run reached the successful transaction but failed its test assertion because it expected 800 stock units rather than 8. The stock model stores the entered item count, not a paise-scaled value. The assertion was corrected, and the focused test and full emulator suite then passed. The Live Orders test also exposed an incorrect expected wording in the test (`handed over` vs the engine's `sale recorded`); the expectation was aligned to the engine response, and the focused test and final full suite passed.

## Limits

This verifies the specified synthetic Quick Sale, payment and Live Orders UI paths and exercises the current emulator instrumentation suite. It does not qualify the full customer module, account privacy/security, a production sales day, cloud synchronization, cross-device behavior or physical-device operation. The Quick Sale, Payment and Live Orders flows remain partial screens; the 52-screen Android and owner-web product is not complete.
