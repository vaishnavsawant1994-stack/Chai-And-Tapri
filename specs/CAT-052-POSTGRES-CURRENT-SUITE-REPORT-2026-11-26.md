# CAT-052 PostgreSQL integration suite — 26 November 2026

## Result

The current backend Node suite passed **39/39, 0 failures, 0 skips** against a fresh disposable PostgreSQL 17 instance.

## Setup and scope

- Created an isolated Docker PostgreSQL 17 container bound only to `127.0.0.1:55443` with a synthetic test database.
- Applied backend migrations 001–004.
- Applied `backend/ops/runtime-grants.sql` and ran tests with a restricted `tapri_runtime` login; the test cleanup connection used the isolated administrator account.
- Exercised all backend unit and PostgreSQL integration tests, including authenticated tenant sync/idempotency/conflicts, unit-label projection, weak credential rejection, persistent login lockout, staff invitation/removal and HTTP API sync/dashboard/logout.
- Used generated synthetic test identities only. No external or production database was accessed.
- Stopped the disposable test container after the run. The unrelated pre-existing Docker container was not modified; the host PostgreSQL service was not used.

## Evidence and limits

Test log: `../test-evidence/2026-11-26/backend-postgres-39-of-39.log` (local ignored evidence).

This validates the current backend code and migrations against the local test database. It does not qualify a deployed service, production infrastructure, Android live-sync end-to-end operation, multi-device conflict resolution, independent security review, or owner-web visual/accessibility behavior.
