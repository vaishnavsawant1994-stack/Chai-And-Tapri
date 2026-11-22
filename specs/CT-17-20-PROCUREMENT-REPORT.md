# CT-17–20 Procurement Foundation Report

**Date:** 29 October 2026  
**Status:** Partial implementation; selected model, persistence, migration, and UI paths passed automated tests. Not a complete purchasing module.

## Current continuation — supplier contact and migration (1 November 2026)

Supplier profiles now accept an optional business phone number from Add/Update and Edit Supplier flows. The owner-gated detail page displays it and permits updates or clearing. SQLCipher schema v23 adds the field with an additive migration from v22; older supplier records migrate with a null contact. Supplier names, phone numbers and ledger records remain in the encrypted local store and are carried by the existing `CounterSnapshot` restore path. The supplier audit event records only `contact=set/none`, never the number. Input is optional and validated to 7–15 digits with common formatting characters. Focused migration/reopen test PASS 1/1 and current full API 37 instrumentation PASS 81/81; Node 52/52, Kotlin JVM 56/56, APK/test APK build PASS, lint 0 errors / 155 warnings. Evidence: `../test-evidence/2026-11-01/ct19-20-23-current-verification.md`. Contact data handling still needs a dedicated privacy review.

## Implemented

- Local supplier records with ID, display name, and contact field.
- Direct purchase receipt for up to ten products selected from the active catalogue.
- Receipt quantity and unit purchase cost stored in integer paise.
- Atomic receipt posting: append-only receipt and line, stock movement, supplier payable, audit/idempotency event, and local outbox event are committed together.
- Supplier cash settlement against the outstanding payable. It is classified as staff-declared cash, not externally verified.
- Supplier return against an original receipt: requires a reason, limits returns to unreturned received quantities and currently available stock, and cannot reduce payable by more than the outstanding balance. Returns append a record and negative stock movement.
- Encrypted SQLite schema v9-v10 added suppliers, receipts, supplier payments, purchase returns and return lines; schema v12 adds optional drawer-session attribution to supplier cash payments. Migrations are additive, including v8-to-v12, preserving existing order data.
- Snapshot integrity checks validate references, receipt totals, stock movement correspondence, confirmed stock baselines, and payable bounds.
- A native procurement screen supports supplier add/update, direct receipt, cash payment, payable summary, and receipt/payment history.

## Qualification evidence

Run on 29 October 2026:

| Check | Result |
|---|---:|
| Node tests | 48/48 passed |
| Kotlin JVM tests | 26/26 passed |
| API 37 emulator instrumentation | 18/18 passed; 0 failures/errors/skips |
| Debug APK | Build passed |
| Instrumentation APK | Build passed |
| Android lint | 0 errors, 68 warnings |

New coverage includes supplier/receipt/payment atomicity and retry behavior, multi-product receipt totals and movements, return validation/payable limits/idempotency, encrypted database reopen, v8-to-v11 migration while preserving an existing order, and procurement screen rendering of persisted receipt, return, payment and payable data. The receipt dialog supports up to ten product lines and the return dialog is linked from each receipt; direct dialog submission has not been manually qualified. Reports are under `android/app/build/test-results/testDebugUnitTest/` and `android/app/build/outputs/androidTest-results/connected/debug/`.

## Accounting behavior and limits

- Purchase receipts add stock and create a supplier payable; paying the supplier reduces that payable and does not create another purchase or stock movement.
- A receipt requires a confirmed opening stock baseline for that product. This prevents an uncounted legacy balance from being silently combined with newly received quantities.
- Supplier cash payments are staff-declared. The current screen does not provide bank/UPI settlement verification.
- Purchase returns require sufficient aggregate stock and outstanding payable. Product lots are not tracked, so the system cannot prove that the physically returned units came from a particular delivery; this is a material prototype limitation.
- Receipts and returns are append-only; credit notes, receipt edits, and supplier payment reversals are not implemented.
- There is no purchase-order draft, partial delivery, invoice OCR, supplier portal, or delivery discrepancy flow.
- The UI was exercised by an emulator instrumentation test that launched the activity and inspected rendered values. No separate manual usability/visual qualification is claimed.
- Supplier contact handling is minimal and has not had a privacy or access-control review.

## Next work

