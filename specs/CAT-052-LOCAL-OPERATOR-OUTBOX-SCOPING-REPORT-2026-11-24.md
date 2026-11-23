# CAT-052 Local Operator Outbox Scoping — 24 November 2026

## Problem addressed

The app used one owner bearer token to drain the complete local outbox. When a cashier had created the oldest pending event, the server authenticated the owner token and recorded the owner as submitter. That did not preserve the local audit actor and could assign one operator's offline work to another account.

## Implementation

- Pending `SyncOutboxEvent` values now include the local `actor_id` obtained from the matching `audit_events.command_id`; no database schema migration was needed.
- The Keystore-encrypted `SyncSession` now persists an optional local operator binding. Owner-only Cloud Sync binds a session after the local owner gate and login/registration succeed. A legacy session without a binding is not used for uploads until the owner signs in again.
- Manual and WorkManager sync now use `ActorScopedSyncJournal`. It exposes only contiguous events at the head of the global outbox whose local actor matches the session binding. It does not skip over a different actor's event, because doing so could reorder dependent state deltas.
- If the next event is attributed to another actor or has no actor (including legacy data), the app leaves it pending and reports that it was not uploaded under this account. Actor mismatch is also recorded in background sync status.
- Worker E2E fixtures now explicitly bind synthetic owner sessions, preserving the intended owner-sync test setup.

## Verification

- Kotlin JVM suite: **78 passed, 0 failed**. Three `ActorScopedSyncJournalTest` cases verify a mismatched head is never sent, order is preserved across actor boundaries, and unattributed legacy events remain blocked.
- API 37 emulator instrumentation: **1/1 passed** for `SyncOutboxActorAttributionTest.pendingOutboxEventsCarryTheLocalActorFromTheirAuditRecord`.
- API 37 encrypted session vault instrumentation: **6/6 passed**, including local-actor binding persistence, malformed binding rejection, old unbound session compatibility, and existing key-preservation checks.
- `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` passed; lint reported **0 errors / 179 warnings**.
- Generated local evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (outbox attribution run; last focused vault XML is generated in the same directory but has since been replaced by the outbox run) and `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.ActorScopedSyncJournalTest.xml`.

## Remaining limits

This prevents the current owner session from uploading a queued event created by a different or unknown local operator. It does not yet provide Android invitation acceptance, authenticated staff PIN-to-cloud-account binding, or a staff sync-session UI. Those staff events remain safely queued at the outbox head and can block later events until the matching actor is linked. The local owner ID is a device-local identity, not a cryptographic proof that the local person and cloud account holder are the same human. Cloud authorization still comes from the server's authenticated membership role.

This slice was not tested against the isolated backend endpoint or a full Android instrumentation regression. It does not qualify production synchronization, browser visual review, physical devices, independent security review, or field usage. OW-09/CAT-052 remain partial.

## Post-commit re-verification — 24 November 2026

- `android\\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: **PASS**, Kotlin JVM **78/78**, both APK tasks built, lint **0 errors / 179 warnings**.
- `android\\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chaiandtapri.counter.SyncOutboxActorAttributionTest`: **PASS 1/1** on the API 37 emulator.
- The same focused instrumentation task for `SyncSessionVaultTest`: **PASS 6/6**.
- `node.exe --test *.test.cjs` in `prototype`: **PASS 52/52**.
- `npm.cmd test` in `backend`: **28 passed, 5 PostgreSQL-dependent tests skipped, 0 failed**. PostgreSQL integration was not run in this continuation.
- The exact test XML for the last actor-attribution run is `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`; the session-vault task subsequently overwrote this single generated XML, so its 6/6 result is recorded in the successful Gradle invocation rather than a retained per-run XML.

These reruns validate the scoped change and nearby suites; they are not a full product regression, PostgreSQL integration run, or production qualification.
