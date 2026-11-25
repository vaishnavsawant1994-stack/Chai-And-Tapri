# Chai And Tapri OS — Progress Addendum

**Updated:** 25 November 2026

## CAT-051B shop-identity preflight — 25 November 2026

Backup verification now rejects an authenticated snapshot whose shop identity differs from the active business before replacement confirmation. The actual MainActivity verification callback test shows the early failure and confirms the active order remains. Service mismatch rejection, same-shop SAF export/restore, and trusted same-shop key-loss preview/recovery also passed in separate API 37 invocations (1/1 each). Kotlin JVM **96/96**, debug/instrumentation APK builds passed, lint 0 errors / 189 warnings. This scoped run did not execute the full Android suite. Cross-device/person recovery, physical storage/power/key invalidation, independent review, and production gates remain open. See [CAT-051B report](CAT-051B-SHOP-IDENTITY-PREFLIGHT-REPORT-2026-11-25.md).

## CT-12 open-order cancellation UI — 25 November 2026

Added synthetic API 37 UI tests for CT-12 cancellation and its manager gate. The manager completes the reasoned and confirmed cancellation; the persisted audit event carries the manager actor, with no payment/refund or stock event. A cashier is redirected from CT-12. Both focused tests passed in separate invocations; Kotlin JVM suite freshly rerun at 96/96; APK assembly passed; lint reported 0 errors / 189 warnings. A first attempt exposed a test-only audit-ID prefix assumption; the final assertion checks the persisted cancellation event and passed. This is not a full-suite or role-matrix qualification. See [CT-12 cancellation report](CT-12-OPEN-ORDER-CANCELLATION-REPORT-2026-11-25.md).

This addendum records work completed after the 24 November progress audit. It does not replace broader product scope or mark the MVP complete.

## CT-11 payment-event date filtering

Sales History can switch its inclusive local date range between order creation time and payment-event record time. The selected basis is labeled, all matches remain read-only, and the filtered set is shared with CSV export. Missing legacy timestamps do not match a selected range; clearing the range still includes undated orders. Kotlin JVM **96/96**; focused API 37 UI test **1/1**; prototype Node **52/52**; APK build tasks pass; lint 0 errors / 189 warnings. Backend and full connected Android suites were not rerun. See [CT-11 report](CT-11-PAYMENT-EVENT-DATE-FILTER-REPORT-2026-11-25.md). This remains local device-clock history, not a complete accounting-period report.

## CT-27 recipient-side handover acknowledgement

The designated enabled local receiver now sees a pending handover in CT-27 and can record their physical cash count. The one-time event is attributed to that local actor, persists through encrypted database reopen and portable encrypted backup restore, and does not alter the sender's closed drawer balance. Count differences remain unresolved and visible. Schema v33 adds an additive v32→v33 migration. The sync projection compares only its allow-listed financial/session fields, so local acknowledgement metadata creates no duplicate shift projection. Verification: Kotlin JVM **95/95**; prototype Node **52/52**; focused API 37 UI/model test **1/1** and synthetic portable restore test **1/1**; APK tasks pass; lint 0 errors / 189 warnings. Full Android/backend suites were not rerun. This is local account/count acknowledgement, not proof of physical transfer or production identity. See [report](CT-27-HANDOVER-ACKNOWLEDGEMENT-REPORT-2026-11-25.md).

## CT-27 local handover recipient binding

Shift handovers can now optionally identify a receiving person by an enabled local staff account. Schema v32 adds a nullable account ID through an additive v31→v32 migration; legacy text labels remain unverified. A mismatched/unknown account is rejected without closing the drawer. Kotlin JVM **94/94**; focused API 37 instrumentation **1/1**; debug and instrumentation APK builds passed; lint 0 errors. Prototype Node **52/52** was run; backend and full Android suites were not rerun. This associates a local directory entry only and does not prove recipient acknowledgement or cash receipt. See [CT-27 report](CT-27-VERIFIED-HANDOVER-RECIPIENT-REPORT-2026-11-25.md).

## CT-09 pre-fulfillment price correction

