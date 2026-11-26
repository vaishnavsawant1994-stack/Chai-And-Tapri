# CAT-052 Android HTTPS sync and catalogue pull E2E — 26 November 2026

## Scope

Qualified one real Android API 37 emulator path against a disposable PostgreSQL 17 backend over HTTPS. The test used only synthetic account/shop/product/order data and the repository's test-only TLS certificate. The database container was isolated on host loopback at port 55446, the HTTPS test backend on loopback port 8793, and ADB forwarded the certificate-valid `localhost:8793` endpoint to the emulator.

## What the drill exercised

- Android registered a synthetic owner account with the local HTTPS backend and persisted the session in the test vault.
- The encrypted SQLite store created a configured product, recorded opening stock, and made a cash sale.
- Android submitted the durable outbox to the PostgreSQL-backed service. A test transport dropped the response after the server committed the sale event.
- The test verified only unacknowledged records remained queued, closed and reopened the SQLCipher database, and retried. Server idempotency acknowledged the committed retry without creating a duplicate order.
- Android fetched `GET /v1/sync/catalogue/{shopId}` through the authenticated HTTPS transport, validated the shop and snapshot, applied it to encrypted SQLite, and verified the confirmed product price remained available.
- The test queried owner projections and verified final revision/event counts, one order, 2,500 paise of declared cash, stock decrement, and cost-readiness behavior.

## Defect corrected

Adding catalogue writes to the shared outbox made the older live test's fixed expected cursor/revision stale. The test now derives expected progress from the actual ordered outbox events and checks the downloaded catalogue as well. Its first run used the emulator's `10.0.2.2` alias, which did not match the test certificate's `localhost` subject alternative name; rerunning through ADB reverse with `https://localhost:8793` preserved normal certificate hostname validation. No application TLS validation was weakened.

## Verification

| Check | Result |
| --- | --- |
| Focused live Android E2E | **1/1 passed**, API 37 emulator, 0 failures/errors/skips |
| Prototype JavaScript | **52/52 passed** |
| Backend Node with restricted runtime login and PostgreSQL 17 | **44/44 passed**, 0 skipped |
| Kotlin JVM | **106/106 passed** |
| Debug APK and instrumentation APK builds | **Passed** |
| Android lint | **Passed**, 0 errors / 201 warnings |

The backend test suite initially failed when run with the database administrator as the application connection; those two RLS failures confirmed the role context matters. It passed 44/44 after using the restricted `chai_tapri_e2e` runtime login and separate administrator connection for test cleanup. The database was not queried against any existing service/container.

## Evidence and cleanup

- Connected instrumentation XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` (the build output is ignored by Git).
- Kotlin JVM XML: `android/app/build/test-results/testDebugUnitTest/` (ignored build output).
- Lint text report: `android/app/build/reports/lint-results-debug.txt` (ignored build output).
- Disposable test database container: `chai-tapri-e2e-20261126`; stopped and removed after verification.
- HTTPS test process stopped and ADB reverse removed after verification.

## Qualification boundary

This qualifies one synthetic emulator-to-local-backend flow and the current catalogue pull path. It does not qualify a deployed backend, Android-to-Android multi-device synchronization, all projection families, conflict resolution, production secrets/identity policy, independent security review, physical devices, Bluetooth audio, noisy-shop operation, or field rollout. The API 37 full default UI suite was not run as part of this focused E2E; the opt-in live E2E passed separately.
