# CAT-052 Android WorkManager Sync — Implementation Report

Date: 30 October 2026

## Implemented

- Added AndroidX WorkManager 2.12.0 for durable, OS-managed background work.
- Added a unique one-time sync request and an owner-enabled periodic request (six-hour interval). Both require a connected network, use exponential backoff, and carry no credentials or business payload in WorkManager input/output.
- Added `SyncOutboxWorker`, which loads the Keystore-protected session, uses the existing allow-listed SQLite outbox and HTTPS transport, serializes with manual sync and account/session changes, and persists outcomes through the existing idempotent event journal. It processes at most five events per run and queues a continuation when eligible events remain.
- The worker retries on network/server-transient failures and the backup/recovery write barrier; it reports permanent auth/protocol/conflict outcomes without marking local business events as server-accepted. Non-sensitive worker status is retained locally.
- Added owner-screen controls to queue one background run, enable six-hour scheduling, and pause scheduled work. Logout cancels background work, waits for the sync lock, requests server revocation, then clears the local session only after the existing logout confirmation.
- Moved encrypted session decryption off the Cloud Sync Activity's main thread so slow Keystore access does not block first paint.

## Verification

- Final-source compile, Kotlin JVM test task, debug APK, Android-test APK: PASS (`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`). Kotlin JVM XML reports 35/35.
- Focused API 37 worker instrumentation: 3/3 PASS. It verifies missing-session fail-closed behavior, unique connected-network scheduling and pause, and retry during the business write barrier. Preserved runner evidence: `test-evidence/cat052-workmanager/worker-tests-logcat.txt` and Gradle output `test-evidence/cat052-workmanager/focused-tests.log`.
- Prototype Node tests: 49/49 PASS (`node --test *.test.cjs`).
- Backend tests: 5 PASS, 4 PostgreSQL integration cases SKIPPED because the isolated PostgreSQL service was not running (`npm.cmd test`).
- Android lint: PASS, 0 errors / 138 warnings. Report: `android/app/build/reports/lint-results-debug.txt`.
- Full 65-case Android instrumentation run did not qualify: emulator system services became unavailable during the run. Two subsequent Cloud Sync UI attempts failed before the test could launch the activity because Pixel Launcher displayed an ANR dialog. Screenshots: `test-evidence/cat052-workmanager/cloud-sync-screen.png` and `test-evidence/cat052-workmanager/cloud-sync-screen-after-relaunch.png`; failure XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`; captured Gradle log: `test-evidence/cat052-workmanager/cloud-sync-screen-test.log`.
- The failing UI runs do not establish an application screen defect because the activity launch timed out while the launcher was not responding, before the test could inspect the activity. The screen edits therefore remain unqualified.

## Boundaries and remaining work

- This does not qualify end-to-end synchronization: no trusted live HTTPS server request was made, and server-side financial projection application is still absent.
- The server currently journals events; it is not yet the authoritative replica of all shop finances. Customer-linked credit/repayment and unsupported or historical outbox records remain blocked from upload.
- There is no new-device enrollment for an existing shop or conflict-resolution UI. A permanent auth/protocol failure requires owner intervention; periodic execution does not silently override it.
- WorkManager is a deferred scheduler, not continuous background connectivity. Real device battery/doze behavior and synchronization during process death remain unqualified.
- The complete Android instrumentation suite must be rerun after emulator stability is restored. Independent security review, physical Bluetooth testing, field trials and production readiness remain open.

## Next executable task

Restore a stable API 37 emulator launcher, rerun `SyncBackgroundWorkTest` and the Cloud Sync screen test, then run the full instrumentation suite on the exact final source. After that, build a synthetic localhost-to-PostgreSQL sync integration harness for authenticated event acceptance, lost acknowledgements, retries and outbox cursor reconciliation without using real shop data.

Superseding lint verification after the final Cloud Sync main-thread change: `:app:lintDebug` passed with 0 errors and 139 warnings. `:app:testDebugUnitTest` also passed after that change; the JVM report remains 35/35. The warning count supersedes the earlier 138-warning line in this continuation.
