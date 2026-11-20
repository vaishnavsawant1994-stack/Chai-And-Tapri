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
- No PostgreSQL-backed test, Android sync-to-server test, browser visual review, or production deployment was performed.

**Next:** run the backend integration suite against an isolated synthetic PostgreSQL database; then visually review the owner dashboard only through the authorized browser path.
