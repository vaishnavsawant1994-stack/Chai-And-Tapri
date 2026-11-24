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

This is an Android client/service integration test, **not a UI-driven test of `StaffCloudAccessActivity`**. It does not demonstrate online owner invitation creation in the Android owner UI, email delivery/verification, offline identity authentication, complete staff permission matrices, authoritative server-side accounting, production TLS/secrets/operations, multi-device conflict resolution, or production readiness. Existing manual browser review, independent security review, physical-device/field, and deployment gates remain open. The isolated PostgreSQL cluster was stopped and the ADB reverse mapping removed after inspection.
