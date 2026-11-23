# OW-12 Accepted Event History

**Date:** 22 November 2026  
**Status:** Implemented and tested as a privacy-limited owner view; not a complete employee audit trail.

## Delivered

- Added authenticated owner-only `GET /v1/dashboard/audit?shopId=...&limit=...`.
- Enforced shop identity and row-level security using the existing authenticated owner/shop context.
- Bounded page size to 1–100 events and returns newest accepted events first.
- Returns server revision, event ID, event type, base revision, server receipt time, and counts of allow-listed record categories.
- Never returns raw event envelopes, amounts, product/customer identifiers, device IDs, or employee identity.
- Added the Owner dashboard's Accepted Event History tab with bounded history selection, an empty state, and explicit scope/limitation disclosures. All dynamic content uses `textContent`.
- Kept the dashboard's existing content security policy and owner login/session flow.

## Verification

- PostgreSQL-backed backend suite: **24/24 passed, 0 failed, 0 skipped**, using a fresh isolated PostgreSQL 17 cluster, restricted runtime role, and separate test administrator connection.
- Tests cover ordering/limit, privacy minimization, owner-role denial, authenticated route behavior, invalid limits, and UI wiring.
- `node --check` passed for backend and owner-web JavaScript.
- No browser visual review was performed; CAT-046's authorization boundary remains in effect.

## Limits

The view summarizes server-accepted synchronization envelopes. The current contract lacks staff actor attribution and complete audit actions, so the UI explicitly does not present this as employee accountability or proof of business activity. It excludes offline and unsynchronized records. OW-12 remains partial pending a richer, reviewed audit event contract and authorized visual/usability qualification.

## Follow-up - 24 November 2026

Expanded metadata-only change counts to include accepted purchase receipts, supplier settlements, purchase returns, customer credits, repayments, and expenses. The owner table now labels that combined count clearly. No payload values, IDs, or amounts are returned. The focused dashboard fixture test asserts all added counts and continues checking that customer IDs and amounts are absent.

Current verification: backend Node suite 26 passed / 4 PostgreSQL-dependent skipped; prototype suite 52/52 passed; owner JavaScript syntax check passed. The PostgreSQL-dependent tests were skipped because this run had no configured test database. Earlier PostgreSQL integration evidence above remains historical evidence for its tested source revision; no fresh PostgreSQL route qualification is claimed for this change. Browser visual review remains pending.
