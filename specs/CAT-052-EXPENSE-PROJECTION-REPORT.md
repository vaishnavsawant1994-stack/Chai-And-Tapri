# CAT-052 — Privacy-Minimized Expense Projection

**Date:** 19 November 2026  
**Status:** Privacy-minimized projection is implemented; one synthetic Android-to-isolated-backend runtime slice is qualified below.

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

The PostgreSQL integration tests were skipped because no test database URL is configured. Android instrumentation did not complete: a full run stalled during device tests, and a later focused financial-restore invocation did not launch its test runner. A later synthetic Android outbox-to-server end-to-end test is recorded in the current continuation below. Owner dashboard browser visual review, shop-local timezone support, provider verification, and production security review remain separate gates.

## CAT-052 Android expense projection E2E ? 23 November 2026

The synthetic API 37 Android instrumentation journey now records a private-noted transport expense alongside a supplier receipt, declared settlement, and purchase return. It verifies the encrypted SQLCipher outbox contains all five expected business events, submits them over authenticated HTTPS to a fresh isolated PostgreSQL cluster through the Android `SyncRunner`, and checks all five are acknowledged at server revision 5 with an empty outbox. Local payable remains ?300, stock remains 4, and local expense total is 7,500 paise.

The owner `/v1/dashboard/activity` result reports ?7,500 staff-declared expenses and one `transport` category record at ?7,500. The test confirms the private expense note is not present in the owner response. Supplier OW-07 values continue to reconcile independently: receipts ?1,000, declared payments ?200, returns ?500, payable ?300. The final source was freshly compiled and the focused instrumentation run passed 1/1 with 0 failures/errors/skips. Backend PostgreSQL regression passed 28/28 with a non-superuser runtime role after removing DELETE privileges; the initial run exposed an overly broad grant in the temporary test harness and was discarded. Evidence: `../test-evidence/2026-11-23/cat052-expense-android-e2e.xml`, `cat052-expense-android-e2e-logcat.txt`, and `cat052-expense-backend-postgres-tests.log`.

This qualifies one synthetic Android-to-isolated-backend expense projection flow. It does not verify shop-local business dates, provider-confirmed payment, profit completeness, owner browser rendering, production hosting, live shop records, or complete CAT-052 synchronization. Backend remains an event journal; unsynchronized, blocked, customer-linked credit and other unsupported records are not covered.