Complete CT-17–20 with multi-line purchase drafts, partial/full receiving, return/credit-note semantics, supplier payment types and correction controls. Before moving to reports or profit calculations, define purchase-cost allocation and cost-of-goods-sold policy. Keep CAT-051B, sync, security, hardware, and field gates separately open.

## Superseding continuation — CT-17 drafts and partial receiving — 1 November 2026

Added persistent multi-line purchase-order drafts. Saving a draft does not create stock or supplier payable. The owner may mark it sent after sending outside the app, and then post one or more partial receipts against remaining quantities. Linked receipts use the existing atomic stock/payable/audit/outbox transaction. Over-receipts, unit-cost mismatch, unsent orders, stale revisions, duplicate commands, and cancellation after delivery are rejected. An unreceived order can be cancelled with a reason.

SQLCipher schema v21 adds purchase-order draft/line tables and a nullable purchase-order ID on receipts; v20 migration is additive. Kotlin JVM 54/54, Node 52/52, debug and instrumentation APK build tasks PASS, lint 0 errors/154 warnings. The Android v20→v21 migration/partial delivery test compiled but was not run because ADB had no connected device. See [CT-17 draft report](CT-17-PURCHASE-ORDER-DRAFT-REPORT.md) and `../test-evidence/2026-11-01/ct17-purchase-order-drafts-gradle-rerun.log`.

This slice does not send orders to suppliers, capture invoices, handle rejected/short deliveries, edit persisted drafts, allocate stock lots, or provide supplier payment verification. The procurement module, Android MVP and overall product remain incomplete and not production-qualified.

## CT-17/18 native UI partial-receipt qualification - 22 November 2026

Added an emulator UI journey that explicitly selects a synthetic catalogue product, creates a purchase-order draft for four units, marks it sent, and records two deliveries of two units through the Procurement screen. Assertions verify that the draft and sent states do not change stock or payable; the first receipt increases stock from 5 to 7 and payable to 1,000 paise; the second increases stock to 9 and payable to 2,000 paise. The test also checks the final received quantity/state, both receipt records, invoice reference, stock movement count, and receipt audit event. Focused isolated API 37 instrumentation passed 1/1 (`emulator-5556`, XML: `../android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`). Node 52/52 passed; Kotlin JVM 63/63 passed; debug and instrumentation APK assembly passed; lint reported 0 errors / 172 warnings.

The first test attempt exposed a fixture mistake: the product picker alphabetically selected the default Coffee product, which correctly failed because that product had no confirmed opening-stock baseline. The test now selects the synthetic tea product in the actual picker. A subsequent complete connected instrumentation run did not produce a JUnit report and was stopped after the runner remained active in later UI tests; therefore no new full-suite pass is claimed. The prior 108/108 full-suite result remains the last completed broad regression, while the new procurement path is focused-test-qualified only. Provider payment verification, physical delivery evidence, purchase return allocation, supplier-side integration, independent security review, field use and production readiness remain open.

## CT-18 supplier invoice references — 1 November 2026

Receipts now accept an optional staff-entered supplier invoice reference. It is trimmed, bounded to 80 characters, checked for control characters and duplicate case-insensitive references for the same supplier, persisted in SQLCipher schema v22, shown in receipt history, and included in the receipt audit description. The additive v21→v22 migration preserves previous receipt/stock/payable data. This is a reference string only; no invoice image, OCR, authenticity or delivery verification is provided. Focused API 37 migration and receiving UI tests passed 1/1 each; Kotlin JVM 55/55, Node 52/52, build tasks pass, lint 0 errors/154 warnings. The full 77-case API run recorded 63 pass/14 failures after emulator System UI/Pixel Launcher ANRs. See [CT-18 report](CT-18-SUPPLIER-INVOICE-REFERENCE-REPORT.md) and `../test-evidence/2026-11-01/ct18-*`. Full Android regression is not green.

## CT-20 staff-declared UPI supplier settlement - 1 November 2026

The supplier ledger now lets staff record cash or UPI settlements against outstanding supplier payables. Both records have `status=staff_declared`; the UPI record does not claim provider verification. A UPI settlement reduces the staff-entered payable but is not associated with the cash drawer. Cash settlement remains included in cash-drawer reconciliation. Unsupported methods, nonpositive amounts and overpayment are rejected; command IDs remain idempotent. Existing `method` and `status` fields were sufficient, so no SQLite schema migration was needed. The UI disclosure and recent payment history label both methods/evidence status.

