# CAT-052 Android Sync Runner — Implementation Report

Date: 30 October 2026

## Implemented slice

Added `SyncRunner` and `HttpsSyncHttpTransport` for the current authenticated `POST /v1/sync/events` contract. The runner reads one sendable event at a time from a `SyncJournal`, maps it using `SyncEnvelopeMapper`, uses the durable cursor as the base revision, validates that a response refers to the exact pending event, and only then records accepted/duplicate/conflict outcomes. It stops on the first conflict. HTTP failures, malformed/misattributed responses, or failed local acknowledgements leave the event pending so an idempotent retry can reconcile with the server. The production transport requires HTTPS, uses platform certificate/hostname validation, rejects redirects and embedded URL credentials, applies connect/read timeouts, and bounds response size. Added Android `INTERNET` permission; cleartext traffic remains disabled.

The sync runner receives an already-authenticated bearer token as an argument; it does not retain or log the token. Its persistence adapter connects to the existing encrypted outbox and acknowledgement journal.

Added `SyncSessionVault` for app/device-bound credential persistence. It requires HTTPS endpoints and UUID business/shop/device identifiers, encrypts the session with AES-GCM using an Android Keystore key, stores only the ciphertext envelope in private preferences, and preserves the ciphertext if the key is unavailable or authentication fails. Explicit clear removes the envelope and key. Added `SyncAccountClient` for the existing HTTPS registration/login/logout API and an owner-gated `CloudSyncActivity` exposed from Shop Home. The screen supports server account creation, re-authentication on a previously registered device, manual sync, and server-revoked disconnect. Registration/login use server-issued IDs/tokens and persist the session through the vault; account passwords are cleared from the form after submission and are not persisted. A separate private preference retains only non-secret business/shop/device IDs to support re-login on this already registered device.

## Verification

- Focused API 37 instrumentation: 4/4 sync-runner tests passed for ordered accepted events, conflict stop, HTTP failure retry safety, mismatched event IDs, and HTTP URL rejection; 3/3 session-vault tests passed for encrypted persistence, endpoint/identity rejection, explicit clear, and missing-key preservation.
- Kotlin JVM: 35/35 passed.
- APK and instrumentation APK build tasks passed.
- Node parser/business suite: 49/49 passed.
- Backend test command: 5 passed, 4 integration cases skipped because the isolated PostgreSQL integration environment was not started for this run.
- The first full Android attempt on this source set failed at 48/53 while the emulator launcher was ANR. After restarting the launcher and retrying, the complete suite passed 56/56. The successful full-suite XML is preserved at `test-evidence/cat052-session-vault/full-instrumentation.xml`; focused class evidence is `vault-tests.xml` (3/3) and `sync-runner-tests.xml` (4/4) in the same directory.
- Current-source Kotlin JVM: 35/35. Node parser/business suite: 49/49. Backend payload-contract suite: 5/5. Debug APK, instrumentation APK, and lint tasks passed; lint reported 0 errors / 118 warnings. The complete Android run took 6m31s on the API 37 emulator.
- Superseding current-source run after adding account client and screen: Android API 37 instrumentation 62/62; Kotlin JVM 35/35; prototype Node tests 49/49; backend tests 5 passed and 4 database integration tests skipped because PostgreSQL was not started; debug and Android-test APK builds pass; lint 0 errors / 128 warnings. XML is `test-evidence/cat052-session-vault/full-instrumentation-with-account-ui.xml`.
- The latest 62-test emulator run completed in 7m28s with 0 failures, 0 errors, and 0 skips.

## Still required for actual end-to-end sync

The screen/client have not been tested against a running trusted HTTPS backend. The backend still journals events rather than applying them to authoritative financial projections. Manual sync uses a synchronous worker thread with bounded request timeouts, but there is no WorkManager scheduling, durable retry UI, conflict-resolution workflow, automatic recovery from expired sessions, or multi-device enrollment for an existing shop. This is not end-to-end sync qualification and must not be presented as such. The Android app screen creates a new server business/shop on registration; joining an already existing shop from a fresh device needs a secure device-enrollment flow that the server does not yet provide.

## Session vault follow-up — 30 October 2026

The app/device-bound session vault is now implemented and API 37 instrumentation-tested. The emulator tests verified vault recreation and decrypt, HTTPS/UUID validation before persistence, explicit local clear, and fail-closed handling of a missing Keystore key without deleting the encrypted envelope. Session vault tests passed 3/3; sync runner tests passed 4/4. The tests use a synthetic token and do not connect to any remote service. The subsequent full API 37 regression run passed 56/56 after restarting the emulator launcher; evidence is preserved as noted above.

## CAT-052 WorkManager continuation — 30 October 2026

Added durable one-time and owner-enabled six-hour background sync scheduling through WorkManager 2.12.0. Jobs require connected network, use exponential backoff, carry no credential or business payload in WorkManager data, and process up to five eligible events per run before queueing a continuation. A process lock serializes manual sync, account/session replacement, logout and the background worker. The worker observes the backup/recovery write barrier, uses the existing Keystore session and encrypted allow-listed outbox, and records only non-sensitive status. Cloud Sync now offers queue, periodic-enable and pause actions; logout cancels queued jobs and waits for in-flight sync before revoking/clearing credentials. Session decryption was moved off the activity main thread.

Final-source verification: Node 49/49; Kotlin JVM 35/35; Android worker instrumentation 3/3; debug and instrumentation APK compilation/build PASS; lint 0 errors / 138 warnings. Backend tests 5 passed / 4 skipped because PostgreSQL integration was not running. Full Android instrumentation did not complete: emulator services failed during the 65-case run, and isolated Cloud Sync screen launches hit a Pixel Launcher ANR before the test could launch the activity. Worker runner and Gradle logs are under `test-evidence/cat052-workmanager/`; launcher ANR screenshots and failing UI XML are recorded there or under Android build outputs. This is not a complete Android regression pass and the modified screen remains emulator-unqualified.

CAT-052 is still not end-to-end sync qualified: no live HTTPS request occurred; server financial projections, new-device enrollment, conflict-resolution UI and production server operation are absent. The full app suite must be rerun on a stable emulator. Physical device behavior, field qualification, independent security review and product release readiness remain open.

Superseding lint verification after the final Cloud Sync main-thread change: `:app:lintDebug` passed with 0 errors and 139 warnings. `:app:testDebugUnitTest` also passed after that change; the JVM report remains 35/35. The warning count supersedes the earlier 138-warning line in this continuation.
