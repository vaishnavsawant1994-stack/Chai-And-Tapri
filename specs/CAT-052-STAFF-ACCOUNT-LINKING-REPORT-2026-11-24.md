# CAT-052 Android Staff Account Linking

Date: 24 November 2026

## Implemented

- Added a staff-only Android cloud access screen for a user who is already authenticated through the local staff PIN flow.
- Added the exact `POST /v1/staff/accept-invitation` client contract. The one-time invite secret and password are not persisted by the screen; the input widgets are cleared before the network request.
- Accepted server-issued manager/cashier membership only. The server role must equal the active local staff role before the session is stored. Local identity/role is rechecked after asynchronous authentication.
- Stores encrypted cloud sessions in a separate Android Keystore-backed vault per local staff ID; non-secret shop/device identity is namespaced separately to support later login.
- Added manual staff-scoped sync and session revocation. Manual upload checks the active actor, local role, server role, write barrier, sync lock, and actor-scoped outbox journal.
- Generalized background delivery to inspect the global pending outbox head and select only that actor's matching encrypted session. It preserves global event order and never skips events belonging to another operator. Missing operator sessions and role/actor mismatch fail closed with distinct status.
- Added the staff Cloud Access entry to the authenticated staff counter and registered the activity as non-exported.

## Verification performed

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: **BUILD SUCCESSFUL**.
- Kotlin JVM suite: **80 passed, 0 failed** (confirmed from the XML report).
- Android lint: **0 errors, 182 warnings**. Warnings are retained and not treated as proof of clean static analysis.
- Focused Android emulator tests passed individually for `SyncAccountClientTest` (8 tests), `SyncSessionVaultTest` (9 tests), `SyncBackgroundWorkTest` (5 tests), and `SyncOutboxActorAttributionTest` (1 test): **23 passed, 0 failed** across the focused runs.
- `git diff --check`: passed.
- The emulator used the isolated `Tapri_Recovery_Isolated(AVD)` API 37 image.
- Prototype JavaScript suite: **52/52 passed**.
- Backend suite without an isolated PostgreSQL test database: **28 passed, 0 failed, 5 skipped** (the skipped tests require PostgreSQL/backend integration setup).
- A complete unfiltered `connectedDebugAndroidTest` run was attempted. It remained active for several minutes without emitting results; the test process then disappeared while Gradle stayed pending. The run was interrupted, so the full Android suite is **incomplete / not qualified**. Earlier broad regressions have known UI and environment-dependent failures; this result must not be read as a pass.

## Not qualified

- No owner-issued live invite was created and accepted through the Android screen against a running backend in this slice. Client behavior is covered with synthetic transport fixtures.
- A full unfiltered Android instrumentation regression did not complete. One initial focused run exposed a JUnit method return-type issue in the new worker tests; it was corrected and the focused worker suite then passed.
- This does not establish production authorization or sync readiness. Backend policy remains authoritative, offline local business commands are not blocked by cloud roles, and the server still has documented development deployment gaps.
- CAT-051B clean-install recovery, visual review, independent security review, real Bluetooth microphone/headset testing and shop trials remain separate open gates.

## Changed areas

`SyncAccountClient`, `SyncSessionVault`, `SyncBackgroundWork`, `StaffCloudAccessActivity`, `MainActivity`, the Android manifest, and focused Android instrumentation tests. `PROJECT_PLAN.md`, the current progress audit, the master tracker and `backend/README.md` were updated.

## Next executable step

Run the full Android instrumentation suite and exercise the invitation/link/login/manual-sync/revoke path against a disposable local PostgreSQL backend from the emulator using synthetic accounts. Then implement local permission enforcement for manager/cashier business commands and test denied actions plus queued outbox ordering under role changes.