Verification: Kotlin JVM 56/56; focused API 37 procurement screen test 1/1 passes with a cash and UPI payment loaded from persisted records; APK builds pass; lint 0 errors/154 warnings. Node remains 52/52 (no JavaScript changes). Evidence: `../test-evidence/2026-11-01/ct20-upi-payment-build-unit-lint.log`, `ct20-supplier-upi-ui-focused.xml`. This does not add UPI-provider integration or verified supplier payments. The current full Android suite was not rerun after this source change; the prior 77-case run had 14 failures after emulator System UI/Pixel Launcher ANRs.

### CT-20 supplier UPI dialog interaction — 1 November 2026

An Android instrumentation test now drives the actual supplier-payment dialog: it enters a supplier and amount, selects UPI, submits, then reopens encrypted persistence and checks the staff-declared payment event, payable reduction, unchanged cash-drawer expected balance, and snapshot integrity. Focused API 37 instrumentation passed 1/1 after the emulator completed a cold boot. Evidence: `../test-evidence/2026-11-01/ct20-supplier-upi-dialog-focused.xml`; build/unit/lint evidence: `../test-evidence/2026-11-01/ct20-supplier-upi-dialog-build-unit-lint.log`. The full Android suite has not been rerun on this source. This is a synthetic emulator workflow, not provider verification or field qualification.

### CT-19/20 supplier search and details vertical slice - 1 November 2026

The supplier list now filters by case-insensitive name or stable supplier ID and routes each result to a dedicated owner-gated details screen. The details screen reads only the selected supplier's locally persisted profile, purchase-order state/received quantities, receipt lines and references, staff-declared cash/UPI settlements, returns, and computed payable. UPI is explicitly labeled not provider-verified. No schema change was needed and supplier records remain unchanged.

The focused API 37 UI test seeded two synthetic suppliers, searched for one, verified only one detail action remained, opened that supplier, and checked its receipt reference, declared payment and ₹7.00 payable while confirming no other supplier's profile/ledger entries appeared. PASS 1/1; evidence: `../test-evidence/2026-11-01/ct19-supplier-search-details-focused.xml`. Current host verification: Node 52/52; Kotlin JVM 56/56; backend test command 11 passed / 4 skipped (PostgreSQL integration not configured); debug and instrumentation APK build tasks PASS; lint 0 errors / 154 warnings. The full Android instrumentation suite remains unqualified on current source; last historical complete run was 63/77 after launcher/System UI ANRs, and a subsequent current-source attempt stalled without a JUnit report. This slice is synthetic emulator evidence only.

**Superseding verification after the CT-19/20 screens (1 November 2026):** Node 52/52; Kotlin JVM 56/56; backend 11 passed / 4 PostgreSQL-dependent tests skipped; debug and instrumentation APK builds PASS; lint 0 errors / 155 warnings. Four API 37 instrumentation tests passed individually: supplier UPI dialog, supplier search/details, rich financial backup restore and restore-journal cutover recovery. See `../test-evidence/2026-11-01/current-ct19-recovery-verification.md`. The full current-source Android suite remains unresolved.

**Full regression supersession (1 November 2026):** After cold boot, the current API 37 full instrumentation suite passed 80/80, 0 failures/errors/skips, including the new CT-19 supplier-directory-to-details interaction and CT-20 UPI-dialog payment. JUnit evidence: `../test-evidence/2026-11-01/ct19-20-current-full-instrumentation.xml`. This supersedes the immediately preceding statement that the full suite was unresolved; it does not remove the stated procurement, provider-verification or product-scope limitations.

## Superseding CT-17/18 and full isolated API 37 verification - 22 November 2026

The current-source full instrumentation run completed on `Tapri_Recovery_Isolated` in 23m 35s: 109/109 passed, 0 failures, 0 errors, 0 skipped. The runner excluded only `LiveBackendSyncE2ETest`, which requires the separate isolated backend endpoint. The suite includes the actual UI journey for purchase-order draft, sent confirmation, and two partial receipts, and the CT-30 multi-destination alert-route test. JUnit evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.

Current host checks: Node 52/52; Kotlin JVM 63/63; debug and instrumentation APK assembly passed; lint 0 errors / 172 warnings. The earlier broad run was manually stopped without a report; it is superseded by this completed run. The new procurement UI path is emulator-qualified only for this synthetic workflow; the complete procurement module and all six delivery waves remain partial. Physical device/shop, independent security, and production release gates remain open.
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