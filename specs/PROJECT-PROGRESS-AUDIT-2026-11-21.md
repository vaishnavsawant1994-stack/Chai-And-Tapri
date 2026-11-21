# Chai And Tapri OS — Project Progress Audit

**Current source verification: 21 November 2026**
This audit supersedes previous test totals only for the checks explicitly rerun below. Product-wide scope remains the original six-wave execution plan; this is not a completion report.

## Current verification

| Suite/check | Result | Scope |
| --- | ---: | --- |
| Android API 37 instrumentation | **96/96 passed** | Full connected suite with the opt-in live-backend test class excluded; 0 failures, errors, or skips |
| Kotlin JVM | **60/60 passed** | `testDebugUnitTest` |
| Backend Node, no DB configured | **19 passed, 0 failed, 4 skipped** | PostgreSQL-dependent cases skipped |
| Backend PostgreSQL suite | **23/23 passed previously** | Isolated PostgreSQL 17 cluster and restricted runtime login, 20 November; not rerun 21 November |
| APKs | **Passed** | `assembleDebug`, `assembleDebugAndroidTest` |
| Android lint | **Passed with warnings** | 0 errors, 161 warnings |

Current emulator XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` (ignored build output). JVM XML: `android/app/build/test-results/testDebugUnitTest/`. Lint report: `android/app/build/reports/lint-results-debug.html`.

## Work completed in this continuation

- Changed the opt-in Android/backend E2E test to use a real configured-product `CounterModel`, a named SQLCipher database, and `PersistenceSyncJournal` instead of a fabricated in-memory journal.
- Its intended sequence sends an opening-stock event, simulates response loss after the backend receives the cash sale, closes and reopens SQLCipher, verifies the pending sale and cursor, retries, then checks duplicate acknowledgment and owner projection.
- Fixed an E2E fixture mistake where a custom product was sold using the demo catalogue, and replaced a weak substring assertion that had accepted an error containing “No sale recorded.”
- Added validated per-test namespaces for encrypted sync-session vault preferences and Keystore aliases. The default production namespace and API remain unchanged.
- Added and passed an API 37 SQLCipher outbox retry test using a deterministic in-process server double. It accepts the opening-stock event, commits the cash sale and drops its acknowledgment, closes and reopens SQLCipher, then retries as a duplicate. The local cursor advances once, both outbox events are acknowledged, and the server double records one accepted command per event.
- Verified the existing encrypted outbox/restart test and new namespace-isolation test on the API 37 emulator. The latest full connected suite includes these tests.

## CAT-052 qualification boundary

The corrected SQLCipher-outbox-to-live-backend loss/retry scenario compiled but **was not executed** on 21 November because the isolated PostgreSQL test server was not active. The new local retry test validates outbox/runner recovery against a deterministic server double, not the backend implementation. The earlier 20 November Android/backend success used an in-memory journal and remains valid only for that earlier source. A fresh isolated database/server provisioning attempt was denied by the execution policy, so no alternate local database or production service was used.

The database-independent backend suite ran 19 tests successfully; its four PostgreSQL cases were skipped. A separate earlier 23/23 isolated PostgreSQL test result is preserved in `test-evidence/2026-11-20/android-sync-e2e/backend-tests.log`.

## Six-wave product position

| Wave | Position |
| --- | --- |
| 1 — Recovery and security | Encrypted local storage and SAF recovery flows exist; CAT-051B remains partial. Independent security review, additional destructive/device recovery tests, and production security qualification remain open. |
| 2 — Android shopkeeper workflows | Connected business workflows have expanded, but the complete CT-01–CT-32 surface and full-day shop operation are not complete or qualified. |
| 3 — Authenticated synchronization | Registration, sessions, HTTPS transport, an encrypted outbox, PostgreSQL event journal, and limited owner projections exist. New durable Android retry against the backend remains unqualified; the backend is not a complete authoritative financial ledger. |
| 4 — Owner dashboard | Several overview, activity, inventory, product-performance, cashflow, and profit-readiness slices exist; the complete OW-01–OW-20 dashboard and visual/browser qualification remain open. |
| 5 — Selling modes | Quick sale and reviewed speech/typed workflows exist. Continuous Bluetooth voice, physical headset qualification, barcode/QR selling, and real noisy-counter trials remain open. |
| 6 — Release and operations | Production deployment, multi-shop rollout, field validation, independent reviews, and release readiness remain open. |

The project is still a prototype. No physical Bluetooth, field, independent security, production deployment, or complete product qualification is claimed. The next executable backend qualification is to rerun `LiveBackendSyncE2ETest` with a newly initialized isolated PostgreSQL cluster, prove the actual SQLCipher outbox retry across close/reopen, and preserve fresh test evidence. Continue implementing end-to-end shopkeeper workflows in parallel under the six-wave plan.

## Current-source addendum � CAT-051B barrier concurrency � 21 November 2026

A deterministic JVM test now exercises the real maintenance barrier with a concurrent in-flight commit: maintenance blocks until the commit permit closes, new commits are rejected while maintenance is active, and normal commit access resumes after release. After correcting a test-harness double-close, Kotlin JVM tests passed 61/61. The backend database-independent suite passed 19 tests with 0 failures and skipped 4 PostgreSQL-dependent cases. This change does not add new Android recovery UI coverage and does not close CAT-051B. Physical key invalidation, actual power loss/storage exhaustion, second-device/person recovery, independent security review, and production qualification remain open.


## Current-source addendum - CT-06 scanner input - 21 November 2026

Quick Sale now supports exact product-code input from a paired keyboard-wedge barcode/QR reader, using owner-configured local catalogue codes. A valid scan adds one item to the current cart only; normal checkout still commits the transaction. Unknown/ambiguous codes and ineligible products do not alter the cart or business ledger. The additive SQLCipher schema v26->v27 migration retains previous order and stock records. The scanner-code field is device-local and camera scanning is not implemented. API 37 full regression passed 98/98 (0 failures/errors/skips), Kotlin JVM passed 63/63, APK builds passed, and lint reported 0 errors / 161 warnings. Backend Node passed 19 tests and skipped 4 PostgreSQL-dependent tests. Physical scanner and shop trials remain open.

## Superseding current-source addendum - 21 November 2026 - CT-06 camera scanner

The scanner slice now also includes explicit Quick Sale camera scanning using CameraX with bundled on-device ML Kit, a scanner Activity, and optional camera permission with typed/keyboard-wedge fallback. Camera output is a product code only and is resolved by the existing exact catalogue matcher; scanning does not post a sale or interpret UPI/payment QR codes. Focused API 37 tests passed 1/1 each for scanner result contract, optional camera permission/fallback screen, and scanner-code selection/cart-only behavior. Node passed 52/52; Kotlin JVM passed 63/63; debug and instrumentation APK build tasks passed; lint passed with 0 errors and 171 warnings. The full instrumentation suite was attempted but interrupted before a report, so no full-suite claim is made here. This supersedes the prior statement that camera scanning was not implemented, but it does not qualify live camera decode, physical scanners, or field use. See `specs/CT-06-CAMERA-BARCODE-SCANNER-REPORT.md`. CAT-051B recovery failure testing, complete Android MVP, six-wave product scope, independent security review, production sync/deployment, and real Bluetooth/shop trials remain open.
## Superseding emulator regression result - 21 November 2026 - camera-scanner source

A fresh API 37 reboot was followed by `connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.notClass=com.chaiandtapri.counter.LiveBackendSyncE2ETest`. The runner executed 99 tests (the opt-in backend class excluded): 77 passed, 22 failed, 0 errors, 0 skipped. The first failing test was the SAF backup UI test; its accessibility tree showed `Pixel Launcher isn't responding | Close app | Wait`. The subsequent UI tests were obstructed by the same launcher ANR. This is a failed broad emulator regression, not a green qualification. The three focused camera tests still pass individually; their scanner result, permission/fallback, and cart-only behavior remain focused/emulator-tested. Node 52/52, Kotlin JVM 63/63, APK builds PASS, lint 0 errors/171 warnings. Root failure evidence is in the connected HTML/JUnit reports and `logcat-com.chaiandtapri.counter.SqliteCounterPersistenceTest-ownerBackupMenuCreatesEncryptedSyntheticBackupThroughSafUi.txt` under ignored build output. Next: diagnose the low-memory AVD/launcher instability and rerun, while keeping the SAF test independently qualified. CAT-051B remains partial; no physical camera, cross-device recovery, independent security or production qualification is claimed.
## Superseding API 37 emulator regression - 21 November 2026 - four-core runtime