An owner/manager can adjust an order-line unit price only before any fulfillment or financial event. The UI requires a paise-precise decimal rupee price, reason, review, explicit confirmation, and current revision. It changes the line snapshot only; catalogue price, stock, payment and credit ledgers stay unchanged. The reason is local audit data and is excluded from the outbound projection. Kotlin JVM **86/86**; focused API 37 UI **1/1**; debug and Android-test APK builds passed; lint 0 errors / 187 warnings. Full Android and Node suites were not rerun. Evidence: [report](CT-09-ORDER-PRICE-CORRECTION-REPORT-2026-11-25.md), [focused XML](../test-evidence/2026-11-25/ct09-price-correction-focused-instrumentation.xml). Repricing after partial fulfillment remains unsupported.

## CT-09 cancel unhanded remainder

The Order Details screen now allows managers/owners to cancel only the unhanded remainder of an open or partially handed-over order. It requires a bounded reason, explicit review and confirmation, and a current revision. A focused API 37 UI test passed 1/1 and verified the delivered-sale, stock, payment, and audit state; Kotlin JVM passed **84/84**, APK tasks passed, and lint reported 0 errors / 187 warnings. The full API 37 suite was not rerun after this slice; Node tests were not rerun. Evidence: [CT-09 report](CT-09-CANCEL-UNHANDED-REMAINDER-REPORT-2026-11-25.md) and [focused XML](../test-evidence/2026-11-25/ct09-cancel-remainder-focused-instrumentation.xml). The UI does not refund or restock; use the separate refund flow when appropriate.

## CT-09 correction reason capture

Order Details correction of an unhanded quantity now requires a manager/owner-entered reason, validated before mutation and retained in the audited event/outbox. CT-07 reviewed voice removal requests the same reason and rechecks the proposal identity before commit. Kotlin JVM **83/83**; API 37 connected instrumentation **140/140** (0 failures/errors/skips); debug and Android-test APK builds passed; `lintDebug` passed with 0 errors / 187 warnings. Node was not rerun for this Android-only slice. Evidence: [CT-09 report](CT-09-CORRECTION-REASON-REPORT-2026-11-25.md), [full instrumentation XML](../test-evidence/2026-11-25/ct09-correction-reason-full-instrumentation.xml). CT-09 remains partial; hardware, field, independent security, cross-device recovery, deployment and production gates remain open.

## CT-08 multi-line fulfillment

Live Orders can hand over quantities from several product lines in one validated transaction. Focused API 37 multi-line and single-line UI cases passed 1/1 each; Kotlin JVM 82/82; APK and lint tasks passed; lint reports 186 warnings. Full suite: 139/140, with one Order Details launch timeout that passed focused retest; the broad suite is not green/qualified. See [CT-08 report](CT-08-MULTI-LINE-PARTIAL-HANDOVER-REPORT-2026-11-25.md).

## CT-06 Quick Sale receipt handoff

Quick Sale now shows a receipt sourced from the committed order and payment ledger after checkout and provides a direct route to Order Details. Focused API 37 cash/UPI UI-to-ledger-to-order-details test passed **1/1**; the full default Android regression passed **139/139**; Kotlin JVM **80/80**; APK tasks passed; `lintDebug` passed with 183 warnings. Details: [CT-06 receipt report](CT-06-QUICK-SALE-RECEIPT-REPORT-2026-11-25.md).

## CT-07 transcript review safety

The native counter now invalidates its pending structured command as soon as the recognized/typed transcript changes. The UI tells the operator to propose again. This prevents Apply from committing a command that was parsed from text different from the text currently visible for review.

Evidence: [CT-07 reviewed transcript report](CT-07-REVIEWED-TRANSCRIPT-INVALIDATION-REPORT-2026-11-25.md).

## Current verification

- Kotlin JVM tests: **80 passed, 0 failed**; debug and instrumentation APK build tasks passed via `testDebugUnitTest assembleDebug assembleDebugAndroidTest`.
- Focused API 37 emulator instrumentation: **1/1 passed** for stale proposal invalidation, no mutation on stale Apply, and successful re-proposal of the edited one-item command.
- Lint: **passed**, 183 warnings reported; no lint task failure.
- The focused test uses a synthetic owner and product. Its initial failed run was due to a missing test catalog fixture; the fixture was added and the passing run is the current result.

