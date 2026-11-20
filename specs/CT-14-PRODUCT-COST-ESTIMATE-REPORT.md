# CT-14 product cost estimate foundation

**Date:** 20 November 2026  
**Status:** Implemented and tested for the documented prototype scope.

## Delivered

- Product catalogue records may hold an optional owner-entered estimated unit cost in integer paise.
- Catalogue validation accepts only `0..10,000,000` paise; blank means unknown. The product editor and catalogue label identify this as an estimate, not a verified purchase valuation.
- A newly recorded configured-product order line snapshots the current estimate. Later catalogue edits do not rewrite that line's historical estimate.
- SQLite schema v26 adds nullable estimate columns to product catalogue and order lines. The v25 upgrade is additive; old products/orders retain `NULL` and no historical cost is inferred.
- Existing portable database snapshots include the columns as part of the encrypted database. The sync payload remains unchanged because catalog and purchase cost contracts are not yet approved for synchronization.

## Verification

- Kotlin JVM: 60 tests, 0 failures.
- API 37 focused instrumentation: `estimatedCostSnapshotSurvivesCatalogueEditsRestartAndV25Migration`, 1/1 passed. It checks a synthetic two-item cash sale, catalog cost edit, encrypted database reopen, preservation of the original line estimate, and v25 migration preserving sale value while leaving legacy cost unknown.
- `testDebugUnitTest`, `assembleDebug`, and `assembleDebugAndroidTest`: passed.
- Prototype Node: 52/52 passed. Backend Node: 18 passed, 4 PostgreSQL-dependent tests skipped because no test database was configured; this slice changes no backend code.
- Full API 37 instrumentation: 93 tests, 0 failures, 0 errors, 0 skips.
- Android lint: 0 errors, 161 warnings. Debug and instrumentation APK builds passed.

## Accounting limits

This is a manually entered estimate snapshot, not inventory valuation, recipe costing, cost of goods sold, gross profit, or operating profit. The product editor does not yet reconcile estimates against supplier invoices or stock layers. Food recipes, unit conversions, returns/cost allocation, complete expense coverage, Android-to-server cost sync, and owner profit calculation remain unimplemented. OW-03 must continue returning profit as unavailable until these sources and coverage rules are implemented and verified.
