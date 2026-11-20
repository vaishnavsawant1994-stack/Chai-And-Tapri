# OW-03 Profit Readiness — Implementation Report

**Status:** Partial owner-dashboard vertical slice. The API and web source are implemented and database-backed tests pass; browser visual qualification and actual profit calculation remain open.

## Delivered

- Added owner-only `GET /v1/dashboard/profit-readiness?shopId=<id>&days=7|30|90`.
- Added a Profit Readiness tab with server-accepted UTC period, net delivered item-value change, and accepted staff-declared expense totals.
- Added optional sale-line estimated unit cost to the accepted v1 sync allow-list and an owner-only coverage count (`costed sale lines / net-delivered sale lines`) over latest accepted order states. Legacy v1 payloads without cost remain valid. The count spans all accepted history and does not aggregate unlike product units.
- Gross profit and operating profit are returned as `null` with status `not_calculable`; the UI displays “Unavailable” and enumerates missing inputs rather than deriving profit from sale prices.
- The route uses the existing owner-authenticated shop scope and accepted activity projection.

## Why profit is unavailable

Android now syncs optional owner-entered sale-line cost estimates, but purchase receipts remain excluded. The coverage count describes latest accepted sale-line states across all available history, not the selected activity date range. Opening-stock valuation, product cost layers, recipe ingredient costs, sale-date COGS allocation, and complete operating expenses are also absent. Reported expense totals therefore remain a partial declared subset. No profit, margin, or cost-adjusted revenue is claimed.

## Verification

- Backend suite without a database: 19 passed, 0 failed, 4 PostgreSQL-dependent tests skipped.
- Full backend suite against isolated PostgreSQL 17 with the restricted `chai_tapri_api` runtime role: **23/23 passed, 0 failed, 0 skipped**.
- The authenticated HTTP integration syncs a synthetic sale carrying estimated unit cost, verifies line coverage and `null` profit outputs, and checks wrong-shop and revoked-session denial.
- `node --check` passed for backend and owner-web JavaScript; `git diff --check` passed.
- Test output: `../test-evidence/2026-11-20/ow03-postgres-tests.log`. The isolated test cluster was stopped after the run.
- No browser visual review, Android-to-server synchronization, physical-device test, or production deployment was performed.

**Next:** define and review the complete cost contract, including opening inventory valuation, purchase receipt cost layers, purchase returns, recipe consumption, and dated cost allocation to delivered units. Only after cost completeness and authoritative sync are implemented should OW-03 calculate profit.
