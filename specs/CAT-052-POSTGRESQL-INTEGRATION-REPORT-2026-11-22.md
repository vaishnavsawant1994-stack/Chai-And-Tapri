# CAT-052 Isolated PostgreSQL Integration Verification

**Date:** 22 November 2026  
**Status:** Local synthetic PostgreSQL integration suite passed; production synchronization remains unqualified.

## Environment and isolation

- Created a fresh PostgreSQL 17 cluster under the ignored local `test-evidence/2026-11-22/pg-wave3-*` directory.
- Applied backend migrations `001_core` and `002_command_key_text` to a dedicated synthetic test database.
- Applied `backend/ops/runtime-grants.sql`; application tests connected through a dedicated restricted login that inherited `chai_tapri_api`. Test cleanup used a separate local administrator connection.
- No existing system-service database, shop/customer records, or external service was used. The temporary cluster was stopped after testing.

## Result

`npm.cmd test` in `backend/`: **23 passed, 0 failed, 0 skipped**.

The run includes authenticated registration and login, bad-password lockout, bearer logout/revocation, tenant-scoped journal writes and reads, exact retry/idempotency behavior, altered-event rejection, stale-revision conflict recording, runtime-role immutability/RLS checks, HTTP sync, and the owner overview/product/activity/cashflow/profit-readiness projections represented by the tests.

Test log: `test-evidence/2026-11-22/backend-postgres-tests.log` (local ignored evidence). Migration and role setup completed successfully against this isolated cluster.

## Limits

This verifies a local backend prototype with synthetic records. It does not establish Android-to-server end-to-end sync, production TLS, registration abuse protections, managed secrets, operational backup/recovery, complete business projections, independent authorization review, deployment readiness, or multi-device conflict resolution. The current server records accepted journal envelopes and does not execute them as an authoritative accounting ledger.
