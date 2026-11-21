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