# CAT-051B/CAT-052 live sync and recovery maintenance barrier

**Date:** 25 November 2026  
**Result:** Focused synthetic API 37 Android-to-backend race test passed twice.

## Scenario

Ran `LiveSyncMaintenanceBarrierE2ETest.maintenanceWaitsForCommittedSyncAckAndRejectsNewWorker` using the installed debug app and instrumentation APK, an isolated API 37 emulator, a disposable PostgreSQL 17 test cluster, the repository's test-only TLS certificate, and a loopback HTTPS test server. The test server held the response to the first sync request after the database committed it. The client therefore had an in-flight WorkManager sync while restore/recovery requested the business write barrier.

The test verified that maintenance closed business-operation admission and waited for the in-flight lease; a competing worker returned `Retry` without making a second HTTP request; after the held acknowledgment was released, the original worker acknowledged both ordered outbox events, advanced its cursor to 2, and left no pending events; only then could maintenance acquire the barrier. The server saw exactly two sync requests, the owner projection contained two accepted events, one ₹25.00 order, and stock was reduced from ten to nine. The barrier admitted a new business operation after maintenance completed.

Two direct instrumentation invocations passed: **1/1** each (0 failures/errors/skips). Evidence:

- `test-evidence/2026-11-25/cat052-maintenance-barrier-instrumentation.txt`
- `test-evidence/2026-11-25/cat052-maintenance-server-evidence.txt`

The harness used an ephemeral 256-bit control token, passed only to the loopback test process and instrumentation invocation; it is not written to the evidence files. The clean-store/session/work guards in the test passed before the synthetic database was seeded. Afterward the app's cleanup path paused background sync, revoked the synthetic session, cleared its synthetic store and local session. PostgreSQL was stopped, the HTTPS process was terminated, and the ADB reverse mapping was removed.

## Limits

This verifies one process-local race between a held server acknowledgment, a competing worker and the recovery barrier. It does not qualify device power loss, service restarts during the barrier, production sync, physical hardware, multi-device conflict resolution, cross-device recovery, independent security review or production readiness. The test used a synthetic local server/database and does not replace the broader Android regression or backend suite.
