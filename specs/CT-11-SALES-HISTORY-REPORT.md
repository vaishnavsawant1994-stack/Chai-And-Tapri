# CT-11 Sales History — Implementation Report

**Date:** 29 October 2026  
**Status:** Partial local history screen; emulator build and empty-state UI checked.

## Implemented

- Reads order records and payment events from the existing encrypted local database.
- Shows line items, sale, paid, credit, refunded and due amounts, fulfillment/payment states, order revision, cancellation reason and payment event kind/status.
- Empty state states that no orders are recorded on this device.
- The screen explicitly states that date filters are unavailable because reliable event timestamp access has not yet been added to the UI contract.
- Navigation is available from the counter.

## Verification

- Android build, JVM test, Android instrumentation and lint task run PASS (latest run: 23 Kotlin JVM tests, 12 Android instrumentation tests; lint 0 errors / 54 warnings).
- Manually opened on API 37; empty-state hierarchy and screenshot: `test-evidence/2026-10-29/ct11-sales-history.xml` and `.png`.

## Limitations

This is a local prototype list, not a date-filtered daily report or accounting statement. It has no search, export, pagination, timeline timestamps, role authorization or independent detail/refund workflow. The current manual UI evidence is an empty synthetic test installation; populated UI behavior is supported by the existing transaction and persistence tests but was not manually exercised in this run.

## Superseding CT-11 to CT-12 connected-flow verification — 31 October 2026

Added an API 37 instrumentation journey from the Sales History order action into CT-12, then returned one delivered item with an explicit reason and staff-declared cash refund confirmation. The test waits for the updated balance to be rendered, then reopens encrypted persistence and verifies the refund amount, unchanged collected cash, zero due, refunded item quantity, append-only payment and audit events, and no stock restoration without a returnability policy. This is synthetic UI-driven test evidence; it does not replace a manual usability review or cover all correction/allocation paths. The full current-source Gradle run passed 70/70 Android instrumentation tests, 40/40 Kotlin JVM tests, debug and instrumentation APK tasks, and lint (0 errors / 139 warnings). Node tests passed 49/49; backend tests passed 5 with 4 PostgreSQL integration tests skipped because no database was configured. Preserved evidence: `test-evidence/2026-10-31/ct11-ct12-full-instrumentation.xml` and `test-evidence/2026-10-31/ct11-ct12-regression.md`.
