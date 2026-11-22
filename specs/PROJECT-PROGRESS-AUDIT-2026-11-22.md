# Chai And Tapri OS — Project Progress Audit

## Superseding continuation - CT-29 date-scoped report implementation - 22 November 2026

Daily Reports now includes a date picker and a date-scoped projection based on local device-recorded timestamps. Sales and delivered units are grouped by order creation date; current refund allocations reduce net sales on that original order date. Cash collection/refund entries and expenses use their own recorded dates. Current UPI due and customer receivables are presented as current balances for orders created on the selected date, while supplier payables remain current across all dates. Legacy undated orders, payment entries and expenses are excluded and counted. The screen and CSV state that device dates are not server-verified; profit, settlement, tax and close claims remain unavailable.

New Kotlin source tests pass: **64/64 JVM tests**; Node passes **52/52**; debug/instrumentation APK builds pass; lint has **0 errors / 173 warnings**. The updated date-scoped report rendered in a focused API 37 UI test (**1/1 passed**). A focused nonzero SAF export retry failed before reaching the export control because the isolated emulator presented Pixel Launcher and subsequently System UI "isn't responding" system dialogs. The prior full API 37 instrumentation result of 110/110 predates this CT-29 update. The date picker and selected-day SAF export remain runtime-unqualified; no broad instrumentation pass is claimed. Other project qualification gates remain as recorded below; the complete product, independent security, physical device, field and production gates remain open. Details: [CT-29 report](CT-29-CSV-EXPORT-REPORT.md).

**Date:** 22 November 2026  
**Authority:** Current local source, current test outputs, and synthetic emulator recovery evidence.

## Current verification

| Suite / check | Result |
|---|---:|
| Prototype Node tests | 52/52 passed |
| Kotlin JVM tests | 63/63 passed |
| API 37 instrumentation | 110/110 passed; 0 failures, errors, or skips |
| CAT-051B focused real-UI export | 1/1 passed on an isolated fresh API 37 AVD |
| CAT-051B focused uninstall/reinstall restore | 1/1 passed on the same isolated AVD |
| CAT-052 backend + PostgreSQL 17 | 23/23 passed on a separate temporary cluster with a restricted runtime role; cluster stopped after testing |
| Debug and instrumentation APK builds | Passed |
| Android lint | 0 errors / 173 warnings |

The latest 110-test Android run used only `Tapri_Recovery_Isolated`; the primary AVD was excluded. It includes the CT-15 read-only movement-history search, CT-08 partial item handover and queue filters, CT-29 nonzero SAF export/cancellation, and CT-30 alert routes. Only the endpoint-dependent live-backend Android class was excluded; backend integration passed separately against synthetic isolated PostgreSQL. Full-run XML is archived at `test-evidence/2026-11-22/ct15-movement-history-full-instrumentation.xml`. The run had zero failures, errors, or skips.

## Wave status

| Wave | Status |
|---|---|
| W1 — CAT-051B encrypted recovery | Same-emulator current-source UI export, app uninstall/reinstall, and UI restore now demonstrated with a synthetic multi-order dataset. Still partial: hard power loss, real filesystem exhaustion, hardware Keystore invalidation, second physical device/person, OEM transfer behavior, and independent security review remain open. |
| W2 — Android CT-01–CT-32 | In progress; the screens and connected workflows remain partial. CT-06 cart quantity controls, CT-08 partial item handover plus local queue search/status filters, and rich recovery integrations have current emulator evidence; this is not full screen/product qualification. |
| W3 — CAT-052 sync/backend | Local synthetic PostgreSQL suite passes 23/23. A limited Android SQLCipher-outbox to isolated PostgreSQL retry across database close/reopen is qualified. Process-death recovery during sync, full authoritative accounting, production TLS/operations, and reviewed deployment remain unqualified. |
| W4 — Owner dashboard | Partial owner views exist; remaining OW screens and browser visual/usability qualification are open. |
| W5 — Selling modes | One-tap/cart and scan paths are partial; voice remains reviewed single-utterance. Continuous Bluetooth, physical scanning, and field use remain unqualified. |
| W6 — Release qualification | Not complete. Independent security review, supported-device and shop trials, operational recovery, performance, privacy, deployment, and release gates remain. |

## CT-08 partial item handover - 22 November 2026

Live Orders now offers a review dialog for handing over a selected ready item and quantity. It calls the shared revision-checked `partial_given` command with a unique command ID. The emulator UI test verifies a 1-of-3 handover persists `PARTIALLY_GIVEN`, decrements confirmed stock once, appends one `partial_handover` movement, and leaves `salePosted` false until the order is fully delivered. Focused API 37 test passed 1/1; the full current API 37 suite then passed 102/102 with zero failures, errors, or skips. Kotlin JVM 63/63, Node 52/52, APK tasks PASS, lint 0 errors / 172 warnings. The test XML is the full-suite file listed above. This is one synthetic UI path, not full CT-08 qualification. See [CT-08/10 report](CT-08-10-LIVE-ORDERS-REPORT.md).

