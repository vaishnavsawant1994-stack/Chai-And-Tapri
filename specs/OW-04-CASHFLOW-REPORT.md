# OW-04 Recorded Money Movements — Implementation Report

**Status:** Partial owner-dashboard vertical slice; browser visual and PostgreSQL-backed route integration remain unqualified.

## Delivered

- Added owner-only `GET /v1/dashboard/cashflow?shopId=<id>&days=7|30|90` using the existing authenticated shop scope and accepted-event activity projection.
- Added a separate Owner Dashboard tab with UTC daily rows and range totals for staff-declared cash receipts, staff-declared UPI receipts, staff-declared cash refunds, accepted expenses, and the net of only those included records.
- Aggregation is server-side in integer paise with safe-integer overflow rejection. Pending UPI attempts are excluded from incoming money. Duplicate events/expense IDs are already deduplicated by the underlying activity projector.
- The UI labels this as recorded money movement and discloses that it is not profit, a complete cashflow statement, a counted drawer balance, or a bank balance.

## Exclusions and limits

Only accepted server events are included. Cash and UPI receipts are staff-declared; no payment-provider verification is asserted. Pending UPI, customer credit and repayments, supplier purchases/payments, cash-in/out, opening/closing balances, offline records and unsynchronized activity are excluded. Expenses are summarized regardless of tender method, so the result cannot be interpreted as physical cash-drawer movement. Dates use UTC server receipt dates, not a shop-local business day.

## Verification

- Backend unit/API contract suite: 17 passed, 0 failed, 4 PostgreSQL-dependent tests skipped because no `DATABASE_URL` was configured.
- New tests cover calculation, exclusions (including pending UPI), unsafe aggregate rejection, owner-only access denial, authenticated route response, and owner-page asset contract.
- `node --check` passed for `owner-web/app.js`, `backend/src/dashboard.mjs`, and `backend/src/http.mjs`; `git diff --check` passed.
- At the initial local-only run, four PostgreSQL-dependent tests were skipped because no database URL was configured; this has since been superseded by the database-backed qualification below.
- No Android sync-to-server test, browser visual review, or production deployment was performed.

## Database-backed route qualification — 20 November 2026

The full backend suite was rerun against a fresh isolated PostgreSQL 17 cluster using a restricted runtime login with the repository's `chai_tapri_api` grants; the superuser connection was used only for the test cleanup/admin pool. Result: **21/21 passed, 0 failed, 0 skipped**. The authenticated HTTP integration syncs one synthetic order/declared-cash entry and one synthetic expense, then requests `/v1/dashboard/cashflow` and asserts shop/revision scope, ₹20.00 declared cash, ₹250.50 expense outflow, and −₹230.50 net recorded movement. It also verifies wrong-shop and revoked-session denial. The output is at `../test-evidence/2026-11-20/ow04-postgres-tests.log`; the temporary database was stopped after the run. This qualifies the API path on the local synthetic PostgreSQL setup only, not deployment or browser rendering.

**Next:** run the backend integration suite against an isolated synthetic PostgreSQL database; then visually review the owner dashboard only through the authorized browser path.

## Follow-up - 24 November 2026: supplier settlements

OW-04 now includes accepted supplier settlements in daily and total recorded outflows. The projection deduplicates stable payment IDs, includes only `staff_declared` records, uses safe integer paise aggregation, and labels supplier settlements as unverified declarations. They are not categorized as purchase expense or cost of goods sold. Supplier returns and receipts remain excluded from cash movement because the current contract does not record a cash refund or payable timing for those events.

Verification: backend Node suite 26 passed / 4 PostgreSQL-dependent skipped; prototype suite 52/52 passed; JavaScript syntax checks and `git diff --check` passed. Tests cover deduplication, cashflow totals, supplier settlement aggregation, and overflow rejection. The previous PostgreSQL-backed cashflow integration report applies to its earlier source revision; this new supplier-settlement branch has not yet been exercised against PostgreSQL or Android sync E2E. Browser visual review remains pending.

## PostgreSQL end-to-end qualification - 24 November 2026

A fresh disposable PostgreSQL 17 container was used with migrations applied as administrator and a separate restricted runtime login for the application tests. The complete backend suite passed **30/30, 0 failed, 0 skipped**. The authenticated HTTP integration posted a synthetic accepted projection containing a supplier settlement, then verified that OW-04 returns 1,000 paise as `recordedSupplierPaymentsOutPaise`, includes it in `recordedOutflowPaise` and `netRecordedMovementPaise`, and labels the settlement as not bank-verified. Exact retry remained idempotent through the existing event journal. Evidence: `../test-evidence/2026-11-24/ow04-supplier-cashflow/postgres-backend-suite.log`.

The app remains a partial owner projection. This local synthetic PostgreSQL qualification is not deployment, full accounting, browser visual review, or payment-provider verification.
