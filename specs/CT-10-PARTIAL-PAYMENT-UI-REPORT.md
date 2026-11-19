# CT-10 — Partial Cash and Udhar Entry Update

**Date:** 29 October 2026  
**Status:** Partial payment UI added; engine behavior is covered by existing tests; dialog entry itself has not been manually exercised in the emulator.

## Delivered

- Live Orders keeps the existing one-action full cash record and adds a separate `Record partial cash` dialog.
- The operator enters a rupee amount, which is converted exactly to integer paise. Empty, malformed, non-positive, fractional-paise, or overflowing amounts do not call the transaction engine.
- The screen identifies cash as staff-declared, not externally verified, and passes the displayed order revision to the existing validated command.
- Udhar entry now accepts an optional partial amount alongside the existing customer account ID; leaving the amount blank records the full eligible balance. It creates a receivable, not a collection.
- Successful entries use the transaction engine's atomic payment/credit ledger and refresh the order list. The engine rejects over-collection and stale revisions.

## Verification and limits

- Full Android build, JVM tests, API 37 instrumentation, and lint passed after the UI code change.
- Existing native/browser transaction tests cover partial collections and partial credit allocation, but no automated UI test currently enters values into these dialogs. Manual emulator interaction for this new partial-entry dialog is still pending.
- UPI remains an unverified pending state; this change does not add provider verification or partial UPI collection.
- This is not a complete payment screen: no receipt, split-tender summary editor, refund workflow, customer search, or provider integration.

## Superseding UI and transaction coverage — cash plus UPI pending — 31 October 2026

The native Payment screen now supports a cash-plus-UPI-pending split for a finished order. Cash is explicitly staff-declared; the UPI amount remains an uncollected pending attempt. A later operator-confirmed follow-up appends cancellation of that pending attempt and records the observed UPI receipt as staff-declared, never provider-verified. API 37 UI automation verifies the complete flow across activity restarts, including ledger linkage, amount reconciliation and no stock re-deduction. Current complete results: Node 51/51, Kotlin JVM 44/44, Android instrumentation 73/73, APK builds PASS, lint 0 errors/141 warnings; backend 5 passed/4 PostgreSQL-dependent skipped without an isolated database. See `CT-10-PAYMENT-FOLLOWUP-REPORT.md` and `../test-evidence/2026-10-31/ct10-cash-upi-instrumentation.xml`. Manual usability and external payment-provider verification remain unqualified.
## Superseding payment UI qualification — 31 October 2026

The Payment screen now also supports directly recording an observed UPI receipt as staff-declared, with amount entry and explicit confirmation. A direct receipt is rejected while an unresolved pending attempt exists. API 37 UI coverage verifies partial direct receipt persistence and no pending/verified entry; the cash-plus-UPI-pending journey remains separately covered. Final full verification: Node 52/52, Kotlin JVM 45/45, Android instrumentation 74/74, APK builds pass, lint 0 errors/141 warnings. Backend 5 passed/4 skipped PostgreSQL integration tests. See `CT-10-PAYMENT-FOLLOWUP-REPORT.md` and `../test-evidence/2026-10-31/ct10-direct-upi-instrumentation.xml`. Manual usability and provider integrations remain open.