# Chai And Tapri OS — Current Progress Audit

## Latest continuation — OW-02 Daily Sales Activity (2 November 2026)

Owner-only daily sales activity API and owner-web view are implemented at source level. Current backend Node suite: **15 passed / 0 failed / 4 PostgreSQL-dependent tests skipped**. The period uses server-received UTC dates and accepted synchronized journal events only. Tender values are staff-declared; pending-UPI figures are attempts started in range; delivered item-value change is not profit. PostgreSQL-backed integration and browser visual review remain pending. Evidence: `../test-evidence/2026-11-02/ow02-backend-node-tests.log`; details: `OW-02-DAILY-SALES-ACTIVITY-REPORT.md`.

The temporary PostgreSQL cluster command was rejected by command execution policy before execution. No new cluster was created and no existing database was changed. This limits DB-backed testing; it does not prevent source-level or fixture-based progress.

**Date:** 2 November 2026  
**Scope:** Current local source and executed tests. This is a development status audit, not production/security certification.

## Current verification

| Check | Result |
|---|---:|
| Prototype Node | 52 passed / 0 failed |
| Kotlin JVM | 56 passed / 0 failed |
| Android API 37 instrumentation | Latest full 89-case attempt: 87 passed / 2 activity-launch timeouts; both timeout cases later passed focused 1/1 runs |
| Backend | OW-06 current suite: 13 passed / 0 failed; 4 PostgreSQL-dependent tests skipped without a configured test DB |
| Debug and instrumentation APK builds | Passed on latest full attempt before instrumentation failure |
| Android lint | 0 errors / 161 warnings |

## Latest continuation — OW-06 Inventory Snapshot

Added an authenticated owner-only inventory endpoint and dashboard panel for latest accepted per-product stock state and recent distinct movement records. Known, unknown and unreported quantities remain distinct; product units are not aggregated; the UI does not infer low stock, reorder amounts, losses or profit. Current backend Node tests: 13 passed / 0 failed, 4 PostgreSQL-dependent integration cases skipped. Syntax checks passed for the modified frontend and server JavaScript. Database-backed integration and browser visual review remain pending. Details: `OW-06-INVENTORY-SNAPSHOT-REPORT.md`.

The local PostgreSQL 17 service is running but `DATABASE_URL` and `TEST_ADMIN_DATABASE_URL` are unset. The command runner rejected creation of a fresh temporary test cluster before execution; no database was created or changed. Existing unidentified databases remain untouched.

## Latest continuation — CT-09 open-order item addition

Authenticated Order Details users can add an active, owner-priced catalogue item to an open order through the revision-checked engine. Confirmation states that it is not a handover and records no payment. The focused API 37 test passed and verifies the new line/total/revision, actor audit and absence of payment/stock events. Evidence: `../test-evidence/2026-11-02/ct09-order-item-add-focused.xml`.

Earlier full attempts exposed split cash/Udhar and Quick Sale picker interaction problems; those targeted tests passed after coordinate-tap changes, and the split flow after an added dialog-transition wait. The latest full API 37 attempt executed 89 cases with 87 passing and two 45-second `startActivitySync` timeouts on Device Permissions and Cloud Sync. Both cases subsequently passed focused 1/1 runs; retained XML is in `../test-evidence/2026-11-02/`. The current-source non-connected Gradle command passed Kotlin JVM 56/56, debug and instrumentation APK build tasks, and lint 0 errors / 161 warnings. Node 52/52; backend 11 passed / 4 PostgreSQL-dependent cases skipped. The complete Android instrumentation suite is not green, so do not claim whole-suite qualification. Details: `../test-evidence/2026-11-02/ct09-add-item-android-verification.md`.

Latest current-source total: 207 tests passed across Node 52/52, Kotlin JVM 56/56, Android API 37 88/88, and backend 11 passed; 4 PostgreSQL-dependent backend tests were skipped. APK builds passed; lint reports 0 errors / 161 warnings. The latest full run includes the CT-09 order-line correction interaction and selected owner/staff actor-attribution assertions. Evidence: `../test-evidence/2026-11-02/ct09-order-line-correction-full-instrumentation.xml` and `../test-evidence/2026-11-02/ct09-order-line-correction-full-gradle.log`.

Full Android report: `../test-evidence/2026-11-02/ct03-staff-full-instrumentation.xml`; focused cashier and owner-management UI results: `../test-evidence/2026-11-02/ct03-staff-login-ui-focused.xml` and `../test-evidence/2026-11-02/ct03-staff-management-ui-focused.xml`. Staff-access verification: `../test-evidence/2026-11-02/ct03-staff-verification.md`.

## Latest continuation — transaction actor audit attribution

SQLCipher schema v25 adds nullable `audit_events.actor_id`; the additive v24→v25 migration preserves existing audit history with null actor IDs. Shared transaction commits and direct audited persistence writes now attach the authenticated local actor where available. Successful owner/staff authentication events carry their local actor IDs; failed authentication events intentionally have null actor IDs. Tests verify selected authenticated-owner, staff profile/catalogue, business-command reopen, migration, and historical-row paths. The corrected full API 37 suite passed 87/87. Coverage is selected, not exhaustive; role authorization for all direct mutation APIs, background actor policy, independent security review, and server-side identity remain open. See `../test-evidence/2026-11-02/ct03-actor-attribution-verification.md`.

## This continuation — supplier profile contact

