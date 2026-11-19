# CT-22 Customer Details — Local Credit Ledger

**Date:** 30 October 2026  
**Status:** Implemented as a connected, read-only history plus validated repayment action; emulator-tested for the documented synthetic flow.

## Delivered

- Customer Directory now links each persisted customer account ID to a dedicated CT-22 details screen.
- When a local CT-21 profile exists, the details screen displays the saved customer name and a masked, consented contact phone.
- Details are derived from the encrypted local credit and payment ledgers: total credit extended, repayments, reversals, outstanding balance, per-order credit, and payment/repayment event history.
- Each credit entry links to its persisted order details.
- A cash repayment action is available when the balance is positive. It validates a positive paise-exact amount no greater than the current due and uses the existing repayment command with a unique idempotency ID. The disclosure says it is staff-declared and does not verify external payment.
- Unknown or empty customer IDs show an explicit no-account state; database errors disclose that no financial record was changed.

## Verification

- Synthetic account fixture: ₹30.00 credit, ₹10.00 staff-declared repayment, ₹20.00 outstanding.
- API 37 instrumentation follows the Customer Directory action and asserts the rendered account balance and repayment event from persisted records.
- Latest full run: Node 49/49, Kotlin JVM 31/31, Android instrumentation 42/42 (122 total); APK tasks PASS; lint 0 errors / 108 warnings.
- Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` and `android/app/build/reports/lint-results-debug.txt`.

## Remaining work

Customer details still lack profile editing/deletion, dated timeline, consent revocation/retention, customer authentication, aging/due dates, and cloud sync. Repayment dialogs have not been manually used by a shopkeeper; their transaction engine and displayed ledger are covered by tests. No real customer data was used.
