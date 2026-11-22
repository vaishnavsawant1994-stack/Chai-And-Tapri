# CAT-052 / OW-07 Supplier Finance Slice

**Date:** 23 November 2026  
**Status:** Implemented; local Kotlin and backend unit-tested. PostgreSQL-backed acceptance and browser visual qualification remain pending.

## Delivered

- Android supplier, receipt, supplier-payment and purchase-return records now carry persisted opaque UUIDs for sync. SQLCipher schema migration v27→v28 adds these identifiers and fills existing rows without changing local business IDs or financial data.
- The sync allow-list emits supplier finance totals only. It excludes names, phone numbers, local supplier and record IDs, invoice references, product names/IDs, drawer IDs and free-form notes. Profile mutations remain local and unsupported by the sync contract.
- Backend validates exact field sets, UUID identities, integer paise, allowed tender methods and `staff_declared` settlement status. Accepted returns must reference a receipt for the same opaque supplier and stay within the receipt's unreturned value. Procurement record IDs cannot be replayed under a different accepted event.
- Owner-only `GET /v1/dashboard/suppliers` and dashboard view OW-07 summarize accepted receipt values, declared supplier payments, returns and net payable/credit. UI supports refresh and CSV export through the existing report mechanism.
- Evidence text states that records include accepted events only, payments are staff-declared and not bank-verified, identifiers are opaque, and the result is not an accounting statement.

## Verification

- Backend Node suite: **24 passed, 0 failed, 4 PostgreSQL-dependent tests skipped** because this run had no isolated PostgreSQL test connection. Unit coverage checks projection privacy/shape, ledger replay rejection, return allocation, aggregate arithmetic, owner-only route access and dashboard wiring. The PostgreSQL integration test now also exercises receipt/payment/return sync and OW-07 totals when configured, but was skipped in this run.
- Kotlin JVM unit suite: **68 passed, 0 failed**. The projection test confirms supplier PII and product/invoice details are absent from serialized payloads.
- Isolated API 37 emulator: **8/8 focused procurement/migration instrumentation tests passed**, including v27→v28 preservation of supplier, receipt, settlement, return, stock and payable records. Evidence: `../test-evidence/2026-11-23/cat052-procurement-migration-regression.txt`. A full persistence class run before correcting old schema-version expectations produced 7 failures, all from tests expecting schema version 27; source expectations were corrected and all seven tests plus the new migration test passed individually.
- Debug APK and instrumentation APK build tasks passed; Android lint passed with **0 errors / 175 warnings**. Node syntax checks passed for backend and owner web modules.
- Browser visual inspection and PostgreSQL-backed live sync were **not run** in this slice.

## Limits and next work

The server remains an immutable event journal with derived owner summaries, not an authoritative supplier payable ledger. Staff-declared payment totals are not cash/bank verified. Negative payable values are surfaced as recorded supplier credit/advance. Profile edits do not sync. This feature is a narrow W3/W4 vertical slice; it does not complete CAT-052, OW-07 as a full supplier-analysis module, the owner dashboard, or the six-wave product plan.

Next: run the backend suite on an isolated PostgreSQL 17 test cluster, exercise the supplier projection through the full encrypted-outbox-to-server path on an emulator, and test the OW-07 interaction through the authorized browser review path.
