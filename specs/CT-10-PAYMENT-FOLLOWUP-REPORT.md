# CT-10 Payment Follow-Up — Staff-Declared UPI Receipt

**Date:** 30 October 2026  
**Status:** Implemented and automated-test qualified for the prototype workflow; not provider verified and not production-qualified.

## Delivered

- Added a native Payment screen reachable from the counter and eligible live orders. It lists completed orders with an amount due or a pending UPI attempt.
- Added explicit follow-up for a pending UPI attempt. The operator enters the amount actually observed and confirms the action. The transaction engine cancels the matching pending attempt with a linked ledger event, then records a separate UPI payment with `staff_declared` evidence.
- The operation requires the identified order, expected revision, positive amount not exceeding the pending attempt or current due, and an idempotency key. It does not turn a payment notification or verbal assertion into provider verification.
- The screen explains the evidence status. UPI pending remains uncollected until follow-up; the resulting collection remains staff-declared, never provider-verified.
- Added the equivalent validated command to the browser transaction engine for business-contract parity.

## Verification

- Node: 49/49 passed.
- Kotlin JVM: 31/31 passed.
- Android API 37 instrumentation: 37/37 passed.
- Debug and instrumentation APK build tasks: passed.
- Android lint: 0 errors, 107 warnings.
- Instrumentation verifies pending UPI is unverified, exercises the amount entry and explicit confirmation in the actual Android Payment screen through accessibility automation, then asserts the linked cancellation, staff-declared collection, partial due, and absence of provider-verified entries. A separate test covers persistence across encrypted database reopen and duplicate suppression.
- Evidence: `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, and `android/app/build/reports/lint-results-debug.txt`.

## Remaining CT-10 work

The amount-entry dialogs have automated behavior coverage but have not been manually exercised through emulator taps. The screen is not a complete split-tender or payment-history workflow. Customer selection is limited to the existing account identifier, and no acquiring-bank/provider integration or external UPI verification exists. Refunds remain on the audited correction path.

## Superseding continuation — atomic cash plus Udhar collection, 31 October 2026

Added `recordCashUdharSplit` as a single validated engine command for an already completed order. It requires a current order revision, an existing customer account, no unresolved UPI attempt, a positive cash amount strictly below the current due, and consistent customer ownership for any open credit. One local commit appends staff-declared cash and receivable ledger entries, updates the order's cash/credit totals and `SPLIT` payment state, creates one audit event and produces the normal sync-outbox decision. Failure rolls back both tender entries and the receivable. Customer-linked changes remain blocked from outbound sync until the customer identity contract is approved.

CT-10 now exposes **Split cash + Udhar** on eligible completed orders. The operator selects a saved customer, enters cash physically received, reviews the resulting cash/credit amounts, then explicitly confirms. The UI labels cash as staff-declared and does not claim provider verification.

The browser transaction engine now has the matching `order.record_cash_udhar_split` command, with idempotency, revision checking, atomic state commit and a focused behavior test.

Qualification for this continuation (31 October 2026): Kotlin JVM 43/43 passed (40 CounterModel + 3 customer-picker); Node 50/50 passed; Android API 37 instrumentation 71/71 passed; debug APK and instrumentation APK tasks passed; Android lint reported 0 errors and 140 warnings. Backend tests: 5 passed, 0 failed, 4 PostgreSQL-dependent integration tests skipped because a test database was not configured. The UI scenario selected a saved synthetic customer, reviewed and confirmed ₹5 cash plus ₹10 Udhar against a ₹15 order, and reopened encrypted SQLite to verify zero due, separate tender records, customer receivable, audit event, unchanged stock after payment, and no provider-verified entry. Evidence: `../test-evidence/2026-10-31/ct10-split-full-gradle.log`, `../test-evidence/2026-10-31/ct10-split-node-tests.log`, `../test-evidence/2026-10-31/ct10-split-backend-tests.log`, and `../test-evidence/2026-10-31/ct10-split-instrumentation.xml`. Manual usability testing and production authorization remain open.

## Files changed for this continuation

- `android/app/src/main/java/com/chaiandtapri/counter/CounterModel.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/PaymentActivity.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/MainActivity.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/LiveOrdersActivity.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/test/java/com/chaiandtapri/counter/CounterModelTest.kt`
- `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt`
- `prototype/transaction-engine.js`
- `prototype/transaction-engine.test.cjs`

## Superseding continuation — payment history and explicit contact-consent revocation — 31 October 2026

The native Payment screen now has a **Payment history** view that displays append-only payment events with their evidence state. It distinguishes staff-declared cash/UPI receipts from UPI-pending attempts, Udhar receivables, cancelled attempts and reversals/refunds. It does not imply provider verification or invent event timestamps. An API 37 screen test creates a synthetic order, records pending UPI, a staff-declared UPI receipt and cash+Udhar settlement, then verifies the history labels and amounts.

The full emulator regression initially exposed that the existing privacy test's accessibility action could not reliably clear a saved phone field. The customer profile dialog now offers an explicit **Clear saved phone and revoke consent** action, routed through the validated profile update/audit path. The focused test passed with this action, and the subsequent full regression passed.

Final-source verification: Node 50/50; Kotlin JVM 43/43; API 37 instrumentation 72/72 (57 persistence/UI, 5 account-client, 3 background-work, 4 sync-runner, 3 session-vault); debug and instrumentation APK build tasks passed; lint passed with 0 errors and 140 warnings. Backend tests: 5 passed, 4 PostgreSQL-dependent tests skipped because no isolated test database was configured. Evidence: `../test-evidence/2026-10-31/ct10-history-final-gradle.log`, `../test-evidence/2026-10-31/ct10-history-final-instrumentation.xml`, `../test-evidence/2026-10-31/ct10-history-node-tests.log`, and `../test-evidence/2026-10-31/ct10-history-backend-tests.log`.

This qualifies the synthetic Payment History and consent-revocation prototype flows on API 37. Manual usability, provider integration/verification, broader payment module completion, CAT-051 hard-failure and independent security gates, end-to-end live sync, owner dashboard, physical Bluetooth and shop trials remain open.
## Superseding continuation — cash plus UPI pending split — 31 October 2026

Added a native Payment screen split-tender action for a finished order: staff enters cash physically received; the remaining amount becomes a UPI payment attempt marked pending. The command is atomic, revision-checked and idempotent. The cash entry remains `staff_declared`; the UPI attempt has pending status and is not counted as collected. A later UPI follow-up cancels the original pending attempt through an append-only event and records only the observed receipt as staff-declared. No bank/provider verification is inferred. The UI test closes and reopens the Payment activity between split and UPI follow-up, then verifies the encrypted ledger, final paid/due totals, attempt linkage, unchanged stock and absence of verified entries.

Final full-source verification: Node 51/51, Kotlin JVM 44/44, API 37 instrumentation 73/73, debug and instrumentation APK builds PASS, lint 0 errors/141 warnings. Backend tests: 5 passed, 4 PostgreSQL-dependent tests skipped because an isolated test database was not configured. Evidence: `../test-evidence/2026-10-31/ct10-cash-upi-full-gradle.log`, `ct10-cash-upi-instrumentation.xml`, `ct10-cash-upi-kotlin.xml`, `ct10-cash-upi-node.log`, `ct10-cash-upi-backend.log`, and `ct10-cash-upi-lint.txt`.

CT-10 remains a partial prototype. Other tender combinations, manual usability, payment-provider verification, live synchronization, CAT-051B destructive-failure/security gates, owner web dashboard, continuous Bluetooth, physical-device and shop trials, and production readiness remain open.
## Superseding continuation — direct staff-declared UPI receipt — 31 October 2026

Added a separate direct UPI receipt command and Payment screen action for the case where the operator observed payment but no pending UPI attempt was previously recorded. The action requires amount entry and explicit staff-declaration confirmation, is revision-checked and idempotent, and appends an `upi` / `staff_declared` order-collection entry. It cannot run while an unresolved UPI attempt exists; that case must use the pending-attempt follow-up so the attempt remains linked and auditable. No provider-verified status is created. Kotlin and JavaScript rules have matching behavioral tests; an API 37 screen test verifies a partial direct receipt, persisted due, unchanged stock, no pending attempt and no verified entry.

Final full-source regression after this change: Node 52/52; Kotlin JVM 45/45; API 37 instrumentation 74/74; debug and instrumentation APK builds PASS; lint 0 errors/141 warnings. Backend tests 5 passed and 4 PostgreSQL-dependent tests skipped because no isolated test database was configured. Evidence: `../test-evidence/2026-10-31/ct10-direct-upi-full-gradle.log`, `ct10-direct-upi-instrumentation.xml`, `ct10-direct-upi-kotlin.xml`, `ct10-direct-upi-node.log`, and `ct10-direct-upi-lint.txt`.

CT-10 remains partial and prototype-qualified. External provider integration, manual usability, broader tender allocation, remaining screens/modules, recovery/security gates, end-to-end synchronization, owner web, physical Bluetooth, shop trials and release qualification remain open.