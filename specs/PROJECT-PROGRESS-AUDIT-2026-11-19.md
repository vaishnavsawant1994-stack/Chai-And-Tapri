# Chai And Tapri OS — Current Progress Audit

## Superseding emulator regression and customer repayment UI — 19 November 2026

After a normal emulator restart cleared the guest System UI ANR, the current-source API 37 instrumentation suite passed 90/90 with no failures, errors or skips. The earlier broad run (20/90 failures) is retained as diagnostic evidence and superseded by later clean runs. The customer credit screen test drives the repayment dialog, submits a synthetic ₹5 cash repayment, and verifies the updated ₹15 balance plus a separate staff-declared ledger event. The CT-22 detail test verifies a newest-first dated account timeline after reopening encrypted storage. Refreshed latest checks: Node 52/52; Kotlin JVM 59/59; API 37 instrumentation 90/90; debug and instrumentation APK build tasks PASS; lint 0 errors / 161 warnings. The six-wave product and CAT-051B destructive/security, physical-device, field, browser-review and production gates remain open.

## Superseding customer-credit slice verification — 19 November 2026

CT-23 and CT-22 now show age context for the oldest still-open credit order using its timestamp and device clock, with explicit wording that no due date is recorded. Focused screen tests verify the ledger repayment interaction and customer-detail wording. Current-source results are Node 52/52, Kotlin JVM 58/58, API 37 instrumentation 90/90, APK tasks passed, lint 0 errors / 161 warnings. An earlier run had 20 failures while System UI ANR dialogs obstructed automation; that run is superseded by the post-restart 90/90 result above. See `CT-21-23-24-CUSTOMER-CREDIT-AGING-REPORT.md` and `CT-22-CUSTOMER-DETAILS-REPORT.md`; evidence is under ignored `../test-evidence/2026-11-19/`. These slices do not finish CT-21–24 or the product.

## Superseding current-source verification — 19 November 2026

The current-source regression now includes the CT-32 owner shop-profile settings UI test. Prototype Node passed 52/52; backend tests passed 20/20 against a temporary isolated PostgreSQL 17 cluster; Kotlin JVM passed 56/56; API 37 instrumentation passed 90/90; APK tasks passed; lint reported 0 errors / 161 warnings. Total: 218 passed, 0 failed across the four distinct test suites.

An earlier full instrumentation attempt on 19 November had one receivable-projection assertion failure in `liveOrdersUdharSelectsSavedCustomerAndSettlesOrderDue`; its focused rerun and the subsequent 89-case suite passed. The assertion diagnostic was improved, but the intermittent first-run failure was not root-caused. A later full run after the CT-32 settings UI addition passed 90/90. Current XML: `../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`; JVM XMLs: `../android/app/build/test-results/testDebugUnitTest/`; lint: `../android/app/build/reports/lint-results-debug.txt`.

CAT-051B's current-source synthetic recovery was subsequently exercised through the actual Android UI on API 37: a test-generated encrypted archive was selected with SAF, authenticated and previewed, replacement was explicitly confirmed, and the app restored into a clean app installation. After creating a synthetic local owner, the app reopened the restored counter and displayed three restored orders. The focused instrumentation test for that exact rich synthetic dataset passed 1/1 and asserts cash, pending UPI, refund, credit, repayment, stock, audit, outbox and a different local key in an isolated restored store. Screenshot: `../test-evidence/2026-11-19/cat051b-current-ui-orders.png`; UI hierarchy captures are under the same ignored evidence directory. This qualifies the current-source emulator SAF restore workflow for synthetic data; it does not close destructive-failure or security-review gates. Real storage exhaustion, hard process/power interruption, hardware Keystore invalidation, separate-device/person recovery and independent review remain open. PostgreSQL integration ran against a newly initialized loopback-only temporary cluster under `test-evidence/2026-11-19/`; the laptop's existing PostgreSQL service and databases were not used. The cluster was stopped after the run. Test log: `../test-evidence/2026-11-19/backend-postgres-tests.log`.

All previous statements in this file are historical unless explicitly superseded here.

**Date:** 19 November 2026  
**Scope:** Local workspace source and checks executed on this date. Not a production, independent security, or full emulator qualification.

## Current verified checks

