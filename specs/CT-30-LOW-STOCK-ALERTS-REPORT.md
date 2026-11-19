# CT-30 Low-Stock Alerts

**Date:** 30 October 2026  
**Status:** Implemented as a local prototype slice; focused model and API 37 checks passed.

## Behavior

Each product may have an optional reorder threshold of 1–100000 sellable units. The value is stored in encrypted SQLite schema v19. The additive v18→v19 migration preserves existing catalogue rows, stock, and sales; existing products migrate with no threshold until configured.

The catalogue editor exposes the optional threshold. CT-30 reports low stock only for active products with a configured threshold and a physically confirmed stock baseline. It compares the persisted balance to the threshold and displays the current count. Unconfirmed stock is not treated as zero and continues to use the separate opening-stock-required alert. No purchase order is created and no stock is estimated from missing sales.

## Verification

- Kotlin JVM: 35/35 passed, including threshold bounds and comparison behavior.
- API 37 focused migration test passed: a v18→v19 upgrade preserved a synthetic sale and stock count; threshold was null after migration and persisted after editing.
- API 37 focused alert test passed: 2 confirmed units against a threshold of 3 produced the expected alert.
- Node prototype suite: 49/49; backend projection contract: 5/5.
- Debug and instrumentation APK build tasks passed; lint: 0 errors, 114 warnings.
- Fresh complete regression after correcting UI text and the customer-profile test helper: Kotlin JVM 35/35 and API 37 instrumentation 47/47 (0 failed, 0 errors, 0 skipped; Gradle exit 0).
- Node prototype suite 49/49; backend projection contract 5/5. Combined passing methods across these four separate suites: 136.
- `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`, and `connectedDebugAndroidTest` all completed successfully in one Gradle invocation (BUILD SUCCESSFUL, 5m 26s). Lint: 0 errors / 114 warnings.
- Emulator instrumentation evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`; exit code: `android/app/build/outputs/androidTest-results/connected/debug/test-result-exit-code.txt`. JVM evidence: `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`.

The threshold editor dialog was not directly qualified end-to-end. The model, encrypted storage, migration, and alert rendering were tested. CT-30 remains partial; due-date alerts, sync alerts, and automated replenishment remain unimplemented. No physical headset, noisy shop, independent security review, cross-device recovery, or production qualification is implied by this emulator run.


## Editor UI qualification ? 30 October 2026

Added `catalogueEditorPersistsLowStockThresholdAndAlertReadsIt`: on the API 37 emulator it edits a configured product through Product Catalogue, saves a threshold, verifies the value in a fresh encrypted database instance, and verifies the low-stock calculation against the previously confirmed stock count. The focused test passed, followed by the full 48/48 instrumentation suite (0 failed, 0 errors, 0 skipped; exit code 0). Kotlin JVM 35/35; Node 49/49; backend payload contract 5/5; APK build and lint passed (0 lint errors / 114 warnings). This completes UI-path coverage for this threshold slice only; CT-30 still lacks due-date and sync alerts and automated replenishment.