## CT-08 Live Orders queue search and filters - 22 November 2026

The local Live Orders queue now filters by order number, item text, fulfillment/payment state, and five queue views: all, needs preparation/handover, ready to hand over, payment due, and UPI pending. Filtering is read-only. A focused API 37 UI test confirmed the Ready filter and item search show the matching order without changing either order or stock. Focused test passed 1/1; the full current API 37 suite passed 103/103, Kotlin JVM 63/63, and Node 52/52; APK tasks passed and lint reported 0 errors / 172 warnings. The full-suite XML is listed above. This is a partial queue interaction qualification; manual usability and complete CT-08 remain open. See [CT-08/10 report](CT-08-10-LIVE-ORDERS-REPORT.md).

## CT-29 nonzero Daily Reports CSV export - 22 November 2026

Added API 37 instrumentation coverage for nonzero CSV export through the Android Storage Access Framework and picker cancellation. Focused export and cancellation tests passed 1/1 each; the qualified full instrumentation suite passed 105/105 with no failures/errors/skips. The saved Downloads CSV was pulled and inspected: ₹35 recorded sales, ₹15 staff-declared cash, ₹20 pending UPI, ₹5 recorded expenses, and one delivered unit for each product. Cancellation returns the explicit result and leaves the full persisted snapshot unchanged. Current regression: Node 52/52, Kotlin JVM 63/63, API 37 instrumentation 105/105 (endpoint-dependent live-backend class excluded), debug and instrumentation APK build tasks PASS, lint 0 errors / 172 warnings. Backend suite remains the previously verified 23/23 isolated PostgreSQL run; it was unchanged and not rerun for this Android-only slice. See [CT-29 report](CT-29-CSV-EXPORT-REPORT.md), the isolated runner `../android/scripts/run-isolated-connected-tests.cmd`, and ignored synthetic evidence `../test-evidence/2026-11-22/ct29-nonzero-export.csv`.

## CT-30 actionable low-stock alert review - 22 November 2026

Business Alerts now provides review navigation from alert cards to the related existing local workflow. The low-stock Review inventory action opens CT-15 Inventory; the focused API 37 test confirms the target screen and verifies the persisted ledger snapshot is unchanged. Focused test passed 1/1; the full serial-pinned API 37 suite passed 106/106 with zero failures/errors/skips. Node 52/52, Kotlin JVM 63/63, APK builds passed, lint 0 errors / 172 warnings. This is selected routing qualification, not full CT-30 or alert usability qualification. The optional backend-only Android class was excluded; backend PostgreSQL 23/23 is the unchanged prior run. See [CT-30 report](CT-30-LOW-STOCK-ALERTS-REPORT.md), ignored [full-suite XML](../test-evidence/2026-11-22/ct30-alert-routing-full-suite.xml), and [test log](../test-evidence/2026-11-22/ct30-alert-routing-full.log).

## CAT-051B recovery evidence

On the isolated API 37 AVD, the application created an encrypted backup through the Android Storage Access Framework CreateDocument picker. The backup file was copied to local test evidence outside app-private storage. The app was uninstalled and reinstalled while the file remained in Downloads, then the clean-install recovery test used OpenDocument, secret entry, authenticated preview, and explicit replacement confirmation.

The restored synthetic records include two orders; declared cash; pending UPI; a partial item refund; customer credit and a repayment; credit reversal; stock movements; audit/payment history; and command/outbox records. Assertions verify financial/stock projections, event-count consistency, duplicate-command rejection after restoration, and that the fresh installation can open the encrypted database with usable local Keystore key material.

Focused test evidence: `../test-evidence/2026-11-22/cat051b-clean-install-rich-restore-ui.log`. Full API 37 result: `../android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`. The encrypted synthetic artifact and build evidence are local ignored files; no shop data was used.

## Product status and remaining scope

The 28 business modules and 52 planned Android/owner screens are not complete. Many Android screens are partial; most owner dashboard views and modules remain unimplemented. The backend is a local prototype. One synthetic Android SQLCipher-outbox retry slice against isolated PostgreSQL is qualified; broad domain synchronization is not. Local encryption and backups have synthetic emulator coverage, not independent security qualification or production support. Physical camera/scanner, microphone/Bluetooth, noisy-shop, field, public deployment, and production-readiness claims are not made.

See [master implementation tracker](MASTER-IMPLEMENTATION-TRACKER.md), [CAT-051B implementation report](CAT-051B-IMPLEMENTATION-REPORT.md), [CAT-052 PostgreSQL integration report](CAT-052-POSTGRESQL-INTEGRATION-REPORT-2026-11-22.md), and [project plan](../PROJECT_PLAN.md).

