# Chai And Tapri OS — Progress Addendum

**Updated:** 26 November 2026

## OW-14 branch-management continuation — 26 November 2026

Added a restricted business-owner branch directory/create flow, device revocation and replacement-device registration to the backend and owner web dashboard. Migrations 005 through 007 add business owner principals and branch/device audit events. Branches are created transactionally; owners can revoke a lost branch device and register a replacement. Branch sign-in remains separate and the dashboard never changes shops implicitly. Verification against disposable PostgreSQL 17 with the restricted runtime role: backend **45/45 passed, 0 skipped**. The owner dashboard route/static tests are included. Browser visual review and unrelated Android/prototype suites were not rerun for this slice. See [OW-14 report](OW-14-BRANCH-MANAGEMENT-REPORT-2026-11-26.md). Branch creation is not full multi-shop operations or consolidated reporting.

## Local checkout verification refresh

On the current Git checkout, prototype tests passed **52/52**, backend integration passed **44/44** against disposable PostgreSQL 17 using the restricted runtime role (**0 skipped**), and Kotlin JVM passed **106/106**. Debug and Android-test APK builds passed; lint reports **0 errors / 201 warnings**. A backend run without `DATABASE_URL` reported 37 passed and 7 skipped; it was superseded by the database-backed run. The latest broad API 37 instrumentation evidence remains **156/156**; a subsequent test-only extension for a two-line receipt passed a focused **1/1**, with no later full-suite rerun. Historical failed emulator runs remain troubleshooting history, not the current qualification result. Physical device/audio, field, independent security review, authorized browser visual review, deployed backend and production release are still unqualified.

### CT-10 live split tender update

The new focused Android-to-local-PostgreSQL HTTPS scenario passed **1/1** on API 37. It proved that the accepted business records and owner projections separately report 500 paise staff-declared cash and 1,000 paise staff-declared UPI, with no pending UPI and no duplicate order. See [the CT-10 live E2E report](CT-10-OBSERVED-CASH-UPI-LIVE-E2E-REPORT-2026-11-26.md). This is one synthetic flow, not deployed or production sync qualification.

This addendum records current verification evidence for the six-wave execution plan. It does not replace the complete module/screen inventory in `MASTER-IMPLEMENTATION-TRACKER.md` or imply release readiness.

## CAT-051B — process-death recovery

Two current-source API 37 instrumentation scenarios now kill a separate Android app process during restore cutover and verify a fresh process recovers the encrypted store. Death before replacement activation retains the original; death after activation but before the commit marker rolls back to the original and retains the interrupted replacement. Both focused tests passed 1/1. A test-only Messenger completion handshake was added after the first attempt exposed an unbind race. Kotlin JVM 98/98, debug/test APK build tasks passed, lint 0 errors / 191 warnings. See [CAT-051B crash-recovery report](CAT-051B-REAL-PROCESS-CRASH-RECOVERY-REPORT-2026-11-26.md).

The most recent broad connected Android attempt did not finish: it was interrupted during a long customer-credit UI scenario after the emulator became unresponsive, then ADB reported a broken Package Manager pipe. A subsequent focused recovery run initially failed because the test harness unbound before the recovery callback; this was corrected, and both focused recovery checks then passed. The previously documented 151/151 suite remains a historical result for its earlier source and is not presented as current-source full qualification.

## Follow-up exact-source Android regression attempt — 26 November 2026

Reran the serial-pinned API 37 suite using the booted isolated AVD. The run ended after 17m02s when ADB lost the emulator during cleanup. Its captured partial JUnit result is **32 tests: 18 passed, 14 failed, 0 errors, 0 skipped**. Multiple failures show `Pixel Launcher isn't responding` and activity launch timeouts; the remainder were UI assertions after the emulator had become unresponsive. This is a failed/incomplete suite attempt, not a product qualification. XML/HTML evidence and follow-up are in [the emulator-loss report](ANDROID-REGRESSION-ATTEMPT-2026-11-26-EMULATOR-LOSS.md) and ignored evidence at `../test-evidence/2026-11-26/full-regression-emulator-failure/`. The last full green 151/151 remains historical and is not current-source evidence.

