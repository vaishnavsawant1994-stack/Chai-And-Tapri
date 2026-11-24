# Android Regression Qualification — 24 November 2026

## Result

The current-source, serial-pinned Android API 37 instrumentation suite passed **136/136**: 0 failures, 0 errors, 0 skipped. It ran on `Tapri_Recovery_Isolated` (`emulator-5554`) using `android/scripts/run-isolated-connected-tests.cmd`; the runner excluded only the opt-in live-backend classes because no isolated backend endpoint was configured for this suite.

## Failure investigation and correction

The first full run completed 136 tests with 1 failure in `salesHistoryRefundsSplitTenderReceivableBeforeCashWithoutRestocking`. The accessibility click returned successfully, but the test read `isChecked` immediately before Android had refreshed the accessibility node. A bounded wait now re-reads the live node until the checked state is observable. This changes test synchronization only; refund allocation and transaction code were not changed.

The focused CT-28 daily-closing review passed 1/1 after restarting the AVD launcher. The focused refund test then passed 1/1 with the polling assertion, followed by the full 136/136 pass.

## Current-source checks

| Suite/check | Result |
| --- | ---: |
| Prototype JavaScript | 52 passed, 0 failed |
| Kotlin JVM | 81 passed, 0 failed |
| Android instrumentation | 136 passed, 0 failed, 0 errors, 0 skipped |
| Backend Node suite | 28 passed, 0 failed, 5 skipped (PostgreSQL-dependent) |
| Debug APK and instrumentation APK build tasks | PASS |
| Android lint | 0 errors, 182 warnings |

The PostgreSQL-backed backend suite was not started in this continuation. The latest separate isolated PostgreSQL result is documented in the CAT-052 staff invitation Android/PostgreSQL E2E report; it is not counted as a test from this run.

## Evidence locations

- Android instrumentation JUnit: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`
- Kotlin JVM JUnit files: `android/app/build/test-results/testDebugUnitTest/`
- Lint report: `android/app/build/reports/lint-results-debug.txt`
- Instrumentation Gradle task: `:app:connectedDebugAndroidTest` completed successfully in 16m 7s after the corrected test.
- Host suites: `node --test prototype/*.test.cjs`, `npm.cmd test` from `backend/`.

Build/test evidence under Gradle output directories and `test-evidence/` is local and ignored by Git unless explicitly stated otherwise.

## Qualification boundaries

This is emulator qualification for the cases in the 136-test suite. It does not qualify physical Android devices, a Bluetooth headset microphone, continuous voice recognition, noisy-shop speech, field use, full product acceptance, browser visual review, independent security review, production deployment, or production readiness. CAT-051B low-storage/hard power interruption/physical key invalidation and independent recovery review also remain open.