## Superseding CT-30 route coverage update - 22 November 2026

The new synthetic UI test directly qualifies open-order, pending-UPI, customer-credit, and low-stock review navigation to CT-08, CT-10, CT-23, and CT-15. Each route left the full encrypted snapshot unchanged. Focused test passed 1/1; the serial-pinned isolated API 37 suite passed 107/107 with no failures, errors, or skips. Node 52/52, Kotlin JVM 63/63, debug and instrumentation APK build tasks, and lint passed (0 lint errors / 172 warnings). Only the isolated AVD was used. Supplier, shift, and daily-closing routes remain untested by click-through. No change to Wave status or overall product readiness: CT-30 and all six waves remain partial/incomplete as previously described.
## Superseding CT-30 routing result - 22 November 2026

The alert routing UI test now directly clicks all seven local actions: open order, pending UPI, customer receivable, low stock, supplier payable, open drawer, and cash count discrepancy. Destinations are CT-08, CT-10, CT-23, CT-15, CT-17, CT-27, and CT-28. Every navigation is verified against the full saved snapshot and leaves it unchanged. Focused API 37 test passed 1/1; complete isolated instrumentation suite passed 107/107, zero failures/errors/skips. Node 52/52 and Kotlin JVM 63/63 remain current; APK build and lint tasks passed, lint 0 errors / 172 warnings. These are synthetic emulator results only; CT-30 is still partial because dated overdue/sync alerts, user alert settings, and replenishment automation are absent.
## Superseding CT-28 UI test result - 22 November 2026

The CT-28 closing review acknowledgement is now exercised through the Android UI. A synthetic follow-up note persists, and expected cash, payment history, and stock movements are unchanged by acknowledgement. Focused test 1/1; full serial-pinned isolated API 37 instrumentation 107/107 (0 failures/errors/skips); Node 52/52; Kotlin JVM 63/63; APK build and lint tasks pass (0 lint errors / 172 warnings). This is only one emulator UI path; session-based daily closing and all six delivery waves remain incomplete.
## Superseding CT-06 repeat-sale update - 22 November 2026

The native Quick Sale repeat action now stages an eligible prior completed sale into an editable draft, applying current catalogue prices and checking stock; it does not create another transaction until explicit checkout. Focused test 1/1; the current full isolated API 37 suite passed 108/108 with no failures/errors/skips, after an earlier run's Sales History activity launch timed out and passed in isolation. Node 52/52, Kotlin JVM 63/63, APK builds passed, lint 0 errors / 172 warnings. CT-06 remains partial and all six waves remain incomplete.

## CT-17/18 purchase-order UI continuation - 22 November 2026

A new synthetic purchase-order screen test passed 1/1 on isolated API 37. It covers draft creation, sent confirmation, and two partial receipts through the actual Android UI. Stock and payable remain unchanged until receipt posting and then reconcile to the expected balances; order receipt state, supplier invoice reference, stock movements, and audit history are checked. Node 52/52, Kotlin JVM 63/63, APK builds pass, lint 0 errors / 172 warnings. The complete connected suite attempt did not emit a JUnit report and was stopped while the instrumentation runner remained active; no new broad pass is claimed. The prior 108/108 result remains the latest completed broad run. CAT-17/18 and all six waves remain partial.

## Superseding CT-17/18 and full isolated API 37 verification - 22 November 2026

The current-source full instrumentation run completed on `Tapri_Recovery_Isolated` in 23m 35s: 109/109 passed, 0 failures, 0 errors, 0 skipped. The runner excluded only `LiveBackendSyncE2ETest`, which requires the separate isolated backend endpoint. The suite includes the actual UI journey for purchase-order draft, sent confirmation, and two partial receipts, and the CT-30 multi-destination alert-route test. JUnit evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.

Current host checks: Node 52/52; Kotlin JVM 63/63; debug and instrumentation APK assembly passed; lint 0 errors / 172 warnings. The earlier broad run was manually stopped without a report; it is superseded by this completed run. The new procurement UI path is emulator-qualified only for this synthetic workflow; the complete procurement module and all six delivery waves remain partial. Physical device/shop, independent security, and production release gates remain open.
## CT-15 full stock movement history - 22 November 2026

Inventory Overview now links to a manager-gated, read-only stock movement history screen. It lists the available append-only movement ledger newest first and supports filtering by product and event type, plus local search by product name/ID, reason, order number, or movement ID. The screen states that event timestamps are unavailable in this prototype and does not edit stock. The focused API 37 UI test navigates from Inventory Overview, searches for `quick_sale`, verifies the matching product/reason and one-of-three result count, and compares the full encrypted persisted snapshot before/after to prove the view is read-only. The full isolated API 37 suite passed 110/110, zero failures/errors/skips, including this test. Final-source focused rerun passed 1/1 after moving the dynamic result count into a string resource and applying the AndroidX KTX emptiness helper. Node 52/52; Kotlin JVM 63/63; debug/instrumentation APK builds passed; lint 0 errors / 173 warnings. Full-suite XML: `test-evidence/2026-11-22/ct15-movement-history-full-instrumentation.xml`. The current focused-test XML is `test-evidence/2026-11-22/ct15-movement-history-focused.xml`.