| Check | Result |
| --- | --- |
| Prototype Node | 52 passed, 0 failed |
| Backend Node + isolated PostgreSQL integration | 20 passed, 0 failed, 0 skipped |
| Kotlin JVM | 59 passed, 0 failed |
| Debug and instrumentation APK build tasks | Passed |
| Android lint | 0 errors, 161 warnings |
| Changed JavaScript syntax | Passed |
| Android API 37 full instrumentation | 90 passed, 0 failed, 0 skipped (post-restart rerun) |
| Focused customer-credit repayment UI | 1 passed, 0 failed |
| Focused maintenance barrier / financial restore / CT-32 settings | 1/1 each passed |

Evidence logs are under `../test-evidence/2026-11-19/`.

## Completed source slice

CAT-052 now includes an additive optional `expenses` array in the `projection_delta.v1` payload. Android serializes only expense ID, normalized category, integer paise amount, tender method and staff-declared status; notes and drawer-session links are excluded. The server accepts old v1 events without `expenses`, validates exact fields when present, and rejects malformed or private fields. Owner Sales Activity displays totals by UTC server-receipt date and expense category, counting distinct IDs once. The UI describes them as recorded staff-declared expenses, not profit or verified outflows.

See `CAT-052-EXPENSE-PROJECTION-REPORT.md` and `OW-02-DAILY-SALES-ACTIVITY-REPORT.md`.

## Recovery qualification status

The source contains the SAF export/restore UI, maintenance barrier, encrypted staging and restore journal. On current source, a test-generated rich synthetic encrypted backup was selected via the SAF picker on API 37, authenticated, previewed, explicitly approved for replacement and restored into a clean app installation. The focused current-source financial restore instrumentation passed 1/1; after creating a synthetic local owner, the app reopened and displayed all three restored orders. The instrumentation assertions also cover cash, pending UPI, partial refund, credit, repayment, stock, audit, outbox, idempotency and new local-key material in the isolated restore store. Evidence screenshot: `../test-evidence/2026-11-19/cat051b-current-ui-orders.png`; UI hierarchy captures and the generated archive are in ignored `../test-evidence/2026-11-19/` artifacts. This is synthetic API 37 emulator qualification of the SAF restore UI path.

Still open: real storage exhaustion, hard process kill/power loss and filesystem behavior, hardware-backed Keystore invalidation, second-device and second-person recovery, OEM backup behavior, independent security review, and production security qualification. The current-source UI drill selected and restored a test-generated archive; a new full UI-driven export followed by uninstall/reinstall was not repeated on 19 November.

## Six-wave product position

- **Wave 1 recovery/security:** prototype features exist; recovery and independent security gates remain open.
- **Wave 2 Android shopkeeper:** many connected partial workflows exist, but the full CT-01–CT-32 product and complete day-to-day operation are not qualified.
- **Wave 3 sync:** server enrollment, journal, projections, outbox, session and worker code exist. The complete backend suite passed on an isolated temporary PostgreSQL cluster using its restricted runtime role. Authenticated Android-to-PostgreSQL end-to-end delivery remains unqualified.
- **Wave 4 owner dashboard:** partial overview, product performance, inventory, and sales activity with expense breakdown. Synthetic PostgreSQL integration now covers accepted event projection through the activity endpoint, expense-category totals and idempotent retry. Other dashboard integrations and browser visual review remain pending.
- **Wave 5 selling modes:** quick sale and reviewed single-utterance speech exist; continuous Bluetooth, barcode/QR and real noisy-counter reconciliation are incomplete.
- **Wave 6 release:** physical device/field trials, independent review, deployment, operations and release qualification remain open.

No full-product, cross-device, physical Bluetooth, field, independent security or production readiness claim is made.

## CT-29 Android SAF export runtime — 19 November 2026

On the installed API 37 emulator, created a synthetic local owner, navigated to Daily Reports, opened Android's Create Document picker, saved the CSV to shared Downloads, observed the success message, and pulled the resulting 940-byte file for content inspection. The ledger was empty and all exported amounts were zero; therefore this proves picker/destination/file-write/success-path operation only, not nonzero financial aggregation. It does not qualify cancellation, write failure, provider variation, calendar-day reports, profit, tax export or manual usability. Evidence screenshots and CSV are ignored under `test-evidence/2026-11-19/ct29-*`; see `specs/CT-29-CSV-EXPORT-REPORT.md`.

## Additional backup export evidence - 19 November 2026

