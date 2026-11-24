# OW-13 Owner Insights — implementation report

Date: 24 November 2026  
Status: Implemented and backend-tested; browser visual qualification pending.

## Delivered

- Added authenticated owner-only `GET /v1/dashboard/insights?shopId=…` backed by the shop-scoped accepted sync-event journal.
- Added deterministic follow-ups for open orders, pending UPI attempts, aggregate customer receivables, aggregate supplier payables, and missing unit-cost coverage.
- Added an Owner Insights tab with source-view navigation and CSV export of the displayed follow-ups.
- Follow-ups use opaque aggregate totals; customer and supplier identifiers are not returned. Amounts remain integer paise in the API.
- Empty input produces an explicit no-follow-up state. Profit is not calculated or inferred.

## Evidence and limits

`backend` suite without a database: 30 passed, 0 failed, 5 PostgreSQL-dependent tests skipped. Owner-web JavaScript syntax check passed. A later run against a fresh disposable PostgreSQL 17 cluster, migrations 001–004 and a restricted runtime role passed **35/35, 0 failed, 0 skipped**. The authenticated integration flow now accepts synthetic order, pending-UPI, receivable and supplier events, fetches the insight route, verifies the exact aggregate follow-ups, checks that opaque customer/supplier IDs are absent, and rejects a wrong-shop request. Captured output: ignored `backend/test-evidence/2026-11-24/ow13-postgres/backend-test.log`.

The data is limited to accepted server events. Pending UPI attempts are not verified receipts; staff-declared financial records are not independently verified; offline, blocked and unsynchronized data is excluded. The reminders are deterministic rules, not generative AI or predictive advice. No browser was opened, so visual/accessibility qualification remains pending under CAT-046's existing review boundary. No Android test was run. The database suite used synthetic data and an isolated local cluster, which was stopped after testing; the existing Windows PostgreSQL service was not used.

## Changed files

- `backend/src/dashboard.mjs`
- `backend/src/service.mjs`
- `backend/src/http.mjs`
- `backend/test/dashboard.test.mjs`
- `backend/test/sync-service.test.mjs`
- `owner-web/index.html`
- `owner-web/app.js`
- `owner-web/styles.css`
- `backend/README.md`

Next: run the dashboard route against the isolated PostgreSQL integration suite, then continue the next prioritized shopkeeper vertical slice. Do not treat this as production AI, financial advice, visual review, or product completion.
