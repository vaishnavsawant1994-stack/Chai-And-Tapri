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

## Live in-flight sync and recovery race â€” 23 November 2026

Added an opt-in API 37 instrumentation scenario and test-only HTTPS backend controls. The synthetic service holds one `/v1/sync/events` response only after the transaction has committed to an isolated PostgreSQL 17 cluster. While that response is held, the test requests recovery maintenance, verifies new business-operation admission is closed, runs a competing worker and confirms it retries without another HTTP sync request. Releasing the held response lets the active worker persist its local acknowledgment and drain the second ordered outbox event; maintenance enters only after the worker finishes.

The test verifies two accepted synthetic events, two local cursor revisions, an empty local outbox, one order, stock reduced from ten to nine, and recovery admission restored after maintenance. The test server's `/__test/*` routes exist only in `backend/test/serve-android-sync-e2e.mjs`; they require a test-only control token and are bound to loopback. The test used a disposable local PostgreSQL 17 cluster, the existing test-only TLS certificate, `adb reverse`, and synthetic records. It did not use the laptop's shared PostgreSQL service or a production endpoint.

The live scenario exposed that a `ReentrantReadWriteLock` lease could be acquired and closed across coroutine dispatcher threads. `BusinessWriteBarrier` now tracks thread-independent shared leases, and `SyncOutboxWorker` propagates its admitted lease through coroutine thread switches. Commits from that already-admitted operation remain allowed while its lease drains; unrelated commits and new workers remain blocked once maintenance is requested. A JVM regression test covers cross-thread lease release and nested journal-commit admission.

Verification for this slice: Kotlin JVM **59/59**; focused `LiveSyncMaintenanceBarrierE2ETest` **1/1** on API 37; `SyncBackgroundWorkTest` **3/3**; `InstrumentationConfigurationTest` **1/1**; `testDebugUnitTest`, `assembleDebug`, and `assembleDebugAndroidTest` **PASS**. Current-source prototype Node tests passed **52/52**; backend Node tests reported **26 passed, 4 skipped** (PostgreSQL-dependent cases); `lintDebug` passed with **0 errors, 176 warnings**. This was not a full Android instrumentation run. JUnit and isolated service logs are in ignored `../test-evidence/2026-11-23/cat051b-sync-maintenance-e2e/`.

CAT-051B remains partial. This synthetic server race does not qualify restore cutover with a live SQLCipher replacement, multi-process admission, cross-device recovery, real storage exhaustion/power-loss, hardware Keystore invalidation, physical device behavior, independent security review, or production sync/security.
