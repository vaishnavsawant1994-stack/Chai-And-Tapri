# CT-11 — Sales and Order History

**Current status:** Partial Android workflow; search and order-creation date range are emulator-tested. Manual usability review remains pending.

## Implemented

- Reads persisted local orders, financial totals, order state and payment-event history from the encrypted store.
- Search filters by order number, product/item text, fulfillment/payment state, cancellation reason, and payment-event method/status/kind.
- Inclusive local date range filters use the order's persisted creation timestamp.
- Legacy records migrated from older schemas keep timestamp `0`, display as undated, and are excluded only when a date filter is active; the migration does not manufacture historical dates.
- A no-match state is shown. Search and date filtering are read-only.
- Order detail and refund/correction navigation remain available from each result card.

## Persistence change

SQLite schema v16 adds `orders.created_at`, defaulting to `0` for migrated legacy rows. New orders persist their creation time in epoch milliseconds. The v15-to-v16 migration is additive. Portable database snapshots include the column under schema v16.

## Verification

- Android API 37 instrumentation: 34/34, 0 failures/errors/skips.
- Kotlin JVM: 30/30, 0 failures/errors/skips.
- Node: 48/48, 0 failures.
- Debug and Android instrumentation APK builds: PASS.
- Android lint: 0 errors / 104 warnings.
- Combined: 112 automated tests across separate suites.
- Instrumentation covers search results, no-match behavior, untouched persisted orders, timestamp persistence across reopen, and legacy migration to schema v16 with legacy dates remaining unknown.
- JVM tests cover inclusive date boundaries, open-ended ranges, and exclusion of legacy undated records when date filtering is active.

Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, and `android/app/build/reports/lint-results-debug.txt`.

## Limitations

- Dates represent order creation; payment events do not yet have independently exposed timestamps.
- Manual date-picker usability and accessibility review is pending.
- This does not complete CT-11, the broader CT-01–32 Android app, or the six-wave plan.
