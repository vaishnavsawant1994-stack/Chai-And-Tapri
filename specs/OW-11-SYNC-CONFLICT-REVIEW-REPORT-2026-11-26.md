# OW-11 — Owner Sync Conflict Review

**Date:** 26 November 2026  
**Status:** Read-only review slice implemented; browser visual qualification pending.

## Delivered

- Added `GET /v1/dashboard/conflicts`, authenticated through the normal shop-bound session and restricted to owners.
- The query runs under `app.current_shop`, and the existing forced row-level security policy restricts `sync_conflicts` to that shop.
- Cursor-pages at most 100 newest unresolved conflicts with opaque identifiers, expected/current revisions, server receipt time, and counts for allow-listed record families. Stable ordering uses server timestamp then conflict UUID as a tie-breaker.
- Does not return the stored envelope, raw transaction payload, item names, amounts, customer details, or supplier details.
- Added the owner dashboard table and review guidance. It explicitly does not offer automatic merge, replay, or resolution.
- Updated `backend/ops/runtime-grants.sql` to grant `SELECT` for conflict review while retaining the existing insert-only conflict write model.

## Verification

- Fresh isolated PostgreSQL 17 database, migrations 001–004, restricted API login and updated runtime grants.
- `npm.cmd test --prefix backend`: **39 passed, 0 failed, 0 skipped**.
- Integration assertions cover owner route access, stored stale-revision conflict visibility, revision metadata, omitted raw envelope/product identifier, limit rejection, and cashier denial.
- Cursor integration creates two same-shop stale submissions, pages them separately, and verifies there is no duplicate or missing conflict across the cursor boundary.
- `node --check` passed for `backend/src/http.mjs`, `backend/src/service.mjs`, `backend/test/sync-service.test.mjs`, and `owner-web/app.js`.
- `git diff --check` passed.
- Test database and dedicated runtime login were dropped; the disposable PostgreSQL cluster was stopped. No external service was used.

## Limits and follow-up

- Browser automation was unavailable (`playwright` is not installed in the workspace), so the new view has not received runtime interaction, responsive layout, or visual qualification.
- Browser interaction and visual layout remain unqualified; the API and UI implement keyset paging, but no browser automation executed it.
- No decision/reconciliation workflow is implemented. Staff and owner must resolve the stale local state through a separately governed correction path; the server never accepts the rejected event by merely displaying it.
- Existing installations need the updated runtime grant applied. The app role must not receive `UPDATE` or `DELETE` on conflicts through this change.
- This does not complete OW-11 approvals, multi-device conflict resolution, CAT-052 production synchronization, independent security review, dashboard release, or product qualification.
