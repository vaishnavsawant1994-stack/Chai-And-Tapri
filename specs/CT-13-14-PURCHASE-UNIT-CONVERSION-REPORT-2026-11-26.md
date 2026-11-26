# CT-13/14 Purchase Unit Conversion — 26 November 2026

## Implemented

Products now distinguish their base/count unit from a direct-purchase unit and an integer conversion factor. Example: stock is counted and sold in bottles while a supplier delivery may be received in crates, with 1 crate = 12 bottles. Product setup and edit persist these fields; products with an order or stock history keep the conversion metadata locked. Existing products migrate additively with factor 1 and their previous unit label as the purchase unit (schema v33→v34).

Direct receipts accept quantity in the configured purchase unit and cost per base unit in integer paise. The model converts quantity using overflow checks, then posts the receipt, base-unit stock movement, payable, audit event and outbox record through the existing atomic transaction. The conversion configuration is rechecked before posting to reject stale metadata. Duplicate command IDs do not post twice.

## Verification

- Kotlin JVM: **103/103 passed**, 0 failures/errors/skips.
- Debug APK, instrumentation APK, and `lintDebug`: **PASS**. Lint: 0 errors, 194 warnings.
- Focused API 37 SQLite v33→v34 migration, converted receipt, encrypted reopen: **1/1 passed**. Preserved JUnit: `test-evidence/2026-11-26/ct13-unit-conversion/schema-v33-v34-conversion-reopen.xml`.
- Direct Procurement UI scenario: **failed/unqualified** on two headless API 37 AVDs. In each run the emulator launcher/System UI became unresponsive, the activity control never appeared, and the test failed after 15.7s / 21.3s. Screenshots show Android's launcher “isn't responding” dialog, not the app's procurement screen. Captures: `test-evidence/2026-11-26/ct13-unit-conversion/procurement-failure.png` and `procurement-failure-2.png`; latest JUnit: `android/app/build/outputs/androidTest-results/connected/TEST-CAT052_Worker_Synthetic(AVD) - 17.xml`.
- A full API 37 run on the feature branch first produced **154/154 PASS** before the final stale-conversion revalidation guard. A later full-source attempt after that guard lost the emulator during cleanup; partial result was 43 tests, 1 failure, 0 errors/skips (the failing customer-credit UI scenario was accompanied by ADB device loss). Do not treat either run as exact-source full-suite qualification after the final guard.

## Direct Procurement UI follow-up — 26 November 2026

The previously blocked `directProcurementScreenConvertsConfiguredCratesToBottleStock` UI test was rerun on the dedicated, booted API 37 `Tapri_Recovery_Clean_2026` AVD and passed **1/1**. It created a synthetic product counted in bottles and purchased in crates (12 bottles per crate), confirmed 5 bottles of opening stock, opened the real Procurement screen, selected the supplier/product in the direct receipt flow, and posted 2 crates at 125 paise per bottle. After reopening encrypted SQLite, the test asserted 29 bottles on hand, 3,000 paise supplier payable, a 24-bottle receipt movement, and the audited crate-to-bottle conversion details. The test cleans up its synthetic local database after the assertions.

This supersedes the earlier unqualified UI result caused by two other headless AVDs displaying launcher/System UI ANRs before Procurement opened. Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Clean_2026(AVD) - 17.xml` (1 test, 0 failures/errors/skips). No full Android suite or physical shop workflow was run in this follow-up.

## Product Editor unit configuration follow-up — 26 November 2026

The `catalogueUiCreatesConfirmedProductAndQuickSalePostsItsConfiguredPrice` API 37 UI test now creates the synthetic product with sell/count unit `cup`, purchase unit `crate`, and factor 12. It verifies those exact fields persist in the encrypted catalogue, records the opening stock, edits aliases/favourite, then checks Quick Sale renders `1 cup × E2E Lemon Chai` and posts the canonical configured price with the expected stock/payment/audit effects. The focused current-source instrumentation passed **1/1** on `Tapri_Recovery_Clean_2026` at 00:33:33 UTC on 26 November 2026. JUnit evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Clean_2026(AVD) - 17.xml`.

Together with the [direct receipt UI follow-up](#direct-procurement-ui-follow-up--26-november-2026), Product Editor → stock confirmation → receipt → Quick Sale now has individually tested UI paths for integer pack conversion. This is not a single uninterrupted UI scenario and does not qualify the full catalogue/procurement modules.

## Scope and limits

This is direct stock receipt conversion, not complete purchasing-unit support. Purchase-order lines still use base units. Supplier invoice package totals are not parsed: the operator enters cost per base unit. No fractional conversion factors are supported. Recipes, variable-yield production, batch/expiry lot tracking, catalog sync of conversion metadata, supplier-system integration, cross-device conflicts, manual usability and shop-floor accuracy remain open. The UI automation result needs a stable emulator rerun; this report does not claim UI qualification.

## Evidence and source

Screenshots and emulator boot logs are under `test-evidence/2026-11-26/ct13-unit-conversion/` (ignored local evidence). Source tests cover conversion, costs, audit detail, duplicate command protection, integer overflow rollback, conversion freeze, and migration/reopen.