A separate manual API 37 emulator session completed the Backup & Recovery UI's encrypted export path using synthetic data: the masked recovery-secret confirmation was accepted, the Android SAF Downloads destination was selected, and the app displayed export success. The generated encrypted archive is preserved only in ignored `test-evidence/2026-11-19/cat051b-ui-export-synthetic-encrypted.ctbackup`. The emulator ADB transport then became offline before restore could be attempted. No app data was cleared and no uninstall was performed. This is export-only evidence for this specific archive; the previously documented synthetic SAF restore is a separate run. A matching restore of this new archive remains unverified.

## CAT-051B current SAF export test - 19 November 2026

Current-source API 37 instrumentation passed 1/1 for the real Backup & Recovery UI export flow. The test drove masked recovery-secret confirmation and Android CreateDocument with Downloads selected; the app displayed success. Host ADB then confirmed and pulled the 245,884-byte synthetic `.ctbackup` file. It is ignored under `test-evidence/2026-11-19/cat051b-current-saf-ui-export-synthetic.ctbackup`. This verifies export only. The exact artifact has not been restored, so clean-install recovery remains open; no production or cross-device claim is made. See the CAT-051B implementation report and focused log `test-evidence/2026-11-19/cat051b-current-saf-ui-export-ui-only.log`.

## CAT-051B superseding UI round-trip - 19 November 2026

Current-source API 37 instrumentation passed 1/1 for a real SAF UI export followed by opening that exported archive through Android's OpenDocument picker, secret verification, authenticated metadata preview, explicit replacement confirmation, and restore. The restored encrypted database retained the synthetic 3,000-paise cash-paid order, one cash collection event, and six units of remaining stock. Evidence: `test-evidence/2026-11-19/cat051b-current-saf-ui-roundtrip-qualification.log` and its Android test XML. The pulled 245,884-byte synthetic artifact and SHA-256 are documented in the CAT-051B implementation report; the artifact remains ignored/uncommitted. This is same-install recovery only; clean-install recovery of this exact archive and CAT-051B security gates remain open.

## CAT-051B clean-install recovery - 19 November 2026

The explicit clean-install API 37 test passed 1/1. With the target app package absent and the verified synthetic archive preserved in Downloads, a fresh debug install restored the selected archive through recovery-only UI, SAF, secret verification, and explicit replacement confirmation. The reopened database retained the expected order, 3,000-paise cash collection, payment ledger event and six remaining stock units; device-local key material was usable after restore. The exact artifact hash and limitations are recorded in the CAT-051B report. This closes the clean-install functional gate only for the tested synthetic dataset; CAT-051B security/physical failure gates and production readiness remain open.

## 19 November 2026 regression qualification note

The focused same-install SAF round trip and clean-install recovery each passed 1/1 on API 37. The clean-install drill is opt-in and now requires explicit archive arguments; default suite execution no longer raises a JUnit assumption failure or modifies local records. Prototype Node passed 52/52, Kotlin JVM passed 59/59, backend tests passed 16 with 4 database-dependent skips, APK assembly passed, and lint reported 0 errors / 161 warnings. The full Android instrumentation run recorded 92 cases and 4 failures: the SAF picker had no accessible tree during suite execution, two existing recovery tests timed out launching MainActivity after 45 seconds, and the unrequested opt-in test was recorded as an assumption failure before its guard was corrected. The full instrumentation suite has not been rerun after that correction; do not claim a green full regression. See `specs/CAT-051B-IMPLEMENTATION-REPORT.md` and ignored logs under `test-evidence/2026-11-19/`.

## Superseding CT-14 cost-estimate foundation — 20 November 2026

The Android catalogue stores an optional owner-entered estimated unit cost and snapshots it on new configured-product order lines. SQLite v25→v26 migration is additive; legacy rows remain cost-unknown. The accepted v1 line allow-list now optionally syncs that snapshot; OW-03 shows latest accepted sale-line coverage while still withholding profit. Latest current-source checks: Kotlin JVM 60/60; focused API 37 instrumentation 1/1; prototype Node 52/52; backend 23/23 on isolated PostgreSQL 17 with a restricted role; APK build and lint tasks passed (0 errors / 161 warnings). The full API 37 suite passed 93/93 before the narrow outbox change and was not rerun afterward. Android-to-server delivery is unqualified. Purchase reconciliation, recipe costing, COGS, and profit calculation remain absent. See `CT-14-PRODUCT-COST-ESTIMATE-REPORT.md` and `OW-03-PROFIT-READINESS-REPORT.md`.
