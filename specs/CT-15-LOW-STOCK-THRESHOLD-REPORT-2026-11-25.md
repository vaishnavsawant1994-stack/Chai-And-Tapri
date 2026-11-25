# CT-15 — Owner-Configured Low-Stock Alerts

**Date:** 25 November 2026
**Qualification:** Focused API 37 emulator test passed; this is a limited CT-15 slice.

## Behavior

Inventory Overview now lists active products at or below an owner-configured reorder threshold, but only when their physical stock count is confirmed. Products without confirmed counts are excluded and the screen explains why. The alert is explicitly informational; it does not create a purchase suggestion or modify inventory.

## Verification

- Focused `SqliteCounterPersistenceTest.inventoryOverviewShowsOnlyConfirmedStockBelowOwnerThreshold`: **1 passed, 0 failed/errors/skipped** on the isolated API 37 emulator.
- The synthetic fixture includes one confirmed product below threshold and one unconfirmed product with a threshold. The test checks the alert, the unverified-stock disclosure, omission of the unconfirmed product from alerts, and an unchanged persisted snapshot.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest`: **PASS** (Kotlin JVM suite 86/86 at this source revision; both APK tasks passed).
- `lintDebug`: **PASS**, 0 errors, 187 warnings.
- `git diff --check`: **PASS**.

JUnit evidence: [`focused instrumentation XML`](../test-evidence/2026-11-25/ct15-inventory-low-stock-focused-instrumentation.xml).

## Limits

This run did not rerun Node tests or the full connected instrumentation suite. The feature depends on accurate owner-confirmed physical counts and configured thresholds. It does not implement replenishment forecasting, supplier selection, purchase orders, or stock accuracy qualification. CT-15 and the product remain partial; physical-device, field, independent security, and production gates remain open.
