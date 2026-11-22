# CAT-052 / OW-08 Customer Credit Projection

**Date:** 23 November 2026
**Status:** Implemented and qualified for the described local synthetic-data slice. This does not complete CAT-052 or OW-08 as a full module.

## Delivered

- SQLCipher schema v29 adds stable opaque sync UUIDs to customer credit and repayment rows and a local-customer-ID-to-opaque-ID table. The v28-to-v29 migration is additive and backfills existing local customer profiles, credit, repayment, and payment references without sending those local IDs to the server.
- The Android projection emits only opaque customer and record identifiers plus integer-paise credit state, repayment activity, and customer-linked payment events. It omits customer name, phone, local customer ID, and order details from the customer-credit record projection. Customer profile changes remain local and blocked from sync.
- Backend projection validation accepts exact allow-listed customer credit/repayment records and UUID customer references. It rejects local IDs and extra identifying fields. The journal enforces stable credit identity, nondecreasing repaid/reversed state, immutable repayment IDs, and repayment totals no greater than settled credit state.
- Owner-only `GET /v1/dashboard/customers` derives latest credit states and unique repayment activity from accepted events. OW-08 displays opaque IDs, extended/settled/repaid/reversed amounts and recorded outstanding balance, and supports current-view CSV export.
- The balance calculation uses the current credit state (`amount - repaid - reversed`); separate repayment events are activity evidence and are not subtracted a second time.

## Verification

- Backend Node suite with an isolated PostgreSQL cluster and restricted runtime role: **30/30 passed, 0 skipped**. The ordinary no-database run is **26 passed, 4 skipped** because those integration tests require PostgreSQL.
- Kotlin JVM unit tests: **68 passed** (`testDebugUnitTest`).
- APK build, instrumentation APK build, and Android lint: **BUILD SUCCESSFUL**; lint report: `android/app/build/reports/lint-results-debug.html` (existing warnings remain; no lint task error).
- API 37 emulator migration test: `schemaV27AddsOpaqueProcurementSyncIdsWithoutChangingLocalRecords`, **1/1 passed**, including preservation and opaque UUID backfill of customer, credit, repayment, and payment records.
- API 37 emulator live Android-to-backend customer-finance drill: **1/1 passed** against a synthetic customer and isolated local HTTPS/PostgreSQL service. Seven outbox events were acknowledged. The owner projection reported ₹5 extended, ₹2 repaid, and ₹3 outstanding; customer name, phone, and local customer ID were absent from the server response.
- Full emulator instrumentation run completed with **112 passing test cases and 4 failures**. The 4 failures were three opt-in live backend test classes invoked without their required isolated HTTPS endpoint; they fail at `Assume.assumeTrue` rather than reaching product assertions. The focused customer-finance live test above was run with the endpoint and passed. A later attempt to exclude those classes via a Gradle property did not forward the filter to the runner and was stopped after confirming it was still running the excluded class. Do not treat the broad instrumentation run as fully green.
- `git diff --check`: passed. Browser visual review was not performed; the pre-existing browser authorization gate remains in force.

The full-run XML was inspected for counts and then replaced by the later interrupted run; the actual result summary is preserved in ignored local evidence at `test-evidence/2026-11-23/cat052-customer-credit-qualification.txt`. The backend suite, JVM suite, build, lint, migration test and focused live test were actually executed in this continuation. No production service or real customer data was used.

## Scope limits

Customer names and phone contacts are intentionally not uploaded. The backend remains an authenticated append-only event journal with derived operational summaries; it is not an authoritative accounting ledger or a verified collections system. Offline and blocked records are excluded until synchronization succeeds. The Android customer/payment opaque UUID migration was tested on the emulator; broader device-replacement recovery and physical devices were not tested. Production deployment, owner browser visual review, physical Bluetooth/device qualification, independent security review, and real-shop trials remain separate gates.
