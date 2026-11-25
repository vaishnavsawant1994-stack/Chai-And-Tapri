# Android regression rerun — 25 November 2026

## Scope

This rerun followed fixes to two stale v31 schema assertions and an asynchronous accessibility-test race in supplier payment. It used the serial-pinned API 37 `Tapri_Recovery_Isolated` emulator. The opt-in live backend sync E2E classes remained excluded.

## Results

| Check | Result |
| --- | --- |
| Prototype Node tests | 52 passed, 0 failed |
| Backend Node tests without PostgreSQL | 33 passed, 0 failed, 6 PostgreSQL-dependent skipped (39 total) |
| Kotlin JVM tests | 96 passed, 0 failed |
| Debug APK build | Passed |
| Instrumentation APK build | Passed |
| Android lint | Passed, 0 errors, 189 warnings |
| Full Android connected suite | 149 passed, 1 failed, 0 errors, 0 skipped (150 total) |
| Isolated Quick Sale UI rerun | 1 passed, 0 failed |
| Isolated supplier UPI UI rerun | 1 passed, 0 failed |

The single full-suite failure was `quickSaleUiRecordsCashAndUpiPendingAsDifferentPaymentStates`. `MonitoringInstrumentation.startActivitySync` could not launch `QuickSaleActivity` within its 45-second idle window during the long full run. It did not fail a sale, payment, inventory, or receipt assertion. The same test passed in an isolated API 37 invocation immediately after the suite. Emulator logs during the full run showed severe frame delays and high UI load. Treat full-suite qualification as **not fully green** because the in-context launch timeout remains reproducible in a long run.

The two schema migration tests passed after they were changed to assert `SqliteCounterPersistence.DATABASE_VERSION`; migration from v30/v25 correctly continues through all migrations to the current v33 schema.

The supplier UPI screen test now waits for the committed-result message before opening a second encrypted database connection. It passed within the full suite and as a focused test. This removes a test-only race between the accessibility click and UI transaction callback.

Post-run triage tried replacing `startActivitySync` with an activity-monitor launch to avoid the full-run idle timeout. That variant failed to attach the accessibility tree to Quick Sale and was reverted. The committed test retains the original `startActivitySync` flow, which passed in the focused run recorded above. The generated XML was consequently overwritten by this reverted experiment and is not evidence for the final source.

## Evidence paths

- Full run command: `android/scripts/run-isolated-connected-tests.cmd emulator-5554`.
- Focused Quick Sale command: `gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chaiandtapri.counter.SqliteCounterPersistenceTest#quickSaleUiRecordsCashAndUpiPendingAsDifferentPaymentStates`.
- Focused supplier command: same Gradle task filtered to `#procurementPaymentDialogRecordsUpiAsStaffDeclaredWithoutChangingCashDrawer`.
- The full-run emulator per-test logcat files are under `android/app/build/outputs/androidTest-results/connected/debug/Tapri_Recovery_Isolated(AVD) - 17/`. The later focused invocation replaced the suite-level JUnit XML, so the report records the observed 149/150 aggregate and preserved per-test emulator logs rather than claiming a surviving full-suite XML artifact.
- Local build/test outputs are generated beneath `android/app/build/` and are not committed.

## Source changes

- Supplier payment UI instrumentation waits for the successful commit feedback before querying its persisted payment.
- v30 and v25 migration assertions compare against the current schema version rather than hardcoding v31.

This does not qualify physical Bluetooth audio, real-shop operation, cross-device recovery, production storage, or independent security review. The complete Android suite should be rerun on an emulator with adequate sustained resources before broad Android regression is marked green.