This qualifies only the synthetic history search/navigation path. The history has no event timestamp, export, pagination, or manual usability qualification. CT-15 and the overall product remain partial; hardware, independent security, deployment and shop-trial gates remain open.
## CT-17/20 purchase-to-supplier-settlement UI journey - 22 November 2026

Extended `procurementPurchaseOrderDraftAndTwoPartialReceiptsUseTheNativeScreen` to drive the complete connected path through the Android UI: create a four-unit purchase-order draft, mark it sent, record two partial receipts, then pay ₹5 cash against the ₹20 payable. The test reopens encrypted persistence and verifies stock remains 9 units, payable is ₹15, the ₹5 settlement is `staff_declared`, no expense or extra stock movement is created, and the audit event is present. It then opens Supplier Details and verifies the remaining payable and staff-declared cash entry. This demonstrates a supplier settlement is a payable reduction, not a second purchase or expense; it does not verify a real bank/cash transfer.

Focused test passed 1/1 before the regression run; the complete isolated API 37 instrumentation suite passed 110/110 with 0 failures/errors/skips and includes the modified test. Node 52/52; Kotlin JVM 63/63; debug APK, instrumentation APK, JVM tests, and lint tasks passed; lint 0 errors / 173 warnings. The runner used only `emulator-5556` and excluded the endpoint-dependent `LiveBackendSyncE2ETest`. Full-run JUnit XML is archived at `../test-evidence/2026-11-22/ct17-partial-receipt-supplier-settlement-full-instrumentation.xml`. Backend PostgreSQL 23/23 remains the previously verified isolated run and was not rerun for this Android-only change.

CT-17/20 remains partial: supplier invoices, settlement authorization/identity, bank verification, terms/due dates, and complete purchase/accounting operations are not implemented or qualified. All six delivery waves remain incomplete.
## CT-17 supplier overpayment rejection review - 22 November 2026

Extended the CT-17/20 purchase-order UI journey to first attempt a ₹21 supplier payment against the ₹20 outstanding payable, then assert the engine's explicit “No change made” rejection and verify that stock, payable, payment history, expense history, and supplier-payment audit history remain unchanged. The test then records a valid ₹5 cash settlement and verifies the ₹15 payable in Supplier Details. The focused instrumentation test passed 1/1 once after correcting the test expectation to match the engine response; the API 37 Gradle command returned BUILD SUCCESSFUL. Host regression after the final test-source change passed Node 52/52; `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` all passed; lint remains 0 errors / 173 warnings.

A final full-suite rerun did not complete. The isolated emulator repeatedly showed System UI/Pixel Launcher ANRs; one run lost Package Manager and stopped after 56 cases, and later retries failed before the target screen could open. The archived latest JUnit file, `../test-evidence/2026-11-22/ct17-overpayment-retry-pixel-launcher-anr.xml`, records a 45-second instrumentation timeout launching ProcurementActivity because the main event queue did not become idle; an earlier retry displayed a Pixel Launcher ANR. These are emulator failures before the tested transaction path, not application transaction assertion failures. The last completed 110/110 full suite predates this additional overpayment UI assertion, although the updated supplier journey itself passed focused. No new full-suite pass is claimed. This testing limitation does not establish broader product or production qualification.
## CT-17 supplier return after partial settlement - 22 November 2026

Expanded the Kotlin source-level supplier-return scenario to cover receipt, partial cash settlement, and return of two still-available units. For a ten-unit receipt valued at ₹5,000, a ₹1,000 staff-declared cash settlement first leaves ₹4,000 payable. Returning two eligible units at the recorded ₹500 unit cost then reduces stock from 10 to 8 and payable to ₹3,000. The original payment remains recorded for ₹1,000; the return history records ₹1,000; exactly one `purchase_return` stock movement is added. Duplicate return commands remain idempotent; an over-return and a return without a reason are rejected without changing final stock/payable; snapshot integrity passes.

The focused test passed 1/1; the complete Kotlin JVM suite passed 63/63 (0 failures/errors); Node passed 52/52; `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` passed; lint reports 0 errors / 173 warnings. No Android instrumentation run was completed for this source-level test change. The emulator had repeated System UI/Pixel Launcher launch failures in the preceding UI qualification attempts, so CAT-17 UI full-suite qualification remains open. This test validates synthetic engine accounting only, not receipt-lot provenance or real supplier settlement.
