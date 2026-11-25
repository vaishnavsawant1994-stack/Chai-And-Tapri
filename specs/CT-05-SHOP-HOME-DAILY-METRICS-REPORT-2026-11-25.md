# CT-05 Shop Home Daily Ledger Metrics — 25 November 2026

## Delivered

Shop Home now adds a device-local-date activity section sourced from `DailyReportProjectionCalculator` and the encrypted local ledgers:

- Sales from orders created on the displayed local date, net of current recorded refunds.
- Staff-declared cash collection entries recorded on that date. These are not externally verified.
- Current pending UPI balance for orders created on that date. This is not a confirmed collection.
- Current customer receivables linked to orders created on that date.
- Dated operating expenses recorded on that date.
- A count of legacy undated records excluded from the date totals.

The screen labels the local-date basis and explicitly says that it does not show profit, verified UPI, bank settlement, or a completed daily close. Existing lifetime summary metrics remain available below.

## Verification

- Kotlin JVM: **82/82 passed** (`testDebugUnitTest`).
- Debug APK: **PASS** (`assembleDebug`).
- Android test APK: **PASS** (`assembleDebugAndroidTest`).
- Focused API 37 Shop Home instrumentation: **1/1 passed**, 0 failures/errors/skips.
- Full API 37 connected instrumentation regression after the change: **140/140 passed**, 0 failures/errors/skips.
- Android lint: **PASS**, 0 errors / 186 warnings.
- Instrumentation XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (local generated build output).

## Scope limits

The focused and full UI tests verify the daily metrics appear in the real emulator screen. The date-scoped arithmetic is covered by the existing `DailyReportProjectionTest`. Device time and timezone remain the source of date attribution. Current pending balances are point-in-time values; this does not establish a complete daily-close workflow or profit calculation. The full emulator regression result is in the local XML artifact noted above.