## Product gates unchanged

CT-07 remains a one-utterance, review-before-apply prototype. Continuous recognition, physical Bluetooth microphone routing, noisy-shop speech qualification, full MVP screen coverage, browser visual verification, independent security review and production deployment remain unqualified. The recent fixture test does not change those statuses.

The broader last recorded test baselines are in [the 24 November audit](PROJECT-PROGRESS-AUDIT-2026-11-24.md) and subsequent CAT-052/Android qualification entries in [PROJECT_PLAN.md](../PROJECT_PLAN.md). They are historical suite results, not rerun by this focused change.

## CT-05 Shop Home daily metrics — 25 November 2026

Implemented and emulator-tested a date-scoped activity section using the existing ledger projection. Kotlin JVM 82/82; APK builds pass; focused API 37 Shop Home instrumentation 1/1; full API 37 connected instrumentation 140/140 (0 failures/errors/skips); lint 0 errors / 186 warnings. Date attribution remains device-local; this does not qualify a full daily close, profit reporting, or the complete screen. Details: [CT-05 report](CT-05-SHOP-HOME-DAILY-METRICS-REPORT-2026-11-25.md).

## CT-15 owner-configured low-stock thresholds

Inventory Overview now shows active products at or below owner-configured thresholds only when physical stock is confirmed. Unknown stock is omitted with a disclosure; the alert is not a purchase recommendation. Focused API 37 instrumentation passed 1/1, including an unchanged persisted snapshot assertion. Kotlin JVM 86/86; debug and instrumentation APK tasks passed; lint 0 errors / 187 warnings. Node and full connected instrumentation were not rerun. See [CT-15 report](CT-15-LOW-STOCK-THRESHOLD-REPORT-2026-11-25.md) and [focused XML](../test-evidence/2026-11-25/ct15-inventory-low-stock-focused-instrumentation.xml). CT-15 remains partial; hardware, security review and production gates remain open.

## CT-16 stock count history CSV export

Added a local audit-history CSV export using Android's SAF picker. It preserves unavailable legacy quantities as unknown, uses explicit device-clock/actor caveats, safely quotes formula-leading text, and does not mutate inventory. The focused API 37 save-picker test passed 1/1; Kotlin JVM 88/88; debug/test APK builds passed; lint 0 errors / 189 warnings. Node suite passed 52/52; the full Android instrumentation suite was attempted but incomplete. The real saved synthetic file is inspected at [CSV evidence](../test-evidence/2026-11-25/ct16-count-history-export/ct16-count-history-synthetic.csv); full scope and limitations are in the [CT-16 report](CT-16-STOCK-COUNT-HISTORY-CSV-REPORT-2026-11-25.md).

## 25 November continuation � CT-13/14 base-unit labels

This supersedes only the product-catalogue unit detail in the audit above. Products now store a validated sell/count base-unit label visible through Quick Sale/cart/receipt, order and stock workflows, and count-history CSV. SQLite v30?v31 migration is additive and defaults legacy products to `units`; migration evidence confirms existing sale and stock records persist. Labels cannot be changed once ledger activity exists. No purchase-unit conversion or product variants are implemented. Verification: Kotlin JVM 89/89; focused API 37 Quick Sale UI test 1/1; focused migration test 1/1; debug and instrumentation APK builds PASS; lint 0 errors/189 warnings. Node suite passed 52/52; the full Android instrumentation suite was attempted but incomplete. Full report/evidence: [CT-13/14 base-unit report](CT-13-14-BASE-UNIT-REPORT-2026-11-25.md), `../test-evidence/2026-11-25/ct13-base-unit/`. CT-13/14 and Wave 2 remain partial; no production/field qualification is implied.

Broad Android regression caveat for this slice: 28 recorded cases passed and one existing cashier sign-in UI test failed with an empty JUnit body before the emulator disappeared from ADB. The suite was incomplete; two AVD restart attempts did not restore a test device. Do not treat this as a full-regression pass. Detailed evidence: ../test-evidence/2026-11-25/ct13-base-unit/full-suite-attempt.txt.