## CAT-052 backend integration follow-up — 26 November 2026

The current backend suite was run against a new disposable PostgreSQL 17 instance with migrations 001–004, runtime grants, and the restricted application role. All **39/39** tests passed with **0 skipped**. Test output: `../test-evidence/2026-11-26/backend-postgres-39-of-39.log`; setup and limits: [CAT-052 report](CAT-052-POSTGRES-CURRENT-SUITE-REPORT-2026-11-26.md). The disposable container is stopped. This does not close CAT-052 production, full financial-ledger, multi-device conflict, or operational-security gaps.

## CT-28 closed cash-session CSV export — 26 November 2026

The Android Daily Closing screen now offers a privacy-minimized SAF CSV for a selected closed drawer session. The report includes integer-paise entries, device-clock times, expected and physically counted cash, and unresolved variance. It excludes names, actor IDs, handover notes, customer data and free-text reasons. Kotlin/JVM 100/100 and APK/lint tasks passed (0 lint errors / 196 warnings). Focused API 37 instrumentation passed 1/1 through CreateDocument save and order/payment/stock non-mutation. Independent read-back of the exported emulator file also passed: 5,000-paise opening float, 1,500-paise staff-declared collections, 200-paise expense, 6,300-paise expected, 6,000-paise counted and -300-paise unresolved variance; no personal identity fields. The later exact-source API 37 regression passed 152/152. Details: [CT-28 export report](CT-28-CASH-SESSION-CSV-EXPORT-REPORT-2026-11-26.md); local ignored evidence is under `../test-evidence/2026-11-26/`.

## Six-wave status

### CT-17 purchase-order purchase-unit input follow-up — 26 November 2026

Purchase-order draft quantities can now be entered in configured purchase units and are converted to persisted base-unit quantities. Audits retain the original pack input; unit conversion locks after a product is used in an order. Kotlin JVM **104/104**, APK builds pass, lint 0 errors / 197 warnings. The test verifies draft-only (no stock/payable) and one-pack partial receipt accounting. No UI instrumentation was run for this sub-slice because the API 37 headless AVDs showed launcher/System UI ANRs in the adjacent Procurement flow. Details: `CT-17-PURCHASE-ORDER-UNIT-CONVERSION-REPORT-2026-11-26.md`. CT-17 remains partial.

### CT-13/14 purchase-unit conversion follow-up — 26 November 2026

Added configurable integer conversion from direct purchase units to product base/count units, with additive encrypted DB migration v33→v34. Kotlin JVM 103/103, APK build tasks PASS, lint 0 errors / 194 warnings. Focused migration/converted receipt/encrypted reopen passed 1/1. The direct Procurement UI test failed/unqualified on two headless API 37 AVDs because Android launcher/System UI became unresponsive before the target screen appeared; see `CT-13-14-PURCHASE-UNIT-CONVERSION-REPORT-2026-11-26.md` and ignored screenshots in `../test-evidence/2026-11-26/ct13-unit-conversion/`. A full exact-source Android run after the final stale-configuration guard is still not qualified (one attempt ended after ADB loss). No full feature/module or production readiness is claimed.

| Wave | Current state | Remaining qualification |
| --- | --- | --- |
| 1 — CAT-051B recovery/security | Encrypted SAF recovery and two real app-process cutover recovery cases have emulator evidence. | Physical power/storage/key-loss, second device/person, OEM transfer and independent security review. |
| 2 — Android CT-01–CT-32 | Connected synthetic workflows exist across counter, stock, procurement, customer credit, expenses, shifts, closing, reports and settings. Exact-source API 37 `connectedDebugAndroidTest` passed 152/152. | Screens and domains remain partial; daily accounting, broad permissions and real shop qualification remain. |
| 3 — CAT-052 backend/sync | Authenticated event API, encrypted outbox, WorkManager retry and selected PostgreSQL-backed E2Es, including app-process restart, are implemented. | Production service/deployment, complete domain contracts, multi-device financial conflict resolution and operational security. |
| 4 — Owner dashboard | Partial owner views, conflict review, deterministic insights and current-view CSV export use accepted server projections. | OW-11/remaining administration and configuration work, visual review, complete accounting and production operations. |
| 5 — selling modes | Quick Sale, reviewed single-utterance voice, barcode/QR validation and explicit reconciliation estimates have connected prototype paths. | Genuine continuous Bluetooth, physical microphone/camera qualification, noisy-counter trials and full operational close. |
| 6 — release qualification | Not complete. | Independent security review, performance, field, deployment, support and release process. |

Current feature evidence is summarized in the [master implementation tracker](MASTER-IMPLEMENTATION-TRACKER.md). Browser visual authorization, physical hardware and production deployment have not been bypassed or claimed.

## CT-10 observed cash + UPI split — 26 November 2026

The Android Payment workflow now records an explicitly confirmed split between cash and an observed UPI receipt in one atomic transaction. Both events are staff-declared; UPI provider verification is not asserted. Kotlin JVM **102/102**, focused API 37 UI test **1/1**, APK tasks PASS, lint **0 errors / 192 warnings**. The focused UI test reopened the encrypted store and confirmed the ₹15 synthetic order settled as ₹5 cash + ₹10 UPI, with no pending/verified event, no stock change and one audit record. See [CT-10 report](CT-10-OBSERVED-CASH-UPI-SPLIT-REPORT-2026-11-26.md); ignored emulator output is under `../test-evidence/2026-11-26/ct10-observed-cash-upi-split/`. This focused slice does not replace the failed/incomplete broad emulator run or close provider, field, security-review, hardware, production, or six-wave gates.
## CT-10 sync projection and baseline correction — 26 November 2026

The observed cash+UPI split now has tests at the local sync-contract boundary: Kotlin verifies that one command's sendable delta includes both staff-declared tenders and the full order paid total; backend contract tests accept the cashier projection only when tender sums reconcile with the order paid delta and reject a one-paise mismatch. Kotlin JVM **102/102**; backend Node **34 passed, 6 PostgreSQL-dependent skipped, 0 failed**. Local backend log: `../backend/test-evidence/2026-11-26/ct10-contract-followup.log`. No live Android-to-PostgreSQL E2E was run for this new flow. The subsequent exact-source API 37 regression completed **152/152**; the earlier 32-test/14-failure emulator-loss attempt remains historical troubleshooting evidence. Full product and all six waves remain in progress.
## Current-source emulator regression correction — 26 November 2026

The earlier same-day full Android attempt failed after emulator/ADB loss (32 tests, 18 passed, 14 failed). A fresh serial-pinned run on the isolated API 37 AVD completed successfully: **152/152 passed, 0 failures, 0 errors, 0 skipped** in **10m08s**. Results: `android/app/build/outputs/androidTest-results/connected/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` and `android/app/build/reports/androidTests/connected/debug/index.html`. The earlier result is retained as troubleshooting history and is superseded as current-source suite status. Emulator tests do not establish physical headset/audio, shop field, independent security, production deployment or release readiness.


## 26 November 2026 follow-up - CAT-052 catalogue to owner projection

Product edits in Android now create sendable catalogue events with purchase-unit conversion metadata. Backend validation and role checks protect the catalogue contract; conversion changes are rejected after accepted product history. Owner product-performance and inventory projections use accepted catalogue names and units while retaining explicit unknown/unreported states. The detailed implementation report is [here](CAT-052-CATALOGUE-OWNER-PROJECTION-REPORT-2026-11-26.md).

Current verification for this follow-up: prototype Node 52/52; backend Node 43/43 passed with 0 skips against disposable PostgreSQL 17; Kotlin JVM 105/105 passed; debug/instrumentation APK builds passed; lint passed with 0 errors and 197 warnings; focused API 37 instrumentation passed 1/1. PostgreSQL backend coverage used synthetic records and a disposable local container. Android-to-server live sync, owner browser visual verification, physical-device, field, production and independent-security qualification are not claimed.

## 26 November 2026 follow-up - shop app catalogue pull

The authenticated sync API now provides a catalogue-only snapshot to owner and staff Android sync sessions. Manual and background sync cache validated snapshots in the encrypted local database; pending local catalogue edits survive, the server revision advances, and new products do not receive invented stock counts. [Implementation report](CAT-052-SHOP-APP-CATALOGUE-PULL-REPORT-2026-11-26.md).

Verification: prototype Node 52/52; backend Node 44/44 on disposable PostgreSQL 17, no skips; Kotlin JVM 106/106; APK builds pass; lint 0 errors / 201 warnings; focused API 37 cache instrumentation 1/1. An Android HTTPS round trip was still unverified when this addendum was first written; the follow-up below supersedes that one gap.

## 26 November 2026 follow-up — live Android HTTPS E2E

The opt-in API 37 emulator test now registered against a disposable PostgreSQL 17 backend over HTTPS, created encrypted product/stock/sale records, exercised a committed event whose acknowledgment was lost, reopened SQLCipher, retried idempotently, fetched the authenticated server catalogue, merged it into the encrypted cache, and verified owner projections. Focused Android E2E **1/1 passed**. Full rerun counts for this continuation: prototype Node **52/52**; backend **44/44** on a restricted runtime login with 0 skips; Kotlin JVM **106/106**; debug/instrumentation APK builds PASS; lint **0 errors / 201 warnings**. See [CAT-052 Android HTTPS E2E report](CAT-052-ANDROID-HTTPS-CATALOGUE-PULL-E2E-REPORT-2026-11-26.md).

The opt-in synthetic E2E is not a deployed backend, multi-device conflict test, full Android regression, independent security review, physical-device/Bluetooth/noisy-shop trial, or production qualification. Full six-wave product work remains active.

## 26 November 2026 follow-up — CAT-051B clean-install recovery

The current-source SAF export and restore tests passed on the dedicated API 37 `Tapri_Recovery_Clean_2026` AVD. A real UI-created encrypted backup persisted in shared Downloads across the test runner uninstalling the app package; a fresh package installation then restored it through the OpenDocument UI. Focused results: rich synthetic backup creation/separate-store restore 1/1; SAF export/authenticated restore 1/1; fresh-install SAF restore 1/1. Assertions covered two orders, cash/UPI-pending distinction, refunds, credit reversal, customer receivable and repayment, stock movements, audit history, command/outbox/ledger counts, and duplicate-command protection. Evidence and boundaries: [CAT-051B report](CAT-051B-CLEAN-INSTALL-RESTORE-REPORT-2026-11-26.md).

An initial wrong-secret test input was rejected before replacement, with the UI confirming active data was unchanged. The corrected matching UI-generated archive passed. This establishes same-AVD uninstall/reinstall recovery only; physical cross-device/person recovery, device power-loss/storage failure, hardware Keystore invalidation, independent security review and production backup remain open. The main development emulator was not cleared.
## CT-13/14 direct purchase-unit receipt UI follow-up — 26 November 2026

The focused direct Procurement UI test `directProcurementScreenConvertsConfiguredCratesToBottleStock` passed **1/1** on the dedicated API 37 `Tapri_Recovery_Clean_2026` AVD. It selected the configured crate product, posted 2 crates × 12 bottles per crate at 125 paise/bottle, reopened encrypted SQLite and verified stock 29, supplier payable 3,000 paise, a 24-bottle receipt movement, and audited conversion detail. This supersedes the earlier UI-only launcher ANR result for this scenario; CT-13/14 and Procurement remain partial. Evidence and limits: [report](CT-13-14-PURCHASE-UNIT-CONVERSION-REPORT-2026-11-26.md).
## CT-14 Product Editor purchase-unit configuration — 26 November 2026

Extended `catalogueUiCreatesConfirmedProductAndQuickSalePostsItsConfiguredPrice` to enter `cup` base unit, `crate` purchase unit, and factor 12 through the actual editor. API 37 instrumentation passed **1/1**. Reopened encrypted catalogue data held all three values; after opening stock and editing aliases/favourite, Quick Sale showed `1 cup × E2E Lemon Chai` and committed the configured paise price. This qualifies the focused product-setup/edit/sell sequence, not the full CT-14 module. JUnit is `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Clean_2026(AVD) - 17.xml`; implementation/test scope is in [CT-13/14 report](CT-13-14-PURCHASE-UNIT-CONVERSION-REPORT-2026-11-26.md).

