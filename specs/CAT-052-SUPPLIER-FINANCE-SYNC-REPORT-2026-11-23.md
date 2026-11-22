# CAT-052 / OW-07 Supplier Finance Slice

**Date:** 23 November 2026  
**Status:** Implemented and locally qualified for the synthetic backend contract, SQLite migration, and one Android-to-isolated-server end-to-end flow. Browser visual qualification remains pending.

## Delivered

- Android supplier, receipt, supplier-payment and purchase-return records now carry persisted opaque UUIDs for sync. SQLCipher schema migration v27â†’v28 adds these identifiers and fills existing rows without changing local business IDs or financial data.
- The sync allow-list emits supplier finance totals only. It excludes names, phone numbers, local supplier and record IDs, invoice references, product names/IDs, drawer IDs and free-form notes. Profile mutations remain local and unsupported by the sync contract.
- Backend validates exact field sets, UUID identities, integer paise, allowed tender methods and `staff_declared` settlement status. Accepted returns must reference a receipt for the same opaque supplier and stay within the receipt's unreturned value. Procurement record IDs cannot be replayed under a different accepted event.
- Owner-only `GET /v1/dashboard/suppliers` and dashboard view OW-07 summarize accepted receipt values, declared supplier payments, returns and net payable/credit. UI supports refresh and CSV export through the existing report mechanism.
- Evidence text states that records include accepted events only, payments are staff-declared and not bank-verified, identifiers are opaque, and the result is not an accounting statement.

## Verification

- Backend Node suite without a database: **24 passed, 0 failed, 4 PostgreSQL-dependent tests skipped**. Then a new isolated PostgreSQL 17 cluster with a restricted runtime role passed **28/28, 0 failed, 0 skipped**. Integration coverage includes privacy-minimized receipt/payment/return sync, owner OW-07 totals, tenant isolation, idempotency and authentication. Test log: `../test-evidence/2026-11-23/cat052-supplier-finance-postgres-tests.log`; the temporary cluster was stopped after testing.
- Kotlin JVM unit suite: **68 passed, 0 failed**. The projection test confirms supplier PII and product/invoice details are absent from serialized payloads.
- Isolated API 37 emulator: **8/8 focused procurement/migration instrumentation tests passed**, including v27â†’v28 preservation of supplier, receipt, settlement, return, stock and payable records. Evidence: `../test-evidence/2026-11-23/cat052-procurement-migration-regression.txt`. A full persistence class run before correcting old schema-version expectations produced 7 failures, all from tests expecting schema version 27; source expectations were corrected and all seven tests plus the new migration test passed individually.
- Debug APK and instrumentation APK build tasks passed; Android lint passed with **0 errors / 175 warnings**. Node syntax checks passed for backend and owner web modules.
- Browser visual inspection was **not run** in this slice. The Android outbox/worker was exercised in a later isolated E2E continuation documented below.

## Limits and next work

The server remains an immutable event journal with derived owner summaries, not an authoritative supplier payable ledger. Staff-declared payment totals are not cash/bank verified. Negative payable values are surfaced as recorded supplier credit/advance. Profile edits do not sync. This feature is a narrow W3/W4 vertical slice; it does not complete CAT-052, OW-07 as a full supplier-analysis module, the owner dashboard, or the six-wave product plan.

Next: exercise the supplier projection through the Android encrypted-outbox-to-server path on an emulator, and test the OW-07 interaction through the authorized browser review path.

## Android encrypted-outbox end-to-end qualification â€” 23 November 2026

The native Android instrumentation path now creates synthetic supplier finance records in the SQLCipher store, emits the privacy-minimized outbox events, synchronizes them over the isolated HTTPS backend using the restricted PostgreSQL runtime role, and reads the owner-only OW-07 projection. The API 37 isolated emulator test passed **1/1**. It verified four accepted events (opening stock, receipt, staff-declared cash settlement, return), server revision 4, an empty acknowledged outbox, local stock 4 and payable â‚¹300; the remote projection reconciled receipts â‚¹1,000, declared payments â‚¹200, returns â‚¹500 and net payable â‚¹300. It also checked that supplier name, phone, invoice reference and product details are absent and that payment status is explicitly not bank-verified.

Evidence: `../test-evidence/2026-11-23/cat052-android-supplier-finance-e2e.txt`. The full Android verification command `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` completed with **BUILD SUCCESSFUL** after this test was added. The PostgreSQL backend integration remains 28/28 from the isolated run documented above; this Android-only addition did not change backend source. Lint remains at 0 errors; warning count is recorded in the current Gradle lint report.

This is an isolated synthetic emulator-to-local-test-server flow, not production sync or accounting qualification. It does not qualify owner browser visuals, full CAT-052, cloud deployment, real shop activity, Bluetooth, physical devices, or independent security review. Supplier profiles remain local and backend totals are reconstructed from accepted events.

