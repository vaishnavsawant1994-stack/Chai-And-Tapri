# CAT-052 WorkManager retry after committed sync acknowledgement loss

**Date:** 25 November 2026  
**Result:** Focused synthetic API 37 emulator integration passed.

## Scenario

Ran `LiveBackgroundSyncRetryE2ETest.workerRetriesCommittedEventsAfterAckLossWithoutDuplicates` on the isolated `Tapri_Recovery_Isolated` API 37 emulator. The test preflight required an empty default business store, no saved cloud identity/session, and no unfinished sync work before it seeded synthetic data. It registered a synthetic owner against a disposable PostgreSQL 17 database and queued opening-stock plus cash-sale outbox events.

The loopback-only HTTPS test service was configured to commit the first `/v1/sync/events` request and then drop its response. The WorkManager worker retried. Instrumentation asserted the work succeeded with at least one retry, exactly two events were acknowledged at revision 2, the local outbox was empty, one ₹25.00 order remained, stock changed from 10 to 9, and the owner summary contained two accepted events and one order with ₹25.00 staff-declared cash. This tests the selected acknowledgement-loss scenario; it is not a general exactly-once guarantee.

## Results and evidence

- Focused Android instrumentation: **1 passed, 0 failed, 0 errors, 0 skipped**.
- Gradle task: `connectedDebugAndroidTest` completed successfully for the selected method; app and instrumentation artifacts were already current.
- Server fault evidence: `test-evidence/2026-11-25/cat052-workmanager-retry/fault-injection-server.log` records `Synthetic response-loss fault after sync request 1 committed.`
- JUnit: `test-evidence/2026-11-25/cat052-workmanager-retry/android-workmanager-ack-loss-retry.xml`.
- The test used the same disposable PostgreSQL 17 cluster from the preceding 35/35 backend run, a non-superuser runtime login, repository test-only TLS material, and temporary ADB reverse mapping. It used synthetic data only. After the test the HTTPS process was stopped, ADB reverse mapping was absent, and `pg_ctl status` reported no PostgreSQL server running.

## Limits

This is one emulator retry/fault-injection case. It does not qualify production service operation, real intermittent networks, multiple devices, concurrent edits, full conflict recovery, endpoint security or production deployment. The test cleaned its synthetic default store only after the preflight guard passed. The full Android regression was not rerun in this continuation.
