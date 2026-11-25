# OW-12 Accepted Event History Pagination

**Date:** 25 November 2026

**Status:** Implemented; PostgreSQL-backed backend suite passed; browser visual qualification pending

## Change

The owner-only `/v1/dashboard/audit` route now supports revision-cursor pagination. Each response returns at most 100 events, newest first, with `hasMore` and `nextBeforeRevision`. The owner page adds a **Load older events** action and appends the next page. The cursor uses server revisions rather than offsets, so new accepted events do not shift already loaded page boundaries.

The existing shop binding, owner authorization, accepted-event metadata summary, and payload privacy limits remain. Raw business payloads and product/customer identifiers are not returned. The UI still labels this as accepted sync-event history; it is not a complete employee audit trail or proof of the underlying business activity.

## Tests and evidence

- Backend no-database suite: **33 passed, 0 failed, 6 PostgreSQL-dependent tests skipped**.
- Backend on a fresh disposable PostgreSQL 17 container with a restricted runtime login: **39 passed, 0 failed, 0 skipped**. This included the real two-page revision-cursor test, verifying newest-first output, page boundary, older cursor, and terminal-page behavior. The loopback-only container was stopped and removed after the run.
- Prototype Node suite: **52/52 passed**.
- `node --check owner-web/app.js`: **PASS**.
- `git diff --check`: **PASS**.
- PostgreSQL log: `test-evidence/2026-11-25/ow12-audit-pagination/backend-postgres-39-of-39.log`.
- Owner-browser rendering was not visually reviewed. CAT-046 authorization remains unchanged.

## Remaining scope

The endpoint exposes only events accepted by the sync service. Offline and rejected commands are absent. Audit records are not cryptographically chained, and this does not qualify the broader employee audit, independent security review, multi-shop controls beyond existing authorization tests, or production deployment.
