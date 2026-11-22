# CT-16 Stock Count Review

**Updated:** 22 November 2026
**Status:** Partial; review/commit and local count-history workflow tested on API 37. Manual usability and production roles remain unqualified.

## Change

Before committing a physical stock count, CT-16 now presents a review dialog. For a product with an owner-confirmed stock baseline it shows the prior quantity, physical count and signed variance. When no prior baseline has been confirmed, it explicitly says the count establishes opening stock and omits a fabricated variance. The operator can return to edit counts or confirm the existing atomic stock-count command.

The review builder rejects empty selections, invalid quantities, and products that are missing or inactive. A zero count is valid. Confirming the dialog still delegates to the existing audited and idempotent stock movement path; dismissing/editing does not mutate stock.

## Local stock-count history

CT-16 now records selected final quantities in the existing immutable audit event when a multi-product physical count is confirmed. This includes products whose count equals the prior balance and therefore has no stock-movement delta. A manager-gated, read-only history screen shows its device-recorded time, reason, local actor when available, and counted product quantities. Legacy count events remain visible, but exact counts are labeled unavailable because older events did not retain them. The screen is reachable from CT-16 and CT-15 Inventory Overview; it does not mutate stock or audit records.

## Verification

- Kotlin JVM tests: **67 passed, 0 failed**. Review/history tests cover variance review, zero and unchanged counts, legacy events without quantity data, malformed event rejection, and newest-first ordering.
- Android debug APK: **PASS**.
- Android instrumentation APK: **PASS**.
- Focused Android API 37 test: **1/1 passed**, including count-history navigation, reason and exact zero/nonzero quantities, and persisted-snapshot equality before/after history review.
- Current-source isolated Android API 37 instrumentation: **111/111 passed**, 0 failures/errors/skips. It includes the updated CT-16 flow; endpoint-dependent `LiveBackendSyncE2ETest` was excluded. XML: `../test-evidence/2026-11-22/ct16-count-history-full-instrumentation.xml`.
- Android lint: **0 errors, 173 warnings**.
- Prototype Node suite: **52 passed, 0 failed**.
- Backend no-database suite: **11 passed, 0 failed, 4 skipped** because the PostgreSQL integration environment was not started for this run. A separately qualified isolated PostgreSQL 17 OW-05 run remains **15/15**.
- Full Gradle invocation completed successfully: `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`.

Evidence: `../test-evidence/2026-11-01/ct16-variance-review-gradle.log`, `ct16-variance-review-node.log`, `ct16-backend-tests.log`, `ct16-stock-count-ui-focused.xml`, and `ct17-full-final-instrumentation.xml`.

## Qualification limits

The emulator test verifies review, atomic count commit, durable audit-backed history rendering, and read-only behavior for this synthetic flow. History remains local to the encrypted device ledger and is not synchronized or exported. It does not establish staged counting, independently verified staff identity, physical-shop usability, or production authorization. Manual usability review and shop testing remain separate.

## Changed implementation

- `android/app/src/main/java/com/chaiandtapri/counter/StockCountReview.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/StockCountHistory.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/StockCountHistoryActivity.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/StockCountActivity.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/InventoryOverviewActivity.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/test/java/com/chaiandtapri/counter/CounterModelTest.kt`
- `android/app/src/test/java/com/chaiandtapri/counter/StockCountHistoryTest.kt`
- `android/app/src/test/java/com/chaiandtapri/counter/StockCountReviewTest.kt`
- `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt`

The product remains a prototype and is not production-qualified.
