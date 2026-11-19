# CT-21 / CT-23 / CT-24 â€” Customer Credit Aging Context

**Date:** 19 November 2026
**Status:** Partial local-ledger usability slice; focused API 37 emulator test passed.

## Delivered

- Customer credit accounts with a positive outstanding receivable now show the age of the oldest still-open credit order.
- Age is computed as elapsed complete 24-hour periods from the persisted order timestamp against the device clock.
- Fully repaid or reversed entries, voided orders, other customers' entries, missing timestamps, and future timestamps are excluded from the age calculation.
- The screen explicitly says that the age is device-clock context and that no due date is recorded. It does not call a balance due or overdue.
- No schema migration or new customer data was added.

## Verification

- Kotlin JVM: latest 59/59 passed, 0 failures, 0 errors (aggregate of five JUnit XML suites; includes customer timeline ordering/date edge cases).
- Focused API 37 test `customerCreditScreenShowsPersistedBalanceAndRepaymentEvents`: 1/1 passed. It verifies the persisted open balance and age wording, operates the actual dialog for a synthetic ₹5 cash repayment, then confirms the resulting ₹15 balance and separate staff-declared repayment event after reopening SQLCipher storage.
- Node prototype suite: 52/52 passed in this work session.
- Debug APK, instrumentation APK and lint tasks passed; lint reported 0 errors and 161 warnings.
- The first broad API 37 rerun had 20/90 failures while repeated Android System UI ANR dialogs obstructed UI automation. After a normal emulator restart, the current-source full suite passed 90/90 with 0 failures, errors or skips, including the restored repayment UI interaction and dated customer-details timeline.

Evidence logs are in ignored `test-evidence/2026-11-19/`, including `ct23-credit-aging-final-node.log`, `ct23-credit-aging-final-build-gradle.log`, `ct23-credit-aging-final-full-instrumentation.log`, and `ct23-credit-aging-repayment-ui-focused.log`. Result XMLs are under `android/app/build/outputs/androidTest-results/connected/debug/`.

## Limits and remaining work

This is age context, not a due-date system, reminder, collection workflow or credit-risk score. Device clock changes can affect displayed age. Missing or inconsistent timestamps are shown as unavailable. CT-21 through CT-24 remain partial modules; profile workflows, complete credit correction and repayment features, and production business validation remain outstanding. The 90/90 run qualifies only the current emulator test scope, not physical-device or production behavior.

