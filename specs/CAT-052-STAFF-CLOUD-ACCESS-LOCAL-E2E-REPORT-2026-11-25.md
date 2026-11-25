# CAT-052 Android staff cloud-access UI — isolated PostgreSQL retest

**Date:** 25 November 2026  
**Result:** Focused synthetic local integration passed; production synchronization remains unqualified.

## Environment

- API 37 emulator: `emulator-5554` (`Tapri_Recovery_Isolated`).
- Fresh PostgreSQL 17 cluster and UTF-8 test database created under ignored `test-evidence/2026-11-25/pg-wave3-isolated`.
- Migrations 001–004 applied to the disposable database. Runtime requests used a non-superuser login granted the repository's `chai_tapri_api` role; test cleanup used the local test administrator connection.
- HTTPS test server bound to `127.0.0.1` and used the repository's test-only certificate. Emulator access used a temporary ADB reverse mapping. No production service, unrelated PostgreSQL database, or real business data was used.

## Verification

The focused instrumentation test `LiveBackendSyncE2ETest.staffCloudAccessUiAcceptsInviteSyncsCashierSaleAndDisconnects` passed **1/1** (0 failures, errors, or skips). It used the rendered Android staff-access screen to accept a synthetic owner-issued cashier invitation, bind the cloud cashier role to a local actor, sync that actor's staff-attributed cash sale, verify the accepted sale in the owner overview, and disconnect. Assertions verified the encrypted actor-scoped session, cleared outbox, accepted order and declared cash total, server logout, and local session removal.

The complete backend PostgreSQL suite passed **35/35, 0 failures, 0 skips**. This included authenticated tenant journal behavior, idempotency/conflicts, login lockout, staff invitation/member removal, HTTP sync, and owner projections. Logs and JUnit are in the ignored local evidence directory:

- `test-evidence/2026-11-25/backend-postgres-suite-35-of-35.log`
- `test-evidence/2026-11-25/cat052-live-sync/android-staff-cloud-access-ui-e2e.xml`

The focused Gradle command rebuilt debug and instrumentation APKs and completed `connectedDebugAndroidTest` successfully. The current one-test JUnit XML records 1 test, 0 failures, 0 errors, 0 skipped.

## Limits and next work

This is a single synthetic Android UI-to-local-test-backend path. It does not establish production synchronization, end-to-end owner invitation administration on Android, email verification, all staff permissions, authoritative server accounting, multi-device conflict resolution, production TLS/secrets/operations, physical-device behavior, field qualification, independent security review, browser visual review, or deployment readiness. The full default Android regression was not rerun in this continuation; its latest prior result remains 138/138 as separately documented. The temporary HTTPS server, database cluster, and ADB reverse mapping were stopped after the test.
