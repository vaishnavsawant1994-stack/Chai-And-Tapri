# CT-05 First-Sale Checklist Navigation — 26 November 2026

## Change

Shop Home now presents only the next required owner setup action while the first-sale checklist is incomplete. The previous identity, catalogue and stock buttons duplicated that next-step route and could invite owners to skip earlier setup steps. Once shop identity, catalogue and opening stock are confirmed, Shop Home displays an explicit completion message and keeps Quick Sale as the normal selling entry.

The checklist remains read-only: opening a setup destination does not write business records. Existing product, stock, sale and transaction behavior is unchanged.

## Verification

- Focused API 37 emulator test `shopHomeShowsMissingFirstSaleSetupAndRoutesToStockCount`: **1/1 passed**.
- Focused API 37 emulator test `ownerCanCompleteGuidedSetupAndRecordFirstSaleFromShopHome`: **1/1 passed**, including a synthetic configured product, physical opening count, native Quick Sale and a recorded cash sale.
- Kotlin JVM suite: **98/98 passed** (Gradle test XML).
- Debug APK build: **passed** (`android/app/build/outputs/apk/debug/app-debug.apk`).
- Android lint: **passed**, 0 errors / 191 warnings.
- A subsequent full `connectedDebugAndroidTest` run was interrupted while a long customer-credit UI scenario was still active. It did not produce a final suite result; no full instrumentation pass is claimed. The focused tests above were completed separately before this run.

## Evidence and scope

The test sources are in `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt`. Gradle unit-test XML is in `android/app/build/test-results/testDebugUnitTest/`. Emulator reports/logs are under `android/app/build/outputs/androidTest-results/connected/debug/`.

This change qualifies the first-sale checklist routing and the existing guided first-sale journey on the API 37 emulator. It does not qualify every Shop Home workflow, physical devices, Bluetooth audio, a production business day, or production readiness.
