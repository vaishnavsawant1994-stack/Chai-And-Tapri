# CT-27 local staff handover recipient binding

**Date:** 25 November 2026
**Status:** Implemented and focused emulator-tested; CT-27 remains partial.

## Change

Staff shift close can now associate the receiving-person label with an enabled account in the device-local staff directory. The account identifier is saved in the encrypted cash drawer session, included in persisted snapshots, and recorded in the audit event. If the operator types an ordinary label without selecting/matching a directory account, the session retains the label and the UI marks it unverified.

The transaction engine rejects a supplied account ID when the account is absent, disabled, or its saved display name does not match the supplied label. Rejected close attempts leave the drawer session open. No account credentials or PIN material are exposed in the shift screen.

SQLite schema version 32 adds nullable `cash_drawer_sessions.handover_to_staff_id` through an additive migration from v31. Existing rows remain intact and their prior labels remain unverified. The new field is included in encrypted snapshot reads/writes.

## Verification

- Kotlin JVM tests: **94/94 passed** (`testDebugUnitTest`).
- Android API 37 instrumentation: **1/1 passed**, `staffShiftHandoverCanBindEnabledReceivingAccountAndSurvivesReopen`; verifies mismatch rejection, no premature close, successful account binding, encrypted DB reopen persistence, and audit inclusion.
- Debug APK: passed.
- Instrumentation APK: passed.
- Android lint: passed, **0 errors**; existing warnings remain.
- The initial instrumentation invocation failed because PowerShell split a Gradle `-Pandroid.test...` argument at the ampersand-containing working path. The same focused invocation with a quoted property argument passed. This was command quoting, not an application test failure.
- Node prototype and backend suites were not rerun; this change is Android-only. Full connected instrumentation was not run.

## Remaining limits

The selected ID establishes a local directory association only. It does not prove the recipient was physically present, accepted the cash, or authenticated at handover time. There is no dual-party acknowledgement, cloud identity, multiple register workflow, complete shift action attribution, attendance, or production authorization review. Legacy/manual handovers must continue to be treated as unverified. Physical-device, shop-floor, independent security, and deployment qualification remain separate gates.

## Files changed

- `android/app/src/main/java/com/chaiandtapri/counter/CounterPersistence.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/CounterModel.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/SqliteCounterPersistence.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/StaffShiftsActivity.kt`
- `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt`
- `PROJECT_PLAN.md`
- `specs/MASTER-IMPLEMENTATION-TRACKER.md`
