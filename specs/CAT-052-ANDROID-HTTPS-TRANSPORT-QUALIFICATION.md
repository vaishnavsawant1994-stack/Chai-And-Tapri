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
