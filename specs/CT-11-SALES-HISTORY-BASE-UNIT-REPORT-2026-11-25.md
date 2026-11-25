# CT-11 Sales History Base-Unit Labels and Payment Times

**Date:** 25 November 2026

**Status:** Implemented; JVM-tested, build-qualified, and focused API 37 UI-tested

## Delivered

- Sales History order cards show each line quantity with its configured product base-unit label.
- Text search matches the displayed quantity/unit/product summary.
- The Sales History CSV `items` field includes quantity, configured unit label, and product name.
- Historical rows with no matching saved label show `unit not recorded`; no unit or pack conversion is inferred.
- History cards show persisted payment-event time using the device's local date/time formatting. CSV tender-event summaries include stored epoch milliseconds; missing/legacy timestamps are explicitly `timestamp unavailable`.
- The order-date range filter retains its existing semantics: it filters order creation dates, not payment-event dates.
- CSV tender evidence and customer-identity omission behavior remain unchanged.
- Android instrumentation checks search, configured `piece` label display, payment-event timestamp visibility, persisted data, and no history mutation.

## Verification

- Kotlin JVM: **92 tests passed, 0 failures**.
- `assembleDebug`: **PASS**.
- `assembleDebugAndroidTest`: **PASS**.
- `lintDebug`: **PASS**, 0 errors / **189 warnings**.
- Focused API 37 emulator instrumentation: **1/1 passed**, XML at `test-evidence/2026-11-25/ct11-unit-payment-time/sales-history-focused-instrumentation.xml` (SHA-256 `293150E85F14429DF99C3CB80C72020BB39B95FE6A08E2F36AAD3AFA8351320C`).
- Full connected Android regression and Node suites were not rerun for this slice.
- APKs: `android/app/build/outputs/apk/debug/app-debug.apk` and `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## Limits

This is not full CT-11 qualification. Manual date-picker usability, full connected regression, and physical-device validation remain open. Payment/order timestamps use the device clock and may be inaccurate; server-authoritative time is not implemented. Units are configured labels; pack-size conversion is unsupported. Browser visual review, independent security review, physical Bluetooth/noisy-shop trials, cross-device recovery, deployment, and production qualification remain separate gates.
