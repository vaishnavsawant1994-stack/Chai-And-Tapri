# CT-30 Low-Stock Alerts

**Date:** 30 October 2026  
**Current status:** Local alert prototype with low-stock configuration, alert rendering, and review navigation covered by selected API 37 tests; CT-30 remains partial.

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

At the initial model-test stage, threshold editor UI qualification was pending; the later editor UI qualification below supersedes that status. CT-30 remains partial; due-date alerts, sync alerts, and automated replenishment remain unimplemented. No physical headset, noisy shop, independent security review, cross-device recovery, or production qualification is implied by these emulator runs.


## Editor UI qualification ? 30 October 2026

Added `catalogueEditorPersistsLowStockThresholdAndAlertReadsIt`: on the API 37 emulator it edits a configured product through Product Catalogue, saves a threshold, verifies the value in a fresh encrypted database instance, and verifies the low-stock calculation against the previously confirmed stock count. The focused test passed, followed by the full 48/48 instrumentation suite (0 failed, 0 errors, 0 skipped; exit code 0). Kotlin JVM 35/35; Node 49/49; backend payload contract 5/5; APK build and lint passed (0 lint errors / 114 warnings). This completes UI-path coverage for this threshold slice only; CT-30 still lacks due-date and sync alerts and automated replenishment.

## Alert review navigation — 22 November 2026

Low-stock and unconfirmed-opening-stock alerts now include a direct **Review inventory** action. Other alert types route to the closest existing operational screen: open orders to Live Orders, pending UPI to Payments, customer receivables to Customer Credit, supplier balances to Procurement, open shifts to Staff Shifts, and cash differences to Daily Closing. Navigation only opens the related screen; it does not change business records.

Added `lowStockAlertReviewOpensInventoryWithoutChangingLedger`. It creates a synthetic product with two confirmed units and a threshold of four, opens Business Alerts, taps the low-stock review action, verifies CT-15 Inventory launches, and compares the complete persisted snapshot before and after. Focused API 37 test passed 1/1; the serial-pinned full instrumentation suite passed 106/106 with zero failures/errors/skips. Node 52/52, Kotlin JVM 63/63, debug/instrumentation APK builds passed, lint 0 errors / 172 warnings. Endpoint-dependent live-backend instrumentation was excluded and remains a separate integration run.

Evidence: `../test-evidence/2026-11-22/ct30-alert-routing-full-suite.xml` and `../test-evidence/2026-11-22/ct30-alert-routing-full.log`. Only the low-stock-to-inventory destination has direct click-through UI coverage; other destinations are wired but not individually qualified. The screen remains a local alert summary. Dated overdue balances, server-sync errors, alert preferences/threshold management from this screen, and automatic replenishment are not implemented; no physical-device or production qualification is implied.