CT-19/20 supplier directory/detail workflows now accept, display, edit and clear an optional business phone number. SQLCipher schema v23 adds the nullable field with a non-destructive v22→v23 migration. Existing suppliers and their purchasing/payable history remain unchanged. The value is kept in encrypted local state and snapshots; audit text stores only whether a contact is present. The latest source adds schema v24 local staff credentials, owner-managed staff accounts, six-digit PIN authentication with persistent five-attempt/60-second lockout, cashier/manager route gates and role-specific counter navigation. The 83-case full emulator suite includes staff account creation/authentication/disable, schema migrations, supplier profile tests and owner sign-in UI; an additional focused test covers cashier staff sign-in and role-filtered menu.

## Overall product state

The six-wave plan, 28 business modules and 52 planned screens remain incomplete. Several Android vertical slices are usable prototypes, but many screens/modules remain partial or unbuilt. Authenticated Android-to-PostgreSQL sync is not end-to-end qualified; continuous Bluetooth voice, barcode selling, full reconciliation, real-shop trials, physical-device qualification and production release remain open. CAT-051 recovery still has low-storage/hard-interruption, physical key-loss, separate-device/person and independent-review gates. Supplier contact data also needs a privacy review.

## Next executable work

Continue Wave 2 vertical slices in dependency order, adding instrumented interaction tests and current-source full regressions. CT-03 now stores actor IDs for shared business-command commits; direct admin/profile/catalogue audit paths are not comprehensively attributed. Idle timeout, PIN reset, server-side identity and cross-device revocation remain open. CAT-051B hard-interruption/storage, cross-device and independent-review gates are also open. Next executable work: rerun the corrected full Android suite, extend attribution to direct persistence/admin paths, then continue remaining CT-01–12 shopkeeper workflows; do not treat operator-entered shift labels as authenticated staff.

## Superseding next action - 2 November 2026

The final 87/87 API 37 instrumentation run passed after direct persistence actor writes and their selected assertions were added. The planned rerun and extension to direct audited writes are complete. Remaining CT-03 tasks are explicit attribution tests for staff account create/disable and successful staff login, actor policy for background work, and comprehensive authorization coverage around direct mutation APIs. Continue the remaining shopkeeper MVP vertical slices after those targeted CT-03 checks.

## Superseding CT-04 qualification note - 2 November 2026

The CT-03 staff-account actor assertion is now verified by a focused 1/1 API 37 run (`ct03-staff-account-actor-attribution-focused.xml`). CT-04's existing optional-permission screen test was restored unchanged after Android denied the instrumentation UID permission to grant RECORD_AUDIO; its current-source focused rerun passed 1/1 (`ct04-permission-screen-current-focused.xml`). Historical manual API 37 evidence covers denial, permanent denial, Settings grant and return. Direct runtime-prompt Allow selection remains pending. Continue with the remaining shopkeeper workflows; do not treat this focused evidence as a full-suite rerun or physical microphone qualification.

## Superseding current-source authorization slice - 2 November 2026

The full API 37 suite was rerun after adding manager-or-owner checks to the direct SQLCipher customer-profile create/update methods. The first focused run exposed a fixture that tried to update a nonexistent profile; the fixture now creates the synthetic profile under the owner before testing cashier rejection. Final focused actor test passed 1/1, and the final full run passed 87/87. Node 52/52, Kotlin JVM 56/56, backend 11 passed / 4 PostgreSQL-dependent skipped, APK builds PASS, lint 0 errors / 161 warnings. The current API 37 XML and Gradle log are `../test-evidence/2026-11-02/ct03-manager-customer-profile-full-instrumentation.xml` and `../test-evidence/2026-11-02/ct03-manager-customer-profile-full-gradle.log`. Authorization remains selected rather than exhaustive; manager-sensitive actions, background actor policy, staff lifecycle and remote identity need further work.

## Superseding CT-09 order correction slice - 2 November 2026

Added a manager/owner Order Details action for removing unhanded quantities, with explicit confirmation, revision checking, SQLCipher-level cashier rejection, and actor-attributed audit history. API 37 instrumentation verifies the actual dialog interaction and confirms the order revision and remaining lines while payment and stock movement histories remain unchanged. The full current-source run passed 88/88. Latest cross-suite status remains Node 52/52, Kotlin JVM 56/56, backend 11 passed / 4 PostgreSQL-dependent skipped; APK builds PASS; lint 0 errors / 161 warnings. Evidence: `../test-evidence/2026-11-02/ct09-order-line-correction-full-instrumentation.xml`, `../test-evidence/2026-11-02/ct09-order-line-correction-full-gradle.log`. Broader edit/correction workflows and full authorization remain incomplete.

## Next executable work - superseding action, 2 November 2026

Continue Wave 2 connected Android functionality beyond this single CT-09 edit path, then exercise CAT-052 against an isolated test PostgreSQL database so the four DB-dependent backend cases no longer skip and Android-to-server delivery can be qualified end to end. The existing PostgreSQL 17 service is running, but no `DATABASE_URL` or `TEST_ADMIN_DATABASE_URL` is configured; a read-only connection attempt using the default local administrator was rejected because no password was supplied. An attempt to create a disposable cluster through the command tool was rejected before execution, so no cluster or database was created. Do not reuse or modify an unidentified existing database. All hardware, independent-review, recovery-interruption and production gates remain open.
