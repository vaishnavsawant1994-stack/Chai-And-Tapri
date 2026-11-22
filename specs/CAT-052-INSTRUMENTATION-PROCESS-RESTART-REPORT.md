# CAT-052 SQLCipher outbox recovery across process termination

**Date:** 22 November 2026  
**Status:** Instrumentation-process restart verified on API 37; app background-worker lifecycle remains unqualified.

## Scenario

The focused Android test was run in two independent instrumentation invocations against the isolated HTTPS/PostgreSQL 17 backend. In phase one, the test registered a synthetic shop, wrote opening stock and a cash sale into the app-scoped SQLCipher database, and synchronized the opening-stock event. The backend committed the sale, after which the test transport discarded the acknowledgment. The test confirmed the sale remained in the durable outbox, stored only test identifiers in the app's no-backup directory, and returned while intentionally leaving the SQLCipher model/database connection open.

The first instrumentation invocation then exited. Phase two ran in a new instrumentation process; it asserted the process ID had changed, loaded the encrypted session vault and database, verified the sale and stock state plus the pending event and cursor, retried the same event, and checked the server revision, accepted-event count, order count and staff-declared cash. It then logged out and removed the synthetic database, namespaced key material, session, and test-state file.

## Results

- Prepare phase: **1 test passed**.
- Resume/retry phase: **1 test passed**.
- Debug and instrumentation APK build: **passed**.
- Android endpoint remained loopback-only; emulator access used `adb reverse`.
- The isolated PostgreSQL cluster and temporary server were stopped; ADB reverse was removed.
- Phase logs: `../test-evidence/2026-11-22/android-sync-e2e-refresh/process-death-phase-prepare.txt` and `process-death-phase-resume.txt` (local ignored synthetic evidence).

## Qualification boundary

This proves the persisted SQLCipher outbox and encrypted session can be reopened by a new instrumentation process after the prior process ended with an open connection and a pending sale. The test's model operations run from Android instrumentation against the app-scoped data directory; it does not prove that the production app process, WorkManager worker, or in-flight background HTTP request can be killed and safely resumed. It does not test physical devices, production synchronization, independent security review, or deployment. CAT-052 remains prototype-qualified.
