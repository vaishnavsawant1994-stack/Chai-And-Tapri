# CAT-052 Durable Sync Outcome Journal — Implementation Note

Date: 30 October 2026

## Change

Android encrypted SQLite schema is now version 20. The sync outbox stores an optional server revision for each event. `CounterPersistence` exposes pending sendable events, the acknowledged revision cursor, and an atomic sync-outcome write. Outcomes accepted/duplicate acknowledge an event and update the cursor monotonically; conflict records the server revision and blocks later queued events. Repeated identical responses are idempotent; changed responses for an acknowledged/conflicted event are rejected. Only the earliest eligible queued event can be acknowledged or conflicted, preventing out-of-order responses from skipping the queue head. The additive v19→v20 migration adds one nullable column and preserves existing outbox contents.

Implementation: `android/app/src/main/java/com/chaiandtapri/counter/CounterPersistence.kt` and `SqliteCounterPersistence.kt`. Emulator scenario: `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt`, test `syncAcknowledgementAndConflictAreDurableAndCursorNeverSkipsConflict`.

## Verification

- Node: 49 passed, 0 failed (`node --test prototype/*.test.cjs`).
- Kotlin JVM: 35 passed, 0 failed (`testDebugUnitTest`).
- Backend projection contract: 5 passed, 0 failed (`node --test test/sync-contract.test.mjs`).
- Focused API 37 instrumentation for v19→v20 migration, out-of-order rejection, idempotent acknowledgement, conflict blocking and durable state: 1 passed, 0 failed.
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`: passed. Lint: 0 errors, 114 warnings.
- Full API 37 instrumentation attempt: 49 tests, 44 passed, 5 failed. Failures: `catalogueEditorPersistsLowStockThresholdAndAlertReadsIt`, `inventoryRouteSavesMultiProductPhysicalCountAsOnePersistentSession`, `customerDirectoryAddProfileDialogSavesContactOnlyAfterConsent`, `paymentScreenConfirmsAndRecordsPartialStaffDeclaredUpiReceipt`, `customerCreditScreenShowsPersistedBalanceAndRepaymentEvents`. The first, second, third and fifth reported missing accessibility controls; the payment test reported the active UI was a Pixel Launcher not-responding dialog. These remain unresolved. The two UI launch tests that timed out in an earlier run passed when run individually. The focused sync XML currently at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` is the 1/1 run, not the 49-test run. JVM XML and lint report remain in their Gradle output directories.

## Scope limits

This is not authenticated synchronization. No Android HTTP client, device enrollment, protected access token, WorkManager delivery/retry loop, remote authoritative financial projection, or end-to-end acknowledgement/conflict UI exists. The backend contract suite validates request shapes; the earlier synthetic PostgreSQL integration result was not repeated in this run. The persisted cursor and journal are local building blocks only.

The Android full-suite failures mean complete emulator UI qualification is not green. CAT-051B recovery qualification, CAT-052 transport, Android shopkeeper workflows, owner dashboard, continuous Bluetooth, physical device/field trials, independent security review, and production readiness remain open.

## Superseding verification — emulator launcher recovery and CAT-052 journal — 30 October 2026

The earlier full-suite result with five UI failures was caused by the emulator's Pixel Launcher entering an ANR dialog, which obscured the app from UI automation. Captured evidence at `test-evidence/emulator-launcher-anr.png`; restarting only the emulator launcher cleared the dialog (`test-evidence/emulator-launcher-restarted.png`). The five previously failing UI tests then passed individually. The complete current-tree run subsequently passed: Node 49/49, Kotlin JVM 35/35, API 37 Android instrumentation 49/49 (133 passed across these three app suites), backend projection contract 5/5 (138 method executions across four suites), debug APK and instrumentation APK tasks PASS, lint 0 errors / 114 warnings. Full command: `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug connectedDebugAndroidTest --console=plain` from `android/`; Node and backend contract commands were run separately. The API 37 XML at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` now reports 49 tests, 0 failures, 0 errors, 0 skipped. This verifies the emulator's current test run, not physical-device or field behavior.

CAT-052 update: schema v20 durably records per-event accepted/duplicate/conflict outcome and server revision; the stored server-revision cursor advances monotonically and an out-of-order response cannot acknowledge past the earliest eligible outbox event. The v19→v20 migration, retries, conflict blocking, cursor persistence, and this queue-head guard pass an API 37 test. Android network delivery, credential/device enrollment, background retries, conflict-resolution UI, and authoritative server financial projection are still outstanding. CAT-051B functional same-emulator recovery is documented as passed in its implementation report; independent security review, hard power-loss/low-storage qualification and second-device recovery remain open. The six-wave project remains in progress.

## CAT-052 Android sync-runner slice — 30 October 2026

Added a manually invokable Android sync runner over the existing allow-listed outbox. It builds the authenticated backend envelope, pushes one event per request in revision order, validates event-correlated responses and persists accepted/duplicate/conflict results through the schema-v20 journal. The HTTP transport requires HTTPS/platform TLS, forbids redirects and credentials in URLs, bounds response sizes, and keeps bearer tokens out of persistent storage/logging. Android INTERNET permission is declared; cleartext remains disabled. Focused API 37 tests passed 4/4. Kotlin JVM 35/35, Node 49/49, backend contract 5/5, APK build tasks PASS, lint 0 errors/114 warnings. The latest full Android run remains flaky: 48/53 passed; the same five accessibility UI tests failed while Pixel Launcher was in ANR. No authenticated network server was contacted. Login/session provisioning, protected token vault, settings/endpoint UX, WorkManager/retry, conflict UI, server projections, and real HTTP integration remain unimplemented. See `CAT-052-ANDROID-SYNC-RUNNER-REPORT.md`.
