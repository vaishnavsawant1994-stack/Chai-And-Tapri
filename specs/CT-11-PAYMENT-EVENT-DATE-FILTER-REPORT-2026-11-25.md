# CT-11 Sales History payment-event date basis

**Date:** 25 November 2026
**Status:** Implemented and focused API 37 emulator-tested; CT-11 remains partial.

## Delivered

Sales History now offers two clearly labeled date-filter bases:

- **Order created:** includes orders whose persisted creation timestamp falls within the inclusive local date range.
- **Payment event:** includes an order when at least one of its payment events has a persisted timestamp in the inclusive local date range.

The date-basis control and range status communicate which timestamp is being used. Search and CSV export use the same filtered order set. If the range is cleared, all orders appear even when an order has no payment events or an old record has no timestamp. With a selected range, legacy/missing timestamps do not match. Existing device-clock caveats remain visible.

## Verification

- Kotlin JVM: **96/96 passed** (`testDebugUnitTest`), including date-basis boundary and legacy timestamp cases.
- API 37 instrumentation: **1/1 passed**, `salesHistorySearchFiltersPersistedOrdersAndPaymentStatesWithoutMutation`; verified the Android screen exposes the date-basis control, toggles to payment-event dates, keeps the order list visible without an active range, and leaves persisted orders unchanged.
- Debug APK and instrumentation APK builds: passed.
- Android lint: passed, **0 errors / 189 warnings**.
- Prototype Node: **52/52 passed** (`node --test 'prototype/*.test.cjs'`); no JS source changed.
- Backend suite was not rerun; no backend source changed.
- The full Android connected suite was not rerun.

## Limits

This is local history based on timestamps recorded on the device. Payment-event dates are not an authoritative bank settlement date, timezone-normalized accounting period, or full business-day report. The focus test verifies the basis control and read-only view; inclusive/boundary matching is covered by JVM helper tests rather than driving the date picker through every boundary in UI automation. CT-11 and the product remain partial.

## Files changed

- `android/app/src/main/java/com/chaiandtapri/counter/OrderHistoryDateRange.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/SalesHistoryActivity.kt`
- `android/app/src/test/java/com/chaiandtapri/counter/CounterModelTest.kt`
- `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt`
- `PROJECT_PLAN.md`
- `specs/PROJECT-PROGRESS-AUDIT-2026-11-25.md`
- `specs/MASTER-IMPLEMENTATION-TRACKER.md`
