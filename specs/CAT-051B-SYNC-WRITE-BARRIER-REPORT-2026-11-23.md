# CAT-051B / CAT-052 Sync Maintenance Barrier Follow-up

**Date:** 23 November 2026  
**Scope:** Preventing WorkManager outbox sync from racing encrypted backup or database restore.

## Finding and implementation

The existing sync worker checked the recovery-maintenance label before starting, but did not hold a shared barrier lease while loading its session, sending events, and persisting acknowledgements. A recovery operation could begin after the check and overlap an in-flight server request.

- `BusinessWriteBarrier` now closes admission to new shared operations as soon as maintenance is requested, before it waits for already-running operations. A request counter handles nested/concurrent maintenance; it preserves the existing visible maintenance label and reentrant restore behavior.
- `SyncOutboxWorker` now holds a shared business-operation permit for the full sync operation, including transport and local journal acknowledgement. A pending backup/restore waits for active sync work; newly starting workers receive retry without opening the sync session.
- Existing persistence commit permits continue to use the same barrier admission path.
- Added deterministic JVM coverage that holds an in-flight operation, queues recovery, verifies new leases are denied, verifies recovery waits, and verifies admission resumes afterward.

## Verification

- Kotlin JVM: **72 passed, 0 failed, 0 skipped**.
- Android `SyncBackgroundWorkTest`: **3 passed, 0 failed, 0 skipped** on API 37, including retry without opening a sync session while maintenance is active.
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`: **PASS**; lint **0 errors, 176 warnings**.
- Evidence XML copies: `../test-evidence/2026-11-23/cat051b-sync-write-barrier/` (ignored local artifacts).
- Node/backend tests and a full Android instrumentation suite were not rerun for this Kotlin barrier change. The previous broad Android 120-case result (82 passed, 38 failed) remains a failed regression, not superseded by these focused tests.

## Limits

This verifies barrier admission/drain behavior and the WorkManager maintenance retry path. It does not inject a real backend request paused mid-flight concurrently with restore, establish multi-process or production deployment guarantees, or replace cross-device recovery, physical Keystore/storage tests, or an independent security review. CAT-051B remains partial; production synchronization/security is not qualified.
