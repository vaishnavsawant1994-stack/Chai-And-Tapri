# Chai And Tapri OS — Project Progress Audit

**Date:** 22 November 2026  
**Authority:** Current local source, current test outputs, and synthetic emulator recovery evidence.

## Current verification

| Suite / check | Result |
|---|---:|
| Prototype Node tests | 52/52 passed |
| Kotlin JVM tests | 63/63 passed |
| API 37 instrumentation | 106/106 passed; 0 failures, errors, or skips |
| CAT-051B focused real-UI export | 1/1 passed on an isolated fresh API 37 AVD |
| CAT-051B focused uninstall/reinstall restore | 1/1 passed on the same isolated AVD |
| CAT-052 backend + PostgreSQL 17 | 23/23 passed on a separate temporary cluster with a restricted runtime role; cluster stopped after testing |
| Debug and instrumentation APK builds | Passed |
| Android lint | 0 errors / 172 warnings |

The 106-test Android run used only `Tapri_Recovery_Isolated`; the primary AVD was excluded. It includes CT-08 UI tests for partial item handover and queue search/status filters, CT-29 nonzero SAF export plus picker cancellation, and CT-30 low-stock alert review navigation. The endpoint-dependent live-backend Android class was excluded; backend integration tests were run separately against isolated synthetic PostgreSQL. The qualified run has zero failures, errors, or skips.

## Wave status

| Wave | Status |
|---|---|
| W1 — CAT-051B encrypted recovery | Same-emulator current-source UI export, app uninstall/reinstall, and UI restore now demonstrated with a synthetic multi-order dataset. Still partial: hard power loss, real filesystem exhaustion, hardware Keystore invalidation, second physical device/person, OEM transfer behavior, and independent security review remain open. |
| W2 — Android CT-01–CT-32 | In progress; the screens and connected workflows remain partial. CT-06 cart quantity controls, CT-08 partial item handover plus local queue search/status filters, and rich recovery integrations have current emulator evidence; this is not full screen/product qualification. |
| W3 — CAT-052 sync/backend | Local synthetic PostgreSQL suite passes 23/23. A limited Android SQLCipher-outbox to isolated PostgreSQL retry across database close/reopen is qualified. Process-death recovery during sync, full authoritative accounting, production TLS/operations, and reviewed deployment remain unqualified. |
| W4 — Owner dashboard | Partial owner views exist; remaining OW screens and browser visual/usability qualification are open. |
| W5 — Selling modes | One-tap/cart and scan paths are partial; voice remains reviewed single-utterance. Continuous Bluetooth, physical scanning, and field use remain unqualified. |
| W6 — Release qualification | Not complete. Independent security review, supported-device and shop trials, operational recovery, performance, privacy, deployment, and release gates remain. |

## CT-08 partial item handover - 22 November 2026

Live Orders now offers a review dialog for handing over a selected ready item and quantity. It calls the shared revision-checked `partial_given` command with a unique command ID. The emulator UI test verifies a 1-of-3 handover persists `PARTIALLY_GIVEN`, decrements confirmed stock once, appends one `partial_handover` movement, and leaves `salePosted` false until the order is fully delivered. Focused API 37 test passed 1/1; the full current API 37 suite then passed 102/102 with zero failures, errors, or skips. Kotlin JVM 63/63, Node 52/52, APK tasks PASS, lint 0 errors / 172 warnings. The test XML is the full-suite file listed above. This is one synthetic UI path, not full CT-08 qualification. See [CT-08/10 report](CT-08-10-LIVE-ORDERS-REPORT.md).

## CT-08 Live Orders queue search and filters - 22 November 2026

The local Live Orders queue now filters by order number, item text, fulfillment/payment state, and five queue views: all, needs preparation/handover, ready to hand over, payment due, and UPI pending. Filtering is read-only. A focused API 37 UI test confirmed the Ready filter and item search show the matching order without changing either order or stock. Focused test passed 1/1; the full current API 37 suite passed 103/103, Kotlin JVM 63/63, and Node 52/52; APK tasks passed and lint reported 0 errors / 172 warnings. The full-suite XML is listed above. This is a partial queue interaction qualification; manual usability and complete CT-08 remain open. See [CT-08/10 report](CT-08-10-LIVE-ORDERS-REPORT.md).

## CT-29 nonzero Daily Reports CSV export - 22 November 2026

Added API 37 instrumentation coverage for nonzero CSV export through the Android Storage Access Framework and picker cancellation. Focused export and cancellation tests passed 1/1 each; the qualified full instrumentation suite passed 105/105 with no failures/errors/skips. The saved Downloads CSV was pulled and inspected: ₹35 recorded sales, ₹15 staff-declared cash, ₹20 pending UPI, ₹5 recorded expenses, and one delivered unit for each product. Cancellation returns the explicit result and leaves the full persisted snapshot unchanged. Current regression: Node 52/52, Kotlin JVM 63/63, API 37 instrumentation 105/105 (endpoint-dependent live-backend class excluded), debug and instrumentation APK build tasks PASS, lint 0 errors / 172 warnings. Backend suite remains the previously verified 23/23 isolated PostgreSQL run; it was unchanged and not rerun for this Android-only slice. See [CT-29 report](CT-29-CSV-EXPORT-REPORT.md), the isolated runner `../android/scripts/run-isolated-connected-tests.cmd`, and ignored synthetic evidence `../test-evidence/2026-11-22/ct29-nonzero-export.csv`.

