# CT-16 Stock Count Review

**Date:** 1 November 2026  
**Status:** Implemented; JVM-tested and current-source API 37 instrumentation-qualified for the count-review workflow.

## Change

Before committing a physical stock count, CT-16 now presents a review dialog. For a product with an owner-confirmed stock baseline it shows the prior quantity, physical count and signed variance. When no prior baseline has been confirmed, it explicitly says the count establishes opening stock and omits a fabricated variance. The operator can return to edit counts or confirm the existing atomic stock-count command.

The review builder rejects empty selections, invalid quantities, and products that are missing or inactive. A zero count is valid. Confirming the dialog still delegates to the existing audited and idempotent stock movement path; dismissing/editing does not mutate stock.

## Verification

- Kotlin JVM tests: **54 passed, 0 failed** in the current project suite. The stock-review tests cover positive, negative and zero variances, unknown baseline handling, and invalid selection/count rejection.
- Android debug APK: **PASS**.
- Android instrumentation APK: **PASS**.
- Android API 37 full instrumentation: **76/76 passed**, including the multi-product stock-count review, confirm-and-save, ledger persistence and movement assertions. XML: `../test-evidence/2026-11-01/ct17-full-final-instrumentation.xml`.
- Android lint: **0 errors, 154 warnings**.
- Prototype Node suite: **52 passed, 0 failed**.
- Backend no-database suite: **11 passed, 0 failed, 4 skipped** because the PostgreSQL integration environment was not started for this run. A separately qualified isolated PostgreSQL 17 OW-05 run remains **15/15**.
- Full Gradle invocation completed successfully: `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`.

Evidence: `../test-evidence/2026-11-01/ct16-variance-review-gradle.log`, `ct16-variance-review-node.log`, `ct16-backend-tests.log`, `ct16-stock-count-ui-focused.xml`, and `ct17-full-final-instrumentation.xml`.

## Qualification limits

The UI test verifies the review dialog and confirms counts through the existing audited stock mutation on the emulator. This does not establish a durable count-session history, multi-stage counting, production authorization roles, or physical-shop usability. Manual visual/usability review and shop testing remain separate.

## Changed implementation

- `android/app/src/main/java/com/chaiandtapri/counter/StockCountReview.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/StockCountActivity.kt`
- `android/app/src/test/java/com/chaiandtapri/counter/StockCountReviewTest.kt`
- `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt`

The product remains a prototype and is not production-qualified.
