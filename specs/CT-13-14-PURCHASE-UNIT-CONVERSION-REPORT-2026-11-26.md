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

## Scope and limits

This is direct stock receipt conversion, not complete purchasing-unit support. Purchase-order lines still use base units. Supplier invoice package totals are not parsed: the operator enters cost per base unit. No fractional conversion factors are supported. Recipes, variable-yield production, batch/expiry lot tracking, catalog sync of conversion metadata, supplier-system integration, cross-device conflicts, manual usability and shop-floor accuracy remain open. The UI automation result needs a stable emulator rerun; this report does not claim UI qualification.

## Evidence and source

Screenshots and emulator boot logs are under `test-evidence/2026-11-26/ct13-unit-conversion/` (ignored local evidence). Source tests cover conversion, costs, audit detail, duplicate command protection, integer overflow rollback, conversion freeze, and migration/reopen.
