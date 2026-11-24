# CAT-052 staff invitation and actor-scoped sync E2E

**Date:** 24 November 2026  
**Result:** Focused Android emulator and isolated PostgreSQL integration passed; production sync remains unqualified.

## Scenario and evidence

Used a newly initialized PostgreSQL 17 cluster under the ignored `test-evidence/2026-11-24/cat052-staff-link/pgdata` directory, migrations 001–004, and a disposable non-superuser login granted the `chai_tapri_api` role. The backend ran only on loopback HTTPS with the repository's test-only certificate and `adb reverse`; the pre-existing Windows PostgreSQL service was not used.

The new `LiveBackendSyncE2ETest.invitedCashierUsesAndroidAccountClientAndUploadsOnlyTheirAttributedSale` test performed these steps with synthetic identities and records:

1. Registered an owner using the Android `SyncAccountClient`.
2. Created a cashier invitation through the authenticated owner API.
3. Accepted the one-time invitation using the Android account client and asserted the returned business/shop identity and cashier role.
4. Checked the local staff role-binding policy, including denial of a manager-role session for a cashier.
5. Stored the bound session in the actor-specific encrypted Android session vault.
6. Sent setup stock as owner, recorded a cash sale as the local cashier, and synced through `ActorScopedSyncJournal` with the cashier bearer.
7. Verified server owner summary: two accepted events, one order, and 1,500 paise declared cash; then revoked the staff session and cleared the encrypted vault.

Results:

- Backend PostgreSQL suite: **33 passed, 0 failed, 0 skipped** using the restricted runtime login and separate local administrator test connection.
- Focused API 37 Android instrumentation: **1 passed, 0 failed, 0 skipped**.
- Debug APK, Android-test APK, and Kotlin unit-test Gradle tasks: **BUILD SUCCESSFUL**.
- JUnit: `../test-evidence/2026-11-24/cat052-staff-link/android-staff-link-e2e.xml` (local ignored evidence).
- Backend test log: `../test-evidence/2026-11-24/cat052-staff-link/backend-postgres-suite.log` (local ignored evidence).
- Post-test synthetic cluster inspection: 2 shops, 2 invitations, 4 memberships, 4 accepted sync events, 0 conflicts. This includes one earlier failed test attempt's synthetic owner/invitation; no real data was used.

The first focused run failed at a test assertion that treated the network client's successful logout as if it also deleted the local encrypted vault. The actual app disconnect workflow performs both operations. The test was corrected to assert server logout and explicit vault clear separately, then passed. This was a test expectation issue, not a code change to production logout behavior.

## Scope limits

The first scenario above is an Android client/service integration test, rather than a UI-driven test of `StaffCloudAccessActivity`; the follow-up section below records the separate UI run. Neither scenario demonstrates online owner invitation creation in the Android owner UI, email delivery/verification, offline identity authentication, complete staff permission matrices, authoritative server-side accounting, production TLS/secrets/operations, multi-device conflict resolution, or production readiness. Existing manual browser review, independent security review, physical-device/field, and deployment gates remain open. The isolated PostgreSQL cluster was stopped and the ADB reverse mapping removed after inspection.

## Follow-up: actual staff cloud-access screen - 24 November 2026

The actual `StaffCloudAccessActivity` was then exercised on the API 37 emulator against the same isolated synthetic backend. The test opened an owner-created invitation, entered the URL/secret/name/password in the rendered screen, tapped **Accept invitation and link this staff ID**, verified the encrypted staff-scoped session, tapped **Sync this staff member's queued actions**, confirmed the cashier's only attributed sale reached the owner summary, then tapped **Disconnect linked cloud session** and verified server revocation plus local vault removal. Result: **1/1 passed**, 0 failures/skips. JUnit: `../test-evidence/2026-11-24/cat052-staff-link/android-staff-access-ui-e2e.xml`.

The UI initially synced zero events because the screen opened the production default database while the test used its isolated SQLCipher database. To avoid clearing or touching app data, the activity now supports a strict, name-restricted database override only when the installed app is debuggable; the instrumentation test passes its synthetic database and key namespace. Release/non-debuggable builds ignore this override and continue using the default application database. The first two UI attempts and one debug-build compile correction are preserved only as test-development history; the final focused run is green. The earlier direct account-client E2E remains an independent passing test. PostgreSQL suite result for this checkpoint remains 33/33 from the preceding run; it was not rerun after the Android-only UI hook change. The isolated database and HTTPS process were stopped and the ADB reverse mapping removed.

The focused UI and client E2Es were followed by the broad current-source regression recorded below. Online invitation issuance in an owner UI, email delivery/verification, complete staff permission coverage, authoritative full accounting, production TLS/deployment, independent security review, and physical/field qualification remain open.

## Broad Android regression closeout - 24 November 2026

The first broad current-source run completed **132/133** and isolated one failing `HttpsSyncTransportIntegrationTest` fake registration response: the fixture still used a non-UUID placeholder user ID and omitted `role`, while the account client now correctly requires authenticated UUID identity and role. Updated the synthetic mock to return a canonical UUID and `owner`; the affected focused TLS integration test then passed **1/1**. Reran the complete default API 37 instrumentation suite: **133/133 passed, 0 failures, 0 errors, 0 skips**, `BUILD SUCCESSFUL` in 31m36s. Final JUnit: `../test-evidence/2026-11-24/cat052-staff-link/android-full-regression-133-of-133.xml`; the initial 132/133 run is retained as `../test-evidence/2026-11-24/cat052-staff-link/android-full-regression-first-run-132-of-133.xml`.

Other current-source checks: Kotlin JVM **81/81**, prototype Node **52/52**, isolated backend PostgreSQL **33/33** (from the same CAT-052 E2E checkpoint), APK and instrumentation APK builds passed, lint **0 errors / 182 warnings**. Separate focused live backend runs passed 1/1 for the Android account client and 1/1 for the actual Staff Cloud Access screen. The broad default runner excludes opt-in live backend classes, so those two live tests are separate evidence, not counted in 133. The isolated backend cluster, HTTPS process and ADB reverse mapping were stopped. This still does not qualify production sync/security, owner-side invitation UI, email verification, complete role policy, cross-device recovery, physical Bluetooth, field trials, CAT-046 browser review, or deployment.