## CT-13/14 + CT-17 connected product-to-sale workflow — 26 November 2026

The same product UI test now covers one connected synthetic path: configure a confirmed-price cup product with 12 cups per crate, confirm opening stock, save aliases/favourite, create a supplier, receive two crates in the Procurement screen, and sell one cup in Quick Sale. Current-source API 37 focused instrumentation passed **1/1** on the dedicated recovery AVD; encrypted-store checks confirmed 28 cups remaining, ₹30.00 payable, ₹12.50 sale, staff-declared cash, receipt/sale movements and conversion audit. JUnit: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Clean_2026(AVD) - 17.xml` (`2026-11-26T01:21:43Z`). The report documents the initial test-harness failures and final successful rerun. No full-suite rerun was performed for this test-only extension, and the catalogue/procurement modules remain partial.

## CT-17 purchase-order conversion and partial receipt UI — 26 November 2026

The native purchase-order UI test now uses a configured `cup`/`crate` product and passed **1/1** on API 37. Four crates convert to 48 cups in the saved draft and audit; the draft leaves stock/payables unchanged. The sent order receives 2 then 46 cups, with expected stock/payable after each receipt, a rejected zero payment, a staff-declared cash settlement, and a one-cup return. Final encrypted state and audit are checked. JUnit timestamp: `2026-11-26T01:30:11Z`; see [CT-17 report](CT-17-PURCHASE-ORDER-UNIT-CONVERSION-REPORT-2026-11-26.md). This is focused UI qualification; supplier messaging, whole-module operation and the full current-source suite remain outside this test.

## Current-source six-wave verification checkpoint — 26 November 2026

After connecting Product Editor, supplier setup, purchase-unit receipt and Quick Sale, plus qualifying CT-17 purchase-order pack input/partial receipts, the exact-source API 37 instrumentation suite passed **156/156** in 16m23s. Three stale test expectations from an initial full-run attempt were updated; all three affected tests passed focused reruns, then the complete suite passed. Prototype Node **52/52**; backend Node **44/44** on disposable PostgreSQL 17 with migrations 001–004 and restricted runtime role; Kotlin JVM **106/106**; debug/instrumentation APK builds **PASS**; lint **0 errors / 201 warnings**. The PostgreSQL container was stopped and removed. JUnit: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Clean_2026(AVD) - 17.xml`.

This update supersedes older Android full-suite counts for this source only. It does not complete all six waves: recovery has physical/interruption and independent-review gates; Android modules/screens remain partial; sync is a local prototype, not a deployed service; continuous Bluetooth, physical/noisy-shop trials, owner-dashboard visual review and release qualification remain open.

## CT-13/14 multi-product receipt UI follow-up — 26 November 2026

The direct receipt UI test now covers a two-line batch with distinct conversions in one purchase: 2 crates × 12 bottles and 3 boxes × 6 packs. The focused API 37 test passed **1/1**. It reopened encrypted storage and verified both base-unit stock balances, combined payable of 6,600 paise, two receipt movements, one two-line receipt, and one audit event containing both conversion inputs. This is the latest focused UI evidence for direct receipt; the broader 156/156 regression predates this test-only extension. The module remains partial.

## Wave 6 CI baseline — 26 November 2026

Added `.github/workflows/ci.yml` for prototype Node, backend/PostgreSQL integration, Kotlin/JVM, debug and instrumentation APK assembly, and lint. Local equivalents passed: Node 52/52; backend/PostgreSQL 44/44 with no skips using a restricted runtime role; Kotlin/JVM 106/106; APK tasks pass; lint 0 errors/201 warnings. GitHub Actions run [45743870636](https://github.com/vaishnavsawant1994-stack/Chai-And-Tapri/actions/runs/45743870636) passed all jobs: prototype 52/52, backend 44/44, and Android JVM/build/lint tasks. The hosted Android build uses compile SDK 36; the local default remains API 37. Emulator instrumentation is not in this workflow. Full detail and boundaries: [Wave 6 CI report](WAVE-6-GITHUB-CI-REPORT-2026-11-26.md).
