# OW-10 — Anonymous Shift Session Summaries

**Date:** 24 November 2026  
**Scope:** Android outbox projection, owner-only backend endpoint, and owner web source. This is a partial OW-10 slice.

## Implemented

- Android emits a `staffShifts` projection when a drawer/shift session opens, its expected cash changes while open, or it closes.
- The server accepts only an exact allow-listed shape with an opaque UUID, `staff_shift`/`cash_drawer` kind, device-clock timestamps and integer-paise opening/expected/counted amounts.
- Operator labels, handover labels, handover notes, and local session IDs are not transmitted. Stable UUIDv3 IDs derive from local session IDs.
- Backend replay builds a shop-scoped session state from accepted events. Session identity is immutable; an open session may refresh its expected amount; a closed session cannot be altered or reopened.
- Owner-only `GET /v1/dashboard/shifts?shopId=<uuid>` returns accepted session summaries and evidence limits. The owner UI renders state, declared cash and reported variance, and supports the existing current-view CSV export.

## Evidence and limitations

The projection is client-declared. The device clock is not server-verified, cash totals are not independently reconciled from an authoritative cash ledger, and local staff labels do not authenticate a person. The UI and API explicitly identify these limits. This view is not attendance, payroll, employee performance, or a complete audit trail. Closed session amounts are immutable in the server journal projection; a local post-close correction currently needs a separately designed correction event contract and is not represented by mutating the closed summary.

The `cashClosingReviews` entity remains unsupported and continues to block a combined outbox projection when it changes. Other unsupported entity changes can also prevent sync. No real employee data should be entered into this prototype for testing.

## Verification

- Backend focused unit/HTTP tests: **27 passed, 0 failed, 4 PostgreSQL-dependent tests skipped** because no `DATABASE_URL` was configured.
- Android JVM tests: **73 passed, 0 failed**.
- Debug APK build and Android lint: **passed**, 0 lint errors; warnings remain.
- Connected API 37 `cashDrawerSessionAndReconciliationPersistAndRenderOnScreen`: **1 passed**, including encrypted persistence, open/close shift outbox payload checks and operator-label omission.
- PostgreSQL-backed restricted-login integration: **31/31 backend tests passed, no skips**, including owner summary, open/update/close lifecycle and closed-state mutation rejection.
- Browser visual review: **not performed**; existing CAT-046 authorization gate remains in force.

## Remaining qualification

The broad connected Android run remains failed and should be diagnosed separately. Its 88 completed test cases include four failures (procurement UI expected 1500 but observed 2000; quick-sale split-tender/repayment snapshot reconciliation; first-owner setup passphrase entry; catalogue UI flow), followed by emulator package-service disconnection. The focused shift test passed after correcting two assertions to distinguish shift start/end audit event names from drawer open/close events. Re-run the broad Android suite after stabilizing those existing UI/test failures and emulator reliability. Inspect the web view only through the authorized review path. OW-10 remains partial until authenticated staff identity, shift handover permissions, attendance rules and the broader staff workflow are designed and qualified.
