# OW-15 — Owner Report CSV Export

**Date:** 23 November 2026  
**Status:** Implemented; source-level and API static-serving tests passed; browser visual review pending.

## Delivered

The owner dashboard now exposes **Export current report** for the currently selected view: overview, product performance, inventory snapshot, sales activity, recorded money movements, profit readiness, or accepted event history. It builds a download from the last successfully rendered view already held in page memory; it does not refetch or export unsynchronized device data. Each file contains report title, shop label, displayed scope, source classification, UTC load timestamp, visible metric cards, table sections, and evidence/limitation notes.

The export is disabled during a refresh and the prior snapshot is invalidated before a request, so a failed refresh cannot be exported as if it were current. Local sign-out clears the snapshots. The CSV encoder emits a UTF-8 BOM and CRLF records, quotes and escapes fields, preserves Unicode, and prefixes formula-leading text while retaining valid negative numeric values. Empty-state rows are padded to match the table headers. The exporter is served as a same-origin JavaScript module under the existing restrictive CSP.

## Verification

- `node --check owner-web/app.js`: passed.
- `node --check owner-web/report-export.mjs`: passed.
- Owner report CSV test: passed (Unicode, quoting, formula neutralization, negative values, malformed row rejection).
- Owner API static-route test: passed (dashboard script, module response, content type, existing owner access and CSP assertions).
- Backend test command without a configured isolated PostgreSQL test connection: 25 total, 21 passed, 0 failed, 4 skipped database-backed cases.
- No visual browser review or database-backed end-to-end export click test was performed.

## Scope and limitations

CSV contents reflect only the visible owner projection and its evidence labels. They are not a complete accounting ledger, verified payment report, profit statement, or data export for recovery. Owner pages continue to omit offline/blocked events and entity types outside the accepted synchronization model. No password, bearer token, or request headers are included. OW-15 is partial until a browser-level download review and database-backed owner workflow are completed.
