# CAT-052 Android HTTPS transport qualification

Date: 20 November 2026

## Delivered

Added an API 37 instrumentation test that sends requests through the production `HttpsSyncHttpTransport`, `SyncAccountClient`, and `SyncRunner`. A test-only loopback TLS server returns synthetic account and sync acknowledgments. The test performs owner registration and pushes one synthetic, allow-listed outbox event; it checks that only the sync request carries the bearer token and that the runner records the correlated event as accepted at server revision 1.

The test server binds only to the emulator loopback interface. Its self-signed certificate includes `localhost` and `127.0.0.1` subject alternative names. The instrumentation test installs a trust manager containing only that test certificate for the duration of the test and restores the original default socket factory in `finally`. Normal HTTPS hostname verification remains enabled. The test private key is stored only under `android/app/src/androidTest/assets/tls/` and is packaged into the instrumentation APK, never the application APK. It is a disposable test identity and must not be used outside local tests.

## Verification scope

- Focused API 37 instrumentation: `HttpsSyncTransportIntegrationTest` — 1/1 passed.
- Full API 37 instrumentation regression: 94/94 passed, 0 failures, 0 errors, 0 skipped (16m20s).
- Prototype Node suite: 52/52 passed.
- Kotlin JVM suite: 60/60 passed.
- `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest`: passed; `lintDebug`: passed with no errors reported.
- JUnit evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` (generated, ignored build output).
- This verifies the Android HTTP/TLS client, registration serialization, bearer-header behavior, event envelope delivery and acknowledgment handling against a local synthetic HTTPS server.
- Existing backend/PostgreSQL tests verify the backend separately. This test does **not** connect Android to the backend service or PostgreSQL, and does not qualify end-to-end server persistence, tenant authorization, production certificate provisioning, background retry, or multi-device conflict handling.
- Android product data and the configured production trust policy are not modified.

## Remaining CAT-052 gates

1. Wire the Android client to the real backend over a locally trusted HTTPS endpoint and an isolated PostgreSQL database.
2. Verify that server-side authorization and stored projections match the Android-originated event.
3. Demonstrate offline queuing and retry after a response interruption, then prove server idempotency and durable local acknowledgment.
4. Complete multi-device enrollment, conflict review, security review, and production deployment readiness separately.

The project remains a prototype and is not qualified for production sync.