After the prior full-suite run hit a Pixel Launcher TouchInteractionService startup ANR, I relaunched the unchanged AVD data with the emulator runtime option -cores 4 -no-snapshot (the AVD config itself was restored unchanged). The focused SAF backup UI test passed 1/1, then the full instrumentation run excluding only the opt-in live-PostgreSQL class passed 99/99, 0 failures, 0 errors, 0 skipped in about 7 minutes. This supersedes the prior 99-test/22-failure environment run. JUnit: android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml; per-test logs in the same ignored output directory. The local PostgreSQL E2E class was excluded because no isolated backend endpoint was active; it remains an independent gate. A single green emulator run does not close physical-device, independent security, production, or field qualification.
## Superseding CAT-052 live SQLCipher outbox retry - 21 November 2026

The corrected `LiveBackendSyncE2ETest.realBackendStoresAndroidEventAndMakesLostAcknowledgmentRetryIdempotent` passed 1/1 on API 37 against a fresh isolated PostgreSQL 17 cluster. The test used the restricted `chai_tapri_api` runtime login over a loopback HTTPS test server exposed through `adb reverse`; the existing Windows PostgreSQL service was not used. Android wrote opening stock and a configured cash sale through `CounterModel` and the SQLCipher-backed `PersistenceSyncJournal`. After the backend accepted the sale, the test injected response loss, closed and reopened the encrypted database, verified the sale remained pending with its cursor unchanged and stock balance 9, then retried. The backend returned a duplicate acknowledgment, the cursor advanced once, and the local outbox cleared. Owner API checks returned two accepted events, one order, INR 2,500 staff-declared cash, estimated cost, and null gross/operating profit. PostgreSQL inspection showed one synthetic shop, two events, zero conflicts, and two revisions. This was a store close/reopen test, not Android process-death recovery.

