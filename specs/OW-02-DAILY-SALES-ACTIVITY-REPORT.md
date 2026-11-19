# OW-02 Daily Sales Activity — Implementation Report

**Date:** 2 November 2026; expense projection updated 19 November 2026  
**Status:** API and owner-web source implemented; PostgreSQL-backed integration and browser visual qualification pending.

## Delivered

- Owner-only `GET /v1/dashboard/activity?shopId=<id>&days=7|30|90` endpoint.
- Owner dashboard Sales Activity tab with selectable 7, 30, or 90 UTC calendar days.
- Daily accepted-event count, order-sale starts, net delivered-item value change, staff-declared cash/UPI entries, staff-declared cash refunds, and UPI pending attempts started.
- Server `received_at` timestamps determine buckets. Order state is reconstructed from the accepted journal before the selected date range so the first in-range change is measured against the prior accepted state.
- Duplicate payment-entry IDs are counted once. Invalid range/date inputs are rejected.
- Non-owner and unauthenticated access is denied; values are scoped to the authenticated shop.

## Interpretation and limits

Delivered item value is not profit, cashflow, or a complete sales ledger. Tender entries are staff-declared, not provider-verified. UPI pending amount is attempts started during the selected range, not currently outstanding pending balance. The projection covers accepted synchronized v1 events only; offline/blocked and unsupported entity events are absent. Dates use UTC, not the shop's configured business timezone. No customer identity or customer-credit details are shown.

## Verification

`node --test --test-reporter=spec backend/test/*.test.mjs`: **15 passed, 0 failed, 4 skipped**. The four skips require a configured PostgreSQL test database. Dashboard-focused tests pass. JavaScript syntax checks pass. Evidence log: [`ow02-backend-node-tests.log`](../test-evidence/2026-11-02/ow02-backend-node-tests.log).

PostgreSQL-backed endpoint integration, browser visual review, authorization/security review, and reconciliation against a real shop's local records remain pending. This is not full OW-02 completion or product qualification.

## 19 November 2026 — expense projection continuation

The Android outbox now serializes new expense records using only `expenseId`, `category`, `amountPaise`, `method`, and `status`. User-entered notes and cash-drawer links are deliberately excluded. The backend accepts this optional additive field on `projection_delta.v1`, preserving compatibility with earlier v1 payloads that omit it, and rejects unknown expense keys. The owner activity response now includes expense totals per UTC receipt date and a range/category breakdown with distinct expense IDs counted once. The page labels these as recorded expenses, not verified payments or profit.

Current checks: backend 16 passed / 0 failed / 4 PostgreSQL-dependent skipped; Kotlin JVM 56/56; prototype Node 52/52; APK and instrumentation APK build tasks passed; lint 0 errors / 161 warnings; changed JS syntax checks passed. Evidence: `../test-evidence/2026-11-19/backend-expense-projection-tests.log`, `android-expense-projection-host-tests.log`, and `prototype-current-node.log`.

This does not qualify Android-to-PostgreSQL sync, because the emulator instrumentation run stalled before producing a final result and all four DB integration tests were skipped. The earlier isolated maintenance-barrier test passed, but the current financial-restore attempt did not start its test runner. Browser visual review and shop-local timezone support remain pending.
