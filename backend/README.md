# CAT-052 local sync journal prototype

This is a local development service implemented with Node.js and PostgreSQL. It provides owner/shop/device enrollment, password authentication, revocable expiring sessions, shop-scoped access checks, and an immutable receipt journal for versioned business command envelopes.

## Run locally

Use a dedicated development PostgreSQL database. Do not point this service at the laptop's unrelated PostgreSQL service or production data.

```powershell
$env:DATABASE_URL = 'postgres://USER:PASSWORD@127.0.0.1:5432/DB'
npm.cmd install
npm.cmd run migrate
npm.cmd test
npm.cmd start
```

For a deployed runtime identity, apply `ops/runtime-grants.sql` as the database administrator with the actual database name (`psql -v database_name=APP_DB -f ops/runtime-grants.sql`), then create a LOGIN role using the organization's secret-management process and grant it membership in `chai_tapri_api`. The SQL file intentionally contains no login secret. The API role can perform the rows needed for registration, login, session revocation and sync; it cannot delete business/event rows, create schema objects, or own RLS tables.

The API binds to `127.0.0.1:8787` by default. Routes include `POST /v1/register`, `POST /v1/login`, `POST /v1/logout`, `POST /v1/sync/events`, and owner-only dashboard routes `/v1/dashboard/overview`, `/v1/dashboard/products`, `/v1/dashboard/inventory`, `/v1/dashboard/activity?shopId=<uuid>&days=7|30|90`, `/v1/dashboard/cashflow?shopId=<uuid>&days=7|30|90`, and `/v1/dashboard/profit-readiness?shopId=<uuid>&days=7|30|90`. Cashflow is a partial accepted-record movement summary, not complete accounting; profit readiness reports known inputs and keeps profit unavailable when cost coverage is absent. The local owner dashboard is served at `/owner/`; `/owner` redirects to it. Registration is currently an open development onboarding endpoint. A real deployment requires TLS, registration abuse controls, a managed secret store, operational monitoring, backup/recovery, stronger identity/MFA policy and a reviewed production authorization model.

The sync request body is `{ "shopId": "â€¦", "events": [ { "type": "business.command.v1", "schemaVersion": 1, "eventId": "â€¦", "commandId": "â€¦", "businessId": "â€¦", "shopId": "â€¦", "deviceId": "â€¦", "baseRevision": 0, "payload": { â€¦ } } ] }` and uses `Authorization: Bearer <session-token>`. Event, business, shop and device IDs are UUIDs; `commandId` is an opaque 1â€“160 character idempotency key so prefixed native command IDs remain valid. Migration `002_command_key_text.sql` upgrades existing UUID command keys to text without changing their values. A response contains a per-event `accepted`, `duplicate`, or `conflict` result and the current server revision. A conflict is a stored review record; it is not automatically merged or retried at a higher revision.

## Sync contract boundary

`business.command.v1` envelopes contain event, command, business, shop and device IDs, schema version, base server revision and a JSON payload. Schema version 1 accepts the strict `projection_delta.v1` allow-list: bounded arrays, integer paise/quantities and known states are required. Optional v1 additions include an `expenses` array with expense ID, simple category, paise amount, method and `staff_declared` status, and `estimatedUnitCostPaise` on order lines. The latter is nullable nonnegative integer paise, is an owner-entered estimate snapshot, and may be omitted by older clients. Free-form notes, drawer/session links and customer identifiers are excluded. Customer-linked credit/repayment data remains rejected until an opaque identity contract is defined. The service records accepted envelopes, recognizes exact retries, rejects reused IDs with altered contents and records stale-revision conflicts. It does not execute a command against authoritative financial projections, resolve conflicts, or promise exactly-once delivery. Clients must retain their local outbox entries until they receive and persist an acknowledgement.

Android persists an encrypted sync outbox and server revision/outcome journal. Its minimized projection currently supports selected order (including optional line cost estimates), stock movement, non-customer-linked payment-entry and expense mutations. Customer-linked credit/repayment and unsupported entities remain blocked; expense notes and drawer links are omitted. Source includes envelope mapping, an HTTPS transport, saved session material and manual/background runners, but authenticated Android-to-PostgreSQL delivery has not been qualified end to end in the current environment. Server journal acceptance is not an authoritative business projection, and owner reports include only accepted events.

Database migration `001_core.sql` enables row-level security for event and conflict tables. API queries also bind the authenticated shop explicitly. Tests have exercised cross-shop read/write isolation as the least-privilege non-superuser role created from `ops/runtime-grants.sql`; they also verify that this role cannot delete immutable sync events. The grant set still needs independent review before deployment.

The current isolated PostgreSQL 17 test run passed 9/9: five payload-contract tests plus four authenticated service/HTTP integration tests. Tests use only synthetic records and a temporary local cluster; the cluster was stopped after the run.

## OW-01 owner overview continuation — 1 November 2026

The same-origin `/owner/` page signs in through the existing account endpoint and requests read-only, owner-scoped views. The APIs bind reads to that session's shop and use the sync-event RLS policy. Overview values derive from accepted order/payment/stock events. Daily Sales Activity also exposes accepted, staff-declared expenses by UTC server receipt date and category from the allow-listed expense subset. No independent accounting projection is persisted; offline/blocked events are absent. Delivered item value and recorded expenses are not profit; cash is not externally verified and UPI is not provider-verified. Browser session tokens are kept in memory only.

After this addition, the full backend suite passed 13/13 on a fresh isolated PostgreSQL 17 cluster, including authenticated sync followed by owner overview, wrong-shop denial, RLS and logout. Evidence: `../test-evidence/2026-11-01/cat052-owner-overview-integration.log` and `cat052-owner-overview-postgres.log`. The temporary cluster was stopped. See [`../specs/OW-01-OVERVIEW-REPORT.md`](../specs/OW-01-OVERVIEW-REPORT.md). This is a local development vertical slice, not a deployed dashboard or production sync qualification.

## OW-05 product performance continuation - 1 November 2026

Added the owner-only product performance endpoint and dashboard section. The projection groups latest accepted orders by product, subtracts returned units, excludes voided/unposted orders, shows stock state separately, and refuses unsafe integer totals. The synthetic PostgreSQL suite passed 15/15, including accepted event to dashboard integration, tenant isolation, and the new route. See [`../specs/OW-05-PRODUCT-PERFORMANCE-REPORT.md`](../specs/OW-05-PRODUCT-PERFORMANCE-REPORT.md) and `../test-evidence/2026-11-01/ow05-product-performance-backend.log`. It is not profit or date-attributed reporting and excludes unsynced/blocked activity.