## Subsequent continuation - CT-13/14 base-unit metadata sync - 25 November 2026

This supersedes the local-only statement above. Optional sell/count labels now travel with accepted transaction projection events and appear in owner product-performance and inventory projections. The backend validates labels and locks the first accepted value per product ID; older unlabeled events remain compatible. This is not full product-catalogue synchronization. Latest source verification: backend Node 31 passed / 5 PostgreSQL skips / 0 failed; Kotlin JVM 90/90; prototype Node 52/52; Android debug and instrumentation APK builds passed; lint 0 errors / 189 warnings. Connected Android regression remains failed/incomplete: 28 cases passed before the staff sign-in UI test encountered an Android System UI ANR. Exact failure is documented in [the slice report](CT-13-14-UNIT-SYNC-OWNER-DASHBOARD-REPORT-2026-11-25.md). No live PostgreSQL integration run was included in this slice.

Verification correction for this addendum: after adding the cashier unit-label authorization case, the final backend suite is **32 passed, 0 failed, 5 PostgreSQL-dependent skipped** (37 total). Kotlin JVM remains 90/90; prototype remains 52/52; Android debug and instrumentation APK builds passed; lint 0 errors / 189 warnings. The earlier 31-pass line above is superseded.

PostgreSQL qualification update for CT-13/14: a fresh loopback-only PostgreSQL 17 cluster with migrations 001-004 and a restricted runtime role passed **38/38, 0 failures, 0 skips**. The new integration case verified accepted unit metadata in owner inventory and product projections, duplicate retry, rejected relabel, stale revision conflict and preservation of the accepted value. The database cluster was stopped. Logs: `test-evidence/2026-11-25/cat13-unit-sync-postgres/`. No connected Android UI test was completed for this metadata sync slice; broad regression remains incomplete after the separately recorded System UI ANR.

Owner web UI follow-up - 25 November 2026: the product-performance and inventory tables now show the accepted unit label alongside delivered, refunded, stock and movement quantities. A local Playwright session used only a synthetic owner and synthetic accepted records. The browser rendered `0 cup`, `9 cup`, `10 cup` and `+1 cup`; console reported 0 errors and 0 warnings. Screenshots: `output/playwright/ct13-owner-products.png` and `output/playwright/ct13-owner-inventory.png`. After adding the formatter module/static route, the final backend suite passed 39/39 on disposable restricted-role PostgreSQL 17; no-database backend run is 33 pass/6 PostgreSQL skips. The cluster, API process and browser session were stopped afterward. Mobile layout and Android UI remain unqualified.

## Android full-regression continuation - 25 November 2026

The current serial-pinned API 37 emulator suite passed **145/145**, with 0 failures/errors/skips. Six old Quick Sale cart assertions were updated for configured unit labels. A write-barrier gap was fixed in `CounterModel.apply`: if a backup/recovery barrier blocks the initial persisted-product snapshot, the command now reports that it was not recorded instead of throwing; it does not mutate state. The focused barrier test passed, followed by the complete suite. Kotlin JVM **92/92**, prototype Node **52/52**, backend no-DB tests **33 passed / 6 PostgreSQL-dependent skipped**, debug and Android-test APK builds passed, and lint passed with **0 errors / 189 warnings**. Evidence: [full Android XML](../test-evidence/2026-11-25/android-full-regression-145/full-instrumentation.xml), [report](ANDROID-REGRESSION-QUALIFICATION-2026-11-25.md). `npm test` was unavailable because PowerShell blocked `npm.ps1`; the equivalent package command was run directly with Node and passed. This does not close clean-install recovery, independent security, physical Bluetooth/field, browser authorization, production or overall MVP gates.
## Follow-up - expanded recovery write-barrier qualification - 25 November 2026

A follow-up expanded `maintenanceBarrierRejectsAndRollsBackBusinessWritesThenReleases` to nine paths: command order, Quick Sale, split tender, opening stock, physical count, product addition, purchase-order draft, expense, and cash collection. The first attempt exposed Quick Sale product resolution before its snapshot; early maintenance rejection was added to validation paths that read persisted products, and all snapshot-first mutations use a shared safe rejection. Focused API 37 barrier instrumentation passed 1/1; the complete serial API 37 suite passed **145/145**, 0 failures/errors/skips, in 30m26s. Kotlin JVM 92/92 and APK build tasks passed; `lintDebug` passed with 0 errors / 189 warnings. Prototype Node 52/52; backend Node 33 passed / 6 PostgreSQL-dependent skipped. Updated JUnit: `test-evidence/2026-11-25/android-full-regression-145/full-instrumentation.xml`. Full detail: `specs/ANDROID-REGRESSION-QUALIFICATION-2026-11-25.md`.

Status clarification: a focused synthetic same-emulator clean-app-install recovery test had already passed and is documented in `CAT-051B-IMPLEMENTATION-REPORT.md` and `CAT-051B-SAF-UI-QUALIFICATION-2026-11-24.md`. The ordinary 145-test suite does not run that destructive opt-in case. Cross-device/person recovery, hard power loss, physical storage exhaustion, physical Keystore invalidation and independent security review remain open.
## Final exact-source Android regression - 25 November 2026

After the fail-closed catalogue-read adjustment, the current source passed the complete serial API 37 emulator suite **145/145**, 0 failures/errors/skips, in 31m07s. This supersedes the earlier 30m26s run in this addendum. The nine-operation maintenance-barrier instrumentation assertion is included and passed. Kotlin JVM **92/92**, prototype Node **52/52**, backend no-DB **33 passed / 6 PostgreSQL-dependent skipped**, APK builds and `lintDebug` passed; lint is 0 errors / 189 warnings. Current XML: `test-evidence/2026-11-25/android-full-regression-145/full-instrumentation.xml`; report: `specs/ANDROID-REGRESSION-QUALIFICATION-2026-11-25.md`. The scoped same-emulator synthetic clean-install restore remains established by its separate opt-in test; this full suite does not execute that destructive drill.
## CT-24 repayment tender continuation — 25 November 2026

The Customer Credit and Customer Details screens now offer cash or UPI for customer repayments. Both payment entries are staff-declared; UPI is explicitly not provider-verified and is excluded from physical cash totals. Kotlin JVM 92/92, focused API 37 repayment UI instrumentation 1/1, prototype Node 52/52, backend no-database 33 passed / 6 PostgreSQL-dependent skipped, APK builds pass, lint 0 errors / 189 warnings. The complete connected Android suite was not rerun. This is a focused synthetic emulator workflow, not complete CT-24 or product qualification. See `CT-24-UPI-CUSTOMER-REPAYMENT-REPORT-2026-11-25.md`.

Full regression attempt follow-up: `connectedDebugAndroidTest` did not complete after the repayment change. The AVD remained at `LocalAccessActivity`, launcher ANRs appeared in logcat and no JUnit summary was produced before the run was stopped. The attempt is incomplete; focused repayment instrumentation remains passed.

## CT-24 repayment receipt follow-up — 25 November 2026

Repayment UI now displays the committed amount and method, per-order allocation, remaining customer balance and evidence limits. A pure formatter refuses to produce a receipt when ledger deltas fail reconciliation. Verification: Kotlin JVM 94/94; focused API 37 receipt flow 1/1; Node 52/52; backend no-DB 33 passed / 6 PostgreSQL-dependent skipped; debug/test APK builds and lint passed (0 errors / 189 warnings). The broad Android suite remains incomplete; a focused rerun passed after restarting the disposable AVD. Details: `CT-24-UPI-CUSTOMER-REPAYMENT-REPORT-2026-11-25.md`.

## CT-25 expense actor display — 25 November 2026

Expense history now presents the existing local audit actor ID next to each expense and marks legacy/unattributed rows as not recorded. The disclosure states the actor ID is not independent proof of human identity. Focused API 37 UI test 1/1 passed; Kotlin JVM 94/94; debug/test APK builds and lint passed (0 errors / 189 warnings). Node/backend suites were not rerun for this Android-only display change. Full details: `CT-25-EXPENSE-OPERATOR-ATTRIBUTION-REPORT-2026-11-25.md`.
