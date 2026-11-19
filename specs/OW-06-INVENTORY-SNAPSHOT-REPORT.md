# OW-06 Inventory Snapshot — Implementation Report

**Updated:** 2 November 2026  
**Status:** Implemented and unit/route tested; database-backed integration and browser visual review remain pending.

## Delivered

- Added owner-only `GET /v1/dashboard/inventory?shopId=...` using the same authenticated owner/shop scope and accepted server journal as OW-01 and OW-05.
- The API reports current accepted stock snapshots per product, keeping numeric, explicitly unknown, and unreported quantities separate.
- It returns up to 100 most recent distinct accepted stock movement records, ordered by server revision. Movement IDs are deduplicated.
- The owner web app now has an Inventory Snapshot tab with per-product stock state and movement history.
- The view explicitly warns that unit metadata is not in the accepted sync contract, movement records do not prove a physical count, and the system does not infer sales, waste, theft, or reorder quantities from differences.
- Empty accepted data is represented as an empty state. All table values are rendered with `textContent`.

## Verification

- Backend Node suite: 13 passed, 0 failed, 4 PostgreSQL-dependent integration tests skipped because no test database is configured.
- `node --check` passed for `owner-web/app.js`, `backend/src/dashboard.mjs`, `backend/src/service.mjs`, and `backend/src/http.mjs`.
- Tests cover known/unknown/unreported quantities, latest snapshots, repeated movement IDs, per-product isolation, empty state, owner-role rejection, authenticated route wiring, static dashboard elements, and API path use.
- Captured test log: `../test-evidence/2026-11-02/ow06-backend-node-tests.log`.
- Browser visual review was not performed. No DB-backed owner dashboard result is claimed.
- No database URL is configured. A request to start a fresh temporary PostgreSQL cluster was rejected by the command runner before execution; no cluster was created and the existing PostgreSQL service/database was not modified.

## Data limits

The sync contract currently accepts product stock snapshots and movement deltas, but not the product catalogue, product names, costs, reorder thresholds, supplier preferences, or unit metadata. The page therefore uses product IDs and does not offer low-stock alerts, valuation, profit, cross-product totals, or buy recommendations. It only sees events accepted by the server; unsynced, blocked, and offline records are omitted.

## Next work

Extend the approved sync model with shop-isolated catalogue/unit metadata and configurable reorder thresholds, then add a truthful low-stock view. Run PostgreSQL integration tests against an explicitly isolated disposable database. Browser layout/interaction qualification remains separate from the pending authorization-gated visual review.
