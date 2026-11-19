# CAT-052 — Privacy-Minimized Expense Projection

**Date:** 19 November 2026  
**Status:** Source and host-test qualified; Android-to-server runtime sync not qualified.

## Change

The Android outbox encoder now includes newly recorded expense entries in the existing `projection_delta.v1` contract. It sends only:

- expense ID
- normalized category
- amount in paise
- declared payment method (`cash`, `upi`, or `card`)
- `staff_declared` status

Expense notes and drawer-session IDs stay on the device. The backend allows the `expenses` array as an optional additive v1 key so older clients remain accepted; the expense object itself remains exact-key validated. Existing customer-linked credit and repayment data remains blocked.

The owner Sales Activity API groups accepted expenses by server receipt date in UTC and by category. Distinct expense IDs are counted once. Values are staff-declared entries and do not establish external payment, actual profit, or full operating cost.

## Verification

- Prototype Node: 52 passed, 0 failed.
- Backend Node and isolated PostgreSQL integration: 20 passed, 0 failed, 0 skipped. The test database was a separate temporary PostgreSQL 17 cluster; the system service was not used. See `../test-evidence/2026-11-19/backend-postgres-tests.log`.
- Kotlin JVM: 56 passed, 0 failed.
- Debug APK and instrumentation APK build tasks: passed.
- Android lint: 0 errors, 161 warnings.

The PostgreSQL-backed HTTP test sends an accepted synthetic expense event, reads it through the owner activity endpoint, checks server revision, daily and category totals, verifies an exact retry remains a duplicate without increasing the aggregate, and rejects a different shop ID. The test runs with a non-superuser API role and a separate admin cleanup connection.
- Changed JavaScript syntax checks: passed.

Evidence logs: `../test-evidence/2026-11-19/prototype-current-node.log`, `backend-expense-projection-tests.log`, `backend-postgres-tests.log`, and `android-expense-projection-host-tests.log`.

The test suite explicitly verifies that notes, phone-like strings, and drawer-session links are absent from the serialized expense projection; the API accepts a valid minimal expense and rejects unknown/private fields, invalid amounts, invalid categories, and invalid tenders. Legacy v1 payloads without an `expenses` array remain valid.

## Open qualification

The PostgreSQL integration tests were skipped because no test database URL is configured. Android instrumentation did not complete: a full run stalled during device tests, and a later focused financial-restore invocation did not launch its test runner. This change is not an end-to-end sync test. Owner dashboard browser visual review, shop-local timezone support, provider verification, and production security review remain separate gates.
