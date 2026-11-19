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

## Superseding CT-22 open-credit age context — 19 November 2026

The detail page now shows the age of the oldest still-open credit order when the customer has a positive receivable. It uses the persisted order timestamp and device clock and explicitly states that no due date is recorded; it does not claim a balance is overdue. Fully settled credit is not shown as open age. The existing directory-to-detail test now asserts this wording using a synthetic profile and credit sale. Focused API 37 test passed 1/1. Current-source regression after this change: Node 52/52; Kotlin JVM 58/58; API 37 instrumentation 90/90; debug and instrumentation APK tasks PASS; lint 0 errors / 161 warnings. Evidence logs: `../test-evidence/2026-11-19/ct22-credit-aging-focused.log`, `ct22-credit-aging-node.log`, and `ct22-credit-aging-full-gradle.log`. This remains device-clock age context, not due-date tracking or collection automation; the broader CT-22 and product remain partial.
