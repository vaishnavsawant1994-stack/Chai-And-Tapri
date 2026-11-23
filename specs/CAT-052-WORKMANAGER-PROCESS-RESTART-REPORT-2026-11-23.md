# CAT-052 — Pending WorkManager Sync Across App Process Restart

**Date:** 23 November 2026  
**Result:** Two-phase synthetic API 37 process-restart drill passed; CAT-052 remains in progress.

## What was tested

An opt-in instrumentation test used the production `SyncOutboxWorker`, the default SQLCipher database, the Keystore-protected sync session, WorkManager's persistent database, and an isolated local HTTPS/PostgreSQL server.

The prepare phase created a synthetic product, opening stock, and one ₹30 cash sale. It registered a synthetic owner session and queued the real worker with a 60-second initial delay. The test saved only the process ID/name, work UUID, shop UUID and test endpoint in an app-private no-backup checkpoint. The phase passed and ended with the work request pending.

After phase one exited, `pidof com.chaiandtapri.counter` returned no running process. Phase two launched a new instrumentation process; the test verified the process name remained the target app and PID changed from **3970** to **4047**. It reopened the saved Keystore session, found the same persisted WorkManager work UUID, and waited for that exact job to complete successfully.

Post-sync checks confirmed:

- one local order worth 3000 paise and stock reduced from 8 to 7;
- no pending local outbox events and cursor at server revision 2;
- server overview retained exactly one order, two accepted events and 3000 paise declared cash;
- no duplicate sale was posted after process restart.

**Prepare instrumentation:** 1/1 passed.  
**Resume instrumentation:** 1/1 passed.  
**Build:** `assembleDebug` and `assembleDebugAndroidTest` passed.  
**Regression tasks:** `testDebugUnitTest` passed (67 tests); `lintDebug` passed with 0 errors / 175 warnings.  
**Run log:** `test-evidence/2026-11-23/cat052-worker-process-restart.log` (local ignored evidence; direct `am instrument` does not emit Gradle JUnit XML).

## Scope limits

This proves a queued one-time WorkManager sync resumes after the app process is gone and a new app process starts. It does not prove termination while an HTTP request is in flight, process death during the WorkManager retry backoff after a lost acknowledgment, Android OEM/background restriction behavior, multiple physical devices, production TLS/accounting, or physical Bluetooth/microphone behavior. The prior report separately qualifies retry/idempotency after an acknowledgment is lost, but without process termination during that retry.

Only synthetic shop records and a locally hosted test certificate/backend were used. Existing AVD business data was not cleared; this test ran on the isolated synthetic AVD and guarded existing credentials, sessions, catalogue and business activity.

## Current-source rerun — 23 November 2026

The current source was rerun on the API 37 `Tapri_Recovery_Isolated` emulator against a fresh PostgreSQL 17 cluster at loopback port 55481 and local HTTPS test server at port 8794. The backend ran under the non-superuser, non-BYPASSRLS `chai_tapri_api` role; migrations were applied by the local postgres administrator. The emulator reached only the local endpoint through `adb reverse`.

Prepare passed 1/1 and persisted the delayed WorkManager request. `am force-stop com.chaiandtapri.counter` was followed by an empty package process list before resume. Resume passed 1/1 and the test logged a PID change from 6852 to 6909 while resuming the same work UUID. Logs: ignored `test-evidence/2026-11-23/cat052-worker-suite/process-restart-prepare.txt`, `process-restart-checkpoint.json`, `process-restart-pids.txt`, and `process-restart-resume.txt`. The database contained synthetic test identities/events only and was stopped after the run; the port-8794 reverse mapping was removed.

The separate ack-loss retry test passed 1/1 against the same local backend (`retry-ack-loss.txt`). The PostgreSQL-backed backend suite passed 30/30 with no skips using the restricted runtime role and administrator cleanup connection (`backend-tests.txt`). These results qualify the two focused scenarios at current source, not production sync, multi-device conflicts, OEM background behavior, real devices, shop operations, or production security.
