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