## CT-30 actionable low-stock alert review - 22 November 2026

Business Alerts now provides review navigation from alert cards to the related existing local workflow. The low-stock Review inventory action opens CT-15 Inventory; the focused API 37 test confirms the target screen and verifies the persisted ledger snapshot is unchanged. Focused test passed 1/1; the full serial-pinned API 37 suite passed 106/106 with zero failures/errors/skips. Node 52/52, Kotlin JVM 63/63, APK builds passed, lint 0 errors / 172 warnings. This is selected routing qualification, not full CT-30 or alert usability qualification. The optional backend-only Android class was excluded; backend PostgreSQL 23/23 is the unchanged prior run. See [CT-30 report](CT-30-LOW-STOCK-ALERTS-REPORT.md), ignored [full-suite XML](../test-evidence/2026-11-22/ct30-alert-routing-full-suite.xml), and [test log](../test-evidence/2026-11-22/ct30-alert-routing-full.log).

## CAT-051B recovery evidence

On the isolated API 37 AVD, the application created an encrypted backup through the Android Storage Access Framework CreateDocument picker. The backup file was copied to local test evidence outside app-private storage. The app was uninstalled and reinstalled while the file remained in Downloads, then the clean-install recovery test used OpenDocument, secret entry, authenticated preview, and explicit replacement confirmation.

The restored synthetic records include two orders; declared cash; pending UPI; a partial item refund; customer credit and a repayment; credit reversal; stock movements; audit/payment history; and command/outbox records. Assertions verify financial/stock projections, event-count consistency, duplicate-command rejection after restoration, and that the fresh installation can open the encrypted database with usable local Keystore key material.

Focused test evidence: `../test-evidence/2026-11-22/cat051b-clean-install-rich-restore-ui.log`. Full API 37 result: `../android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`. The encrypted synthetic artifact and build evidence are local ignored files; no shop data was used.

## Product status and remaining scope

The 28 business modules and 52 planned Android/owner screens are not complete. Many Android screens are partial; most owner dashboard views and modules remain unimplemented. The backend is a local prototype. One synthetic Android SQLCipher-outbox retry slice against isolated PostgreSQL is qualified; broad domain synchronization is not. Local encryption and backups have synthetic emulator coverage, not independent security qualification or production support. Physical camera/scanner, microphone/Bluetooth, noisy-shop, field, public deployment, and production-readiness claims are not made.

See [master implementation tracker](MASTER-IMPLEMENTATION-TRACKER.md), [CAT-051B implementation report](CAT-051B-IMPLEMENTATION-REPORT.md), [CAT-052 PostgreSQL integration report](CAT-052-POSTGRESQL-INTEGRATION-REPORT-2026-11-22.md), and [project plan](../PROJECT_PLAN.md).

## Superseding CT-30 route coverage update - 22 November 2026

The new synthetic UI test directly qualifies open-order, pending-UPI, customer-credit, and low-stock review navigation to CT-08, CT-10, CT-23, and CT-15. Each route left the full encrypted snapshot unchanged. Focused test passed 1/1; the serial-pinned isolated API 37 suite passed 107/107 with no failures, errors, or skips. Node 52/52, Kotlin JVM 63/63, debug and instrumentation APK build tasks, and lint passed (0 lint errors / 172 warnings). Only the isolated AVD was used. Supplier, shift, and daily-closing routes remain untested by click-through. No change to Wave status or overall product readiness: CT-30 and all six waves remain partial/incomplete as previously described.
## Superseding CT-30 routing result - 22 November 2026

The alert routing UI test now directly clicks all seven local actions: open order, pending UPI, customer receivable, low stock, supplier payable, open drawer, and cash count discrepancy. Destinations are CT-08, CT-10, CT-23, CT-15, CT-17, CT-27, and CT-28. Every navigation is verified against the full saved snapshot and leaves it unchanged. Focused API 37 test passed 1/1; complete isolated instrumentation suite passed 107/107, zero failures/errors/skips. Node 52/52 and Kotlin JVM 63/63 remain current; APK build and lint tasks passed, lint 0 errors / 172 warnings. These are synthetic emulator results only; CT-30 is still partial because dated overdue/sync alerts, user alert settings, and replenishment automation are absent.
## Superseding CT-28 UI test result - 22 November 2026

The CT-28 closing review acknowledgement is now exercised through the Android UI. A synthetic follow-up note persists, and expected cash, payment history, and stock movements are unchanged by acknowledgement. Focused test 1/1; full serial-pinned isolated API 37 instrumentation 107/107 (0 failures/errors/skips); Node 52/52; Kotlin JVM 63/63; APK build and lint tasks pass (0 lint errors / 172 warnings). This is only one emulator UI path; session-based daily closing and all six delivery waves remain incomplete.