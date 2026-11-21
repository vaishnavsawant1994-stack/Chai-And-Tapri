# CAT-052 Android HTTPS and isolated PostgreSQL sync qualification

Date: 20 November 2026

## Delivered

Added API 37 instrumentation coverage using the production `HttpsSyncHttpTransport`, `SyncAccountClient`, and `SyncRunner`. One test exercises the Android HTTP/TLS contract against an in-emulator synthetic server. A second opt-in drill uses the actual backend HTTP routes over HTTPS, backed by an isolated PostgreSQL 17 cluster and a restricted `chai_tapri_api` runtime login.

For the backend drill, the HTTPS API binds to host loopback and is exposed to the emulator with `adb reverse`. Its self-signed test certificate includes `localhost` and `127.0.0.1` subject alternative names. Test instrumentation trusts only that certificate during the test and restores the original socket factory in `finally`; normal hostname verification remains enabled. The private key exists only as a disposable instrumentation test fixture and must never be used outside local qualification. The application APK and production trust policy are unchanged.

The backend was created from a fresh temporary cluster, migrations `001_core` and `002_command_key_text` were applied, and the service ran as the restricted API role. The existing laptop PostgreSQL service and databases were not used. Both the backend process and temporary PostgreSQL cluster were stopped after qualification.

The live scenario registered a synthetic owner/shop/device, submitted a sale event with a staff-declared cash collection and estimated line cost, then injected response loss after the backend had committed the first request but before the Android sync runner received the response. The Android test journal retained the event. Retrying the same event received the backend's `duplicate` result at revision 1 and then acknowledged it locally. Authenticated owner endpoints reported one accepted event, one order, ₹25.00 cash, one costed sale line, and null gross/operating profit. The test then revoked the session. The backend journal was inspected in PostgreSQL; each independent run registered a separate synthetic shop and persisted only one accepted revision for its retry pair.

## Verification scope

- Focused API 37 instrumentation: `HttpsSyncTransportIntegrationTest` — 1/1 passed.
- Focused live backend API 37 instrumentation: `LiveBackendSyncE2ETest` — 1/1 passed.
- Full API 37 instrumentation regression with the live backend configured: 95/95 passed, 0 failures, 0 errors, 0 skipped (15m03s).
- Prototype Node suite: 52/52 passed.
- Kotlin JVM suite: 60/60 passed.
- Backend PostgreSQL suite: 23/23 passed, 0 skipped, using the same isolated cluster and restricted runtime role.
- `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest`: passed; `lintDebug`: passed with no errors reported.
- JUnit evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` (generated, ignored build output).
- Backend suite log: `test-evidence/2026-11-20/android-sync-e2e/backend-tests.log`; temporary database and backend logs are in the same ignored evidence directory.
- This verifies a synthetic authenticated Android-to-backend/PostgreSQL round trip, a server-committed event retry, and owner API visibility in the isolated test environment. It is not production-server or production-certificate qualification.
- The live retry test uses an in-memory test journal; it does not prove retry after process death using the app's SQLCipher outbox. The server stores the event journal and computes limited owner projections; it is not yet a full authoritative financial ledger.

## Remaining CAT-052 gates

1. Repeat lost-acknowledgment retry using an event persisted in the SQLCipher outbox, including app process death/restart.
2. Add full account-session renewal/device enrollment and an owner conflict-resolution flow before multi-device use.
3. Expand server-side financial projections and reconciliation beyond the current accepted-event journal.
4. Complete independent security review, production TLS/configuration review, and deployment readiness separately.

The project remains a prototype and is not qualified for production sync.

## SQLCipher outbox retry continuation - 21 November 2026

The opt-in live-backend test source now exercises a real `CounterModel`, configured synthetic product, named SQLCipher database and `PersistenceSyncJournal` instead of fabricating an event in an in-memory test journal. Its intended flow is to persist opening stock and a cash sale, send the first event, inject response loss only after the backend receives the sale event, close/reopen the encrypted database, verify that the sale is still pending with the prior cursor retained, retry, and verify the duplicate acknowledgment advances the local cursor exactly once. `SyncSessionVault` now accepts a validated namespace so isolated instrumentation sessions do not replace the app's default owner-session key/envelope; the existing default namespace remains unchanged.

The first execution exposed a faulty test setup: it used the demo catalogue while trying to sell a custom configured product, and a weak substring assertion accepted the rejection message `No sale recorded`. The test now enables configured-catalogue mode and requires the `Quick sale` success prefix. This is a test-harness correction; no production transaction rule was weakened.

Current-source qualification on API 37, 21 November: the complete emulator suite excluding the opt-in live PostgreSQL test class passed **96/96, 0 failures, 0 errors, 0 skips**. It includes the new durable local retry simulation plus SQLite persistence, sync-cursor/outbox, and session-vault tests. `SqliteOutboxRetryTest` stores a configured-product cash sale in SQLCipher, simulates backend idempotent acceptance with response loss, closes/reopens the encrypted database, retries the pending event as a duplicate, and confirms one server-side acceptance with the cursor advanced once. This qualifies local outbox durability and `SyncRunner` retry behavior against a deterministic in-process server double only. Kotlin JVM passed **60/60**. Backend Node tests without a database passed **19/19 executed**, with **4 PostgreSQL-dependent cases skipped**; the isolated PostgreSQL backend suite previously passed 23/23 on 20 November, but was not rerun for this continuation. `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug` succeeded; lint has **0 errors and 161 warnings**. The opt-in live-backend test source compiled, but its corrected SQLCipher-to-live-PostgreSQL response-loss scenario was **not executed** in this continuation because the isolated PostgreSQL server was not running. A fresh isolated cluster could not be provisioned under the available execution policy. The earlier 20 November live-backend result remains evidence only for its then-current in-memory journal implementation, not the new durable retry path.

The generated current-source emulator XML is at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` (ignored build output). The standalone Kotlin result XMLs are under `android/app/build/test-results/testDebugUnitTest/`; lint output is at `android/app/build/reports/lint-results-debug.html`. Backend test output with the database-independent run is generated in the current local command output; the 20 November isolated PostgreSQL log remains `test-evidence/2026-11-20/android-sync-e2e/backend-tests.log`.

CAT-052 remains prototype-qualified only. The next sync acceptance step is to rerun this corrected live test against a newly initialized, isolated PostgreSQL cluster and capture the result. Process-death retry (as distinct from closing/reopening the SQLCipher store), server-authoritative financial projections, multi-device enrollment/conflicts, production TLS/deployment, and independent security review remain open.
