# CT-11 Sales History Base-Unit Labels

**Date:** 25 November 2026  
**Status:** Implemented; JVM-tested and build-qualified; Android UI runtime assertion pending

## Delivered

- Sales History order cards show each line quantity with the configured product base-unit label.
- Text search matches the displayed quantity/unit/product summary.
- The Sales History CSV `items` field includes quantity, configured unit label, and product name.
- Historical rows with no matching saved label show `unit not recorded`; no unit or pack conversion is inferred.
- CSV tender evidence and customer-identity omission behavior remain unchanged.
- Added an Android instrumentation assertion for a configured `piece` label.

## Verification

- Kotlin JVM: **91 tests passed, 0 failures**.
- `assembleDebug`: **PASS**.
- `assembleDebugAndroidTest`: **PASS**; the new instrumentation source compiled.
- `lintDebug`: **PASS**, 0 errors. Existing warnings remain; this report does not claim a warning-free lint run.
- `adb devices -l`: no devices attached. The new UI instrumentation assertion was not run. Full Android connected regression and Node suites were not rerun for this Android-only slice.
- APKs: `android/app/build/outputs/apk/debug/app-debug.apk` and `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## Limits

This is not full CT-11 qualification. Payment event timestamps, manual date-picker usability, full connected regression, and physical-device validation remain open. Units are configured labels; pack-size conversion is unsupported. Browser visual review, independent security review, physical Bluetooth/noisy-shop trials, cross-device recovery, deployment, and production qualification remain separate gates.
