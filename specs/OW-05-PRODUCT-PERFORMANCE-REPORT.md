# OW-05 Product Performance

**Updated:** 1 November 2026  
**Status:** Implemented and backend-integration-tested; browser visual review pending.

## Delivered

- Added owner-only `GET /v1/dashboard/products?shopId=<uuid>`.
- Reads the session-bound shop's immutable accepted event journal under shop-scoped RLS.
- Builds latest accepted order state by order number; excludes voided and unposted sales.
- Reports per product gross delivered units, returned units, net delivered units, order count, net delivered item value in paise, and latest accepted stock state.
- Keeps product units separate; stock quantity is distinguished as known, explicitly unknown, or not reported.
- Uses checked integer multiplication and safe integer aggregation; overflow returns a structured 422 rather than a rounded figure.
- Added an Owner Dashboard Product Performance section with accessible navigation, refresh, loading/error/empty states and a responsive horizontally scrollable table. Rendering uses DOM nodes and `textContent` for journal-provided values.
- States that only accepted server events are included, that snapshots represent the latest accepted order state, that the item value is not profit, and that offline/blocked/unsynced events are absent.

## Verification

- Backend suite on isolated synthetic PostgreSQL 17: **15/15 passed, 0 failed, 0 skipped**. The integration flow registers a synthetic shop, accepts a synthetic order/stock/payment event, then verifies owner product performance and its totals. Existing wrong-shop denial, RLS, idempotency and logout cases also pass.
- Backend unit/route suite without PostgreSQL: **11 passed, 4 skipped**; the four skips are database-dependent and were subsequently exercised in the isolated integration run.
- `node --check` passed for the changed backend and owner-web JavaScript files.
- Final integration evidence: `../test-evidence/2026-11-01/ow05-product-performance-backend-final.log`; no-database contract suite: `../test-evidence/2026-11-01/ow05-product-performance-contract.log`.
- No browser visual review was performed; the existing CAT-046 authorization boundary remains in force. No production deployment or Android-to-server runtime connection was performed.

## Limits

This is a product-level accepted-journal projection, not an accounting ledger or historical date report. It excludes offline and blocked client data, customer-credit flows blocked by the current sync contract, product names/catalogue metadata, acquisition cost, expenses and profit. Stock reflects the latest accepted stock snapshot, not a live physical count. OW-05 remains partial and Wave 4 remains in progress.
