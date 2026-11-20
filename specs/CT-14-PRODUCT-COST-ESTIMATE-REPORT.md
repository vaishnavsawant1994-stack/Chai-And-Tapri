# CT-14 product cost estimate foundation

**Date:** 20 November 2026  
**Status:** Implemented and tested for the documented prototype scope.

## Delivered

- Product catalogue records may hold an optional owner-entered estimated unit cost in integer paise.
- Catalogue validation accepts only `0..10,000,000` paise; blank means unknown. The product editor and catalogue label identify this as an estimate, not a verified purchase valuation.
- A newly recorded configured-product order line snapshots the current estimate. Later catalogue edits do not rewrite that line's historical estimate.
- SQLite schema v26 adds nullable estimate columns to product catalogue and order lines. The v25 upgrade is additive; old products/orders retain `NULL` and no historical cost is inferred.
- Existing portable database snapshots include the columns as part of the encrypted database.
- Android includes the optional nullable `estimatedUnitCostPaise` snapshot in its allow-listed sale-line sync object. The backend validates integer paise and accepts legacy v1 lines that omit this field. No supplier, customer, invoice, or free-text details are added.
- The owner profit-readiness endpoint reports how many latest accepted sale lines with net delivery carry an estimate. Counts are by line, not pooled item quantities across products with different units.
- Gross profit and operating profit remain unavailable. The endpoint still identifies absent purchase cost layers, opening inventory, recipes, sale-date cost allocation, and complete operating expenses.

## Verification

- Kotlin JVM: 60 tests, 0 failures.
- API 37 focused instrumentation: `estimatedCostSnapshotSurvivesCatalogueEditsRestartAndV25Migration`, 1/1 passed. It checks a synthetic cash sale, durable outbox JSON cost snapshot, catalog cost edit, encrypted database reopen, preservation of the original line estimate, and v25 migration preserving sale value while leaving legacy cost unknown.
- `testDebugUnitTest`, `assembleDebug`, and `assembleDebugAndroidTest`: passed.
- Prototype Node: 52/52 passed. Backend Node: 23/23 passed with no skips against an isolated PostgreSQL 17 cluster using the restricted runtime role; the cluster was stopped after the run.
- Full API 37 instrumentation: 93 tests, 0 failures, 0 errors, 0 skips on the preceding local cost-snapshot source. The focused instrumentation above passed again after the sync-field change; the complete suite was not rerun after that narrow change.
- Android lint: 0 errors, 161 warnings. Debug and instrumentation APK builds passed.

## Accounting limits

This is a manually entered estimate snapshot, not inventory valuation, recipe costing, cost of goods sold, gross profit, or operating profit. The product editor does not yet reconcile estimates against supplier invoices or stock layers. Food recipes, unit conversions, returns/cost allocation, and complete expense coverage remain unimplemented. The coverage indicator is a readiness count across all currently accepted latest order states, not a date-range profit report. OW-03 must continue returning profit as unavailable until these sources and coverage rules are implemented and verified.