The independent isolated PostgreSQL backend suite passed 23/23 with no skips. Full API 37 instrumentation excluding the opt-in live-backend class passed 99/99 with zero failures, errors, or skips; the live class then passed separately 1/1. Existing same-source verification: Node 52/52, Kotlin JVM 63/63, APK builds passed, lint 0 errors / 171 warnings. The temporary HTTPS process and isolated PostgreSQL cluster were stopped and the ADB reverse mapping removed. The cluster data is retained in the temporary directory for local synthetic evidence; no public endpoint or real shop data was used.

This qualifies the SQLCipher-outbox retry across database close/reopen against the isolated backend. Still open: actual app process death/restart during sync, full authoritative server accounting, production TLS/deployment, multi-device enrollment/conflict handling, independent security review, and field/device qualification. CAT-052 remains prototype-qualified; this does not qualify production synchronization.
## Current-source restore recovery process-death drill - 21 November 2026

Added a debug-only, non-exported recovery harness service in a separate Android process. The API 37 instrumentation test creates a synthetic order in SQLCipher, binds the harness, moves the active encrypted database into the preserved restore slot, writes the durable pending-restore journal, and syncs a checkpoint marker. The test unbinds and confirms the helper process exits. A newly started helper process then opens the normal `SqliteCounterPersistence` path, which performs startup recovery from the journal. It reports the recovered order ID and paise total, original audit event, cleared pending marker, and consumed previous-store slot; the instrumentation process then independently reopens the SQLCipher database and checks the same sale and audit record.

Focused test passed 1/1. The complete API 37 instrumentation suite excluding only `LiveBackendSyncE2ETest` passed 100/100, 0 failures, 0 errors, 0 skips. Kotlin JVM passed 63/63; Node passed 52/52; debug and Android-test APK assembly passed; lint reported 0 errors / 171 warnings. Android evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`. The backend suite run without a configured database passed 19 tests and skipped 4 PostgreSQL-dependent integration tests; the separate isolated PostgreSQL 23/23 and live SQLCipher outbox retry 1/1 evidence remains as documented in the CAT-052 report.

This demonstrates an abrupt helper-process death after the durable restore checkpoint and journal recovery in a fresh process using the production persistence startup path. It does not kill the foreground application process, cut device power, simulate filesystem/power-loss guarantees, exhaust physical storage, invalidate hardware Keystore keys, or establish second-device/person recovery. Those gates, independent security review, and production qualification remain open; CAT-051B remains partial.