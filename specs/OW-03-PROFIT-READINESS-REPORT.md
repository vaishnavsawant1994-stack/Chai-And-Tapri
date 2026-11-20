# OW-03 Profit Readiness — Implementation Report

**Status:** Partial owner-dashboard vertical slice. The API and web source are implemented and database-backed tests pass; browser visual qualification and actual profit calculation remain open.

## Delivered

- Added owner-only `GET /v1/dashboard/profit-readiness?shopId=<id>&days=7|30|90`.
- Added a Profit Readiness tab with server-accepted UTC period, net delivered item-value change, and accepted staff-declared expense totals.
- Gross profit and operating profit are returned as `null` with status `not_calculable`; the UI displays “Unavailable” and enumerates missing inputs rather than deriving profit from sale prices.
- The route uses the existing owner-authenticated shop scope and accepted activity projection.

## Why profit is unavailable

The Android app keeps purchase receipts and estimated line costs locally, but purchases are excluded from the current server sync contract. Opening-stock valuation, product cost layers, recipe ingredient costs, sale-date COGS allocation, and complete operating expenses are also not present in accepted server records. Reported expense totals therefore remain a partial declared subset. No profit, margin, or cost-adjusted revenue is claimed.

## Verification

- Backend unit/API suite without a database: 18 passed, 0 failed, 4 PostgreSQL-dependent tests skipped.
- Full backend suite against isolated PostgreSQL 17 with the restricted `chai_tapri_api` runtime role: **22/22 passed, 0 failed, 0 skipped**.
- The authenticated HTTP integration syncs synthetic sale and expense records, verifies readiness values and `null` profit outputs, and checks wrong-shop and revoked-session denial.
- `node --check` passed for backend and owner-web JavaScript; `git diff --check` passed.
- Test output: `../test-evidence/2026-11-20/ow03-postgres-tests.log`. The isolated test cluster was stopped after the run.
- No browser visual review, Android-to-server synchronization, physical-device test, or production deployment was performed.

**Next:** define and review the cross-device product-cost contract, including opening inventory valuation, purchase receipt cost layers, purchase returns, recipe consumption, and cost allocation to delivered units. Only after cost completeness and authoritative sync are implemented should OW-03 calculate profit.
