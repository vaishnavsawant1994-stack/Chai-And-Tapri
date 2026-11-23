# CAT-052 Owner Session Role Enforcement — 24 November 2026

## Scope

The Android account client previously discarded the authenticated membership role returned by the login endpoint. The owner-only Cloud Sync screen could therefore save a server cashier session as if it were an owner session bound to the local owner actor.

## Implemented

- `SyncSession` retains the server-issued user UUID and membership role. Registration records the server user ID and the registration contract's owner role; login parses and validates the server's role instead of discarding it.
- The encrypted Android Keystore session vault persists the user ID and role inside its authenticated ciphertext. Legacy vault payloads without these fields remain readable, but the sync client refuses to use them for owner uploads until a new owner sign-in binds an authenticated identity.
- Cloud Sync account creation and sign-in require an active local owner, verify the returned cloud role is `owner`, and ensure the local owner session did not change while the network request was in progress.
- Manual sync and WorkManager require a cloud user ID, server role `owner`, and local actor `local-owner`. Background work records `owner_identity_required` and stops before network access for a cashier/manager session bound as owner.
- Unknown server roles, malformed account IDs, and incomplete account identity pairs fail closed.

## Verification

- Kotlin JVM: **80/80 passed** (includes local/cloud owner-binding and upload-policy cases).
- API 37 emulator focused instrumentation: `SyncAccountClientTest` **6/6**, `SyncSessionVaultTest` **8/8**, `SyncBackgroundWorkTest` **4/4**, and `SyncOutboxActorAttributionTest` **1/1**; all four Gradle invocations passed.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: **PASS**; both APK artifacts built; lint **0 errors / 181 warnings**.
- Backend and prototype test sources were unchanged by this Android-only slice. Their latest observed results in this continuation remain prototype **52/52** and backend **28 passed / 5 PostgreSQL-dependent skipped / 0 failed**; PostgreSQL integration was not run here.
- Current focused instrumentation result XML is generated under `android/app/build/outputs/androidTest-results/connected/debug/` and is overwritten by each filtered run. The final retained run is the sync worker class; the successful Gradle invocations also establish the account-client, vault, and actor-attribution results.

## Remaining gaps

Android staff invitation acceptance, local staff PIN-to-cloud-account linking, per-staff encrypted session namespaces, and staff-session outbox delivery are still absent. Staff and unlinked legacy events stay blocked at the global outbox head; they are not uploaded under the owner token. These changes do not qualify complete backend synchronization, full Android regression, physical devices, browser visual review, independent security review, field use, or production readiness.
