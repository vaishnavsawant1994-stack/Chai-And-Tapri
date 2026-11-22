# CAT-052 — WorkManager Retry After Lost Server Acknowledgment

**Date:** 23 November 2026
**Result:** Focused synthetic Android integration test passed; CAT-052 remains in progress.

## What was qualified

The Android instrumentation test invokes the production `SyncBackgroundScheduler` and `SyncOutboxWorker`. It uses a locally hosted, test-certificate HTTPS backend backed by the isolated PostgreSQL test cluster. The synthetic backend commits the first sync request and then closes the response socket to model a lost acknowledgment.

The test creates one synthetic product with ten units of opening stock, records a ₹25 cash sale, schedules real WorkManager work, and waits for completion. It asserts that:

- the worker completed successfully after at least one WorkManager retry;
- the event outbox is empty and the acknowledged server revision is 2;
- exactly one order remains locally, valued at 2500 paise;
- local stock is nine units;
- the authenticated server overview has exactly one order and 2500 paise of cash collections;
- retrying the committed request did not duplicate the business event.

**Focused result:** 1 test passed, 0 failures.
**Build result:** `assembleDebug` and `assembleDebugAndroidTest` passed.

Additional current-source checks on 23 November: Kotlin JVM 67/67; Node/backend tests 24/24 with the isolated PostgreSQL cluster enabled; `lintDebug` passed with 0 errors and 175 warnings; the backend test harness passed `node --check`.

## Test isolation and safety

The test is opt-in and requires an explicitly supplied local E2E endpoint. It declines to run when it finds an existing owner session, saved cloud identity, active sync job, owner/staff account, configured catalogue, stock activity, ledger record, customer, shop name, or outbox item. It allows only the four unconfigured starter products and their zero-quantity stock rows. Synthetic credentials and shop records are generated for each run. The test logs out and clears only its synthetic app store after the attempt.

Both existing AVDs contained test data and were preserved. The run used a fresh `CAT052_Worker_Synthetic` API 37 AVD. The local backend was connected to the isolated PostgreSQL cluster under `test-evidence/2026-11-22/`; no production server, external account, real shop, or customer data was used.

## Evidence and limitations

The final focused test output was observed from the `am instrument` run on 23 November 2026 (`OK (1 test)`). APK build output was observed from `assembleDebug assembleDebugAndroidTest`. A JUnit XML artifact was not generated for this direct instrumentation invocation. The complete Node/backend and Kotlin JVM suites were rerun and passed; the complete Android instrumentation suite was not rerun for this test-only slice.

This test demonstrates WorkManager's synthetic retry/idempotency path after a server-side commit whose response is lost. It does not demonstrate process termination while work is queued, cross-device recovery, production service behavior, production TLS/secret operations, authoritative server accounting, background reliability across OEM devices, or physical microphone/Bluetooth operation. The test's outbox payload remains restricted to the existing minimized supported entities. CAT-052 and all product rollout gates remain open.
