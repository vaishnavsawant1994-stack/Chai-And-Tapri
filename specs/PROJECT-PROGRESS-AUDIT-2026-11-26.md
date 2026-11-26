# Chai And Tapri OS — Progress Addendum

**Updated:** 26 November 2026

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
