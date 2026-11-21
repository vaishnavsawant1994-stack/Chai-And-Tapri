# Chai And Tapri OS Ã¢â‚¬â€ Phase 0 Qualification Report

## Superseding current-source recovery and regression evidence ? 22 November 2026

The current source has passed a complete synthetic SAF export and clean-install restore UI drill on an isolated fresh API 37 AVD. Restore after app uninstall/reinstall verified two orders, cash/UPI-pending states, partial refund and credit reversal, customer repayment, stock, audit/payment/outbox consistency, duplicate-command rejection and usable fresh-install Keystore material. Focused export and restore tests passed 1/1 each. Full API 37 instrumentation is 101/101 (0 failures/errors/skips), Kotlin JVM 63/63, Node 52/52; APK tasks pass; lint 0 errors / 171 warnings; current isolated PostgreSQL backend suite 23/23. See [current project audit](PROJECT-PROGRESS-AUDIT-2026-11-22.md), [CAT-051B report](CAT-051B-IMPLEMENTATION-REPORT.md), and [recovery evidence log](../test-evidence/2026-11-22/cat051b-clean-install-rich-restore-ui.log).

Phase 0 and the six-wave product remain incomplete. CAT-051B still lacks real power-loss/storage-exhaustion tests, hardware key invalidation and second-device/person qualification, plus independent security review. Bluetooth/camera hardware, noisy-shop trials, continuous B2 listening, browser visual review, production sync/deployment and full product release gates remain unqualified.

## Superseding current-source verification — 19 November 2026

Current checks: prototype Node 52/52; backend Node 16 passed / 4 PostgreSQL-dependent skipped; Kotlin JVM 56/56; API 37 Android instrumentation 89/89; debug and instrumentation APK tasks PASS; lint 0 errors / 161 warnings; changed JavaScript syntax PASS. Focused maintenance-barrier and financial-restore tests passed 1/1 each. An earlier complete Android attempt had one receivable-projection assertion failure; the focused rerun and later full run passed, but root cause remains unknown. The complete SAF UI clean-install drill was not repeated. PostgreSQL integration, physical-device Bluetooth/noise, independent security review, browser visual review and production qualification remain open. Detailed current-source record: [19 November audit](PROJECT-PROGRESS-AUDIT-2026-11-19.md).

**Updated:** 29 October 2026  
**Overall:** Partial qualification. Build, Android emulator startup, app launch, the available SQLite instrumentation test, and a small emulator UI flow are verified. Phase 0 is not complete.

## Current-source addendum — 2 November 2026

Current local verification: prototype Node 52/52; Kotlin JVM 56/56; Android API 37 instrumentation 82/82 (0 failures, errors or skips); backend 11 passed and 4 PostgreSQL-dependent tests skipped because no test database was configured; debug/instrumentation APK build passed; lint 0 errors / 155 warnings. Evidence: `../test-evidence/2026-11-02/ct03-current-full-instrumentation.xml`. The current tested scope includes supplier v22→v23 migration/profile editing and synthetic owner sign-in through the protected counter. Phase 0 remains incomplete: full recovery security review and hardware edge cases, authenticated staff permissions, production sync, most screens, physical headset/noisy-shop trials, and release qualification remain open.

## Later 29 October development update

After the CAT-051B synthetic recovery run, the clean full workspace verification was rerun while adding the first CT-01/02 slice: Node **48/48**, Kotlin JVM **23/23**, API 37 Android instrumentation **10/10**, debug/test APKs **PASS**, Android lint **0 errors / 45 warnings**. This is 81 tests across three distinct suites. CT-01 Welcome and the partial CT-02 shop profile were manually opened; profile save and read-after-process-restart were observed. See [CT-01/02 report](CT-01-02-IMPLEMENTATION-REPORT.md), [instrumentation results](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml), and [UI screenshots](../test-evidence/2026-10-29/).

## Environment and emulator

- Windows 11 Home Single Language, build 26200; AMD Ryzen 5 5600H; firmware virtualization enabled.
- With user authorization, Windows Hypervisor Platform was enabled as the only Windows optional feature change. After restart, `HypervisorPresent=True` and `emulator -accel-check` reports `WHPX(10.0.26200) is installed and usable` (status 0).
- Existing `Medium_Phone_API_37.0` AVD booted. ADB reports `emulator-5554 device`; Android 17/API 37; `sys.boot_completed=1`.
- Host free memory was about 623 MiB during initial runtime checks. AVD reported available `/data` space of 8.7 GB. Avoid running memory-heavy tools concurrently during emulator use.

## Build and tests

| Check | Result | Evidence |
|---|---|---|
| Node suite | PASS Ã¢â‚¬â€ 43/43 | Console run 29 Oct 2026 |
| Kotlin JVM suite | PASS Ã¢â‚¬â€ 17/17 | `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml` |
| Clean APK + instrumentation APK build | PASS | `clean assembleDebug assembleDebugAndroidTest testDebugUnitTest --console=plain` |
| Android lint | PASS Ã¢â‚¬â€ 0 errors, 30 warnings | `android/app/build/reports/lint-results-debug.txt` |
| Android instrumentation | PASS Ã¢â‚¬â€ 1/1, 0 failures | `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` |
| APK install | PASS | `installDebug` reported installed on API 37 emulator |
| App launch | PASS | Activity `com.chaiandtapri.counter/.MainActivity` resumed |

The instrumentation test created a representative v1 database, upgraded it non-destructively to v2, verified a legacy order survived, then recreated the SQLite store and verified orders, fulfillment/payment state, stock, audit history, customer credit/repayment, append-only payment events, UPI-pending state, and duplicate command handling. This is one instrumentation test method covering prototype scenarios, not comprehensive database qualification. Separately, installing the v2 app over the live emulator's v1 database and opening it retained synthetic order #12.

Native SQLite hardening added an append-only `payment_entries` ledger for cash declarations, UPI pending attempts, credit-sale receivables, and customer cash repayments. Schema migration v1Ã¢â€ â€™v2 adds this table without dropping existing records. Migration/runtime recovery beyond this single tested version step remains open.

The v1 schema stored only each order's current payment summary, so the v1Ã¢â€ â€™v2 migration preserves those summaries and existing rows but cannot reconstruct detailed historical payment events that were never recorded.

## Emulator UI checks

Verified through emulator interaction and UI hierarchy/screenshots:

- A reviewed demo order was created, marked ready, handed over, and paid with staff-declared cash.
- Order #12 showed `GIVEN / STAFF_DECLARED`, total Ã¢â€šÂ¹20.00, due Ã¢â€šÂ¹0.00.
- After force-stopping and reopening the app, that order and payment state were still present.
- Rejecting a proposed new order left the existing business record unchanged and displayed the rejection confirmation.
- Denying microphone permission left the session in `PermissionDenied` and confirmed typed mode remained available. The Bluetooth permission prompt was also denied; no headset was available or tested.
- Captured logcat had no `FATAL EXCEPTION` entry for the app in the inspected range.

Evidence is stored in `test-evidence/2026-10-29/`, including `counter-home.png`, `counter-order-created.png`, `counter-permission-denied.png`, UI hierarchy XML, and `logcat.txt`.

## Scope and remaining gates

- **CAT-046 browser visual review:** Pending its existing authorization gate; emulator screenshots qualify only the Android app.
- **CAT-047 transaction engine:** Current JavaScript tests pass; native Kotlin remains a limited model and is not connected to the JavaScript engine. Full financial/inventory parity, split tenders, returns/refunds, and broader correction paths are not qualified.
- **CAT-048A parser:** Limited vocabulary and shared parser fixtures are tested; real language accuracy is not established.
- **CAT-048B/C physical speech/noise trials:** Pending real Android microphone/headset devices and representative shop noise.
- **CAT-049A:** Emulator-qualified for current build, launch, and basic app flows; not a general release qualification.
- **CAT-049B/C:** Actual Bluetooth microphone source, calls/disconnection, screen lock, long session, battery, and true continuous recognition remain unqualified. Current Android `SpeechRecognizer` flow is one manually initiated utterance at a time.
- **SQLite:** Emulator restart/idempotency and v1Ã¢â€ â€™v2 additive migration tests passed. Storage is unencrypted, there is no sync worker, and corruption/backup/device-recovery remain unqualified.
- **Production synchronization, security, shop pilot, and field qualification:** Not implemented or not tested.

## Next executable work

1. Fix and test Kotlin/JavaScript transaction-contract gaps with shared behavioral fixtures.
2. Extend versioned SQLite migration, add corruption/recovery tests, and design appropriate encryption/key management.
3. Improve Kotlin parser/model edge-case tests, including partial/invalid state transitions and reconciliation cases.
4. Keep Bluetooth physical and real-shop trials open until suitable hardware and participants are available.

See [CAT-049A status](CAT-049A-ANDROID-BUILD-STATUS.md), [emulator diagnostics](CAT-049-EMULATOR-DIAGNOSTIC.md), and [continuation checkpoint](CAT-049-CONTINUATION-CHECKPOINT.md).

## CAT-050B update Ã¢â‚¬â€ 29 October 2026

This update supersedes the previous CAT-050 status and test totals above. Final runs for this implementation passed Node 48/48, Kotlin JVM 22/22, and Android emulator instrumentation 3/3 with zero failures. Debug and instrumentation APK builds passed; Android lint passed with zero errors and 30 warnings.

CAT-050B adds partial item and amount refunds to the browser and Kotlin prototypes. The allocation order is outstanding customer credit, collected cash (staff-confirmed and labelled staff-declared), then uncollected sale balance. Returned item quantities cannot exceed delivered and not-yet-refunded quantities. Inventory is restored only when the caller explicitly chooses restock and shop policy marks the SKU returnable. Amount-only adjustments never restock inventory. Credit reversal, cash refund, and pending-UPI cancellation are appended as ledger events; UPI attempt cancellation stores a reference to the original attempt.

SQLite schema v6 adds a nullable related-payment-entry reference. The additive migration chain now runs v1Ã¢â€ â€™v5; migration, partial item return/credit reversal restart recovery, idempotent retry, UPI cancellation linkage, and forced-write rollback are exercised in emulator instrumentation. The shared `specs/fixtures/refund-parity.tsv` fixture compares selected financial, receivable, inventory, and payment outcomes across the two separate engines; this does not establish exhaustive parity.

The updated CAT-050 report is [here](CAT-050-NATIVE-TRANSACTION-PARITY.md). CAT-051 implementation requirements are [here](CAT-051-SECURE-OFFLINE-REQUIREMENTS.md). SQLite encryption, key lifecycle, backup/restore, corruption recovery, authenticated synchronization, provider-verified UPI, Bluetooth hardware, real-shop trials, and CAT-046 authorized browser review remain open.

## CAT-051 update Ã¢â‚¬â€ 29 October 2026

CAT-051 implementation now uses SQLCipher-backed encrypted storage, Keystore-wrapped device-local database keys, verified plaintext v1-v6 migration, authenticated portable snapshot header metadata, a separate recovery secret, staged restore, duplicate-import protection, and explicit Android backup exclusions. The final run passed Node 48/48, Kotlin JVM 22/22, and API 37 emulator instrumentation 6/6; APK builds passed and lint reports 0 errors with warnings remaining. Encryption evidence includes cipher-version/integrity checks and a test that Android platform SQLite cannot query the encrypted file.

CAT-051 remains **partial and not production-qualified**. Portable recovery has no user-facing document picker/secret workflow or enforced write pause; cross-device clean-install and second-person recovery drills, low-storage/process-interruption cases, Keystore invalidation, and independent security review remain. See [CAT-051 implementation report](CAT-051-IMPLEMENTATION-REPORT.md) and [requirements/status](CAT-051-SECURE-OFFLINE-REQUIREMENTS.md). Bluetooth hardware, continuous speech, CAT-046 browser review and field trials are still pending.



## CAT-051B update - 29 October 2026

This entry supersedes the earlier CAT-051 statement that recovery UI and write quiescence were absent. CAT-051B adds native SAF document launchers, recovery-secret confirmation/clearing, metadata preflight, replacement confirmation, progress and failure states, a process-wide maintenance barrier, snapshot reconciliation, staged cutover, and startup recovery journal. The recovery screen and dialog were inspected on the API 37 emulator using UI hierarchy evidence (`test-evidence/2026-10-29/cat051b-final-ui-hierarchy.xml`).

Latest actual run: Node **48/48**, Kotlin JVM **23/23**, Android instrumentation **9/9** (API 37 AVD, zero failures), debug and instrumentation APK builds **PASS**, Android lint **0 errors / 45 warnings**. Evidence: [instrumentation XML](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml), [Kotlin JVM XML](../android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml), [lint report](../android/app/build/reports/lint-results-debug.txt), [recovery screen hierarchy](../test-evidence/2026-10-29/cat051b-final-ui-hierarchy.xml), [recovery dialog hierarchy](../test-evidence/2026-10-29/cat051b-recovery-dialog.xml), and [post-build ready state](../test-evidence/2026-10-29/cat051b-app-ready-after-build.png).

CAT-051B remains **partial**. Schema v6 has a generated shop UUID; portable format v3 authenticates it, validates it against the encrypted payload and rejects a mismatch against an active shop with business data. A new empty store adopts the imported identity. On the API 37 AVD, a synthetic encrypted backup was exported through Android's SAF save picker, retained outside app-private storage, and restored after app uninstall/reinstall using the SAF open picker, authenticated preview and explicit replacement confirmation. The rich synthetic dataset includes orders, declared cash, UPI pending, partial refund, customer credit and repayment, stock movements, audit events and outbox entries; its instrumented restore assertions passed, and the restored order/payment view was visible in the app. This is same-emulator clean-install qualification, not cross-device evidence. True low-storage exhaustion and hard process termination/power loss are not demonstrated; selected failures are injected. No second-person recovery drill or independent security review has occurred. Continue synthetic data only. CAT-052 should wait for broader recovery failure qualification. See [CAT-051B report](CAT-051B-IMPLEMENTATION-REPORT.md) and the [recovery evidence](../test-evidence/2026-10-29/).

## Same-day implementation addendum — CT-13/14 catalogue (29 October 2026)

The API 37 emulator catalogue test verifies that unconfirmed starter prices are rejected by the production counter configuration, a shop-confirmed price is used in paise, a configured alias parses the intended quantity, and the catalogue/order survive model/database reopening. Current totals supersede earlier entries: Node **48/48**, Kotlin JVM **23/23**, Android instrumentation **11/11**, total **82**, zero failures. Debug and instrumentation APK builds pass; lint is **0 errors / 48 warnings**. See [CT-13/14 report](CT-13-14-CATALOGUE-REPORT.md) and the connected test XML in Android build outputs. This does not qualify Quick Sale UI, full catalogue operations, recovery completion, Bluetooth hardware, physical shop conditions, or production readiness.

## Same-day execution addendum — native Quick Sale (29 October 2026)

This is the latest verification and supersedes the earlier 11-test addendum: Node **48/48**, Kotlin JVM **23/23**, Android instrumentation **12/12**, **83** total across the three suites, zero failures. Debug and instrumentation APKs build successfully. Lint reports **0 errors / 52 warnings**. The new atomic Quick Sale business operation was instrumented for configured canonical price, cash declaration, UPI pending, customer-linked Udhar, stock deduction, persistence after reopen, and duplicate retry. CT-06 screen was opened on API 37 and the empty catalogue guard was inspected; the screen’s checkout taps were not manually run. See [CT-06 report](CT-06-NATIVE-QUICK-SALE-REPORT.md), [catalogue report](CT-13-14-CATALOGUE-REPORT.md), screenshot and XML in `../test-evidence/2026-10-29/`. This is a prototype workflow, not full CT-06 or production qualification.

A partial CT-08/09/10 Live Orders screen was added after the CT-06/CT-13/14 work: persisted orders and revision-checked ready/handover/cash-declared/UPI-pending/Udhar actions. Its empty state was opened on the emulator; UI actions remain unexercised. Current automated totals remain 83 passing (Node 48, Kotlin JVM 23, Android 12), APK builds pass, lint 0 errors / 52 warnings. See [CT-08/10 report](CT-08-10-LIVE-ORDERS-REPORT.md). This does not complete the order, payment or refund screens.


## Superseding local verification and procurement update â€” 29 October 2026

Latest complete local run: `node --test prototype/*.test.cjs` PASS (48/48); `android/gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest --console=plain` PASS (Kotlin JVM 24/24, Android API 37 instrumentation 17/17, zero failures/errors/skips, both APK build tasks pass, lint 0 errors / 64 warnings). Total: 89 passing tests across three distinct suites. The latest changes add partial CT-17â€“20 supplier/direct-receipt workflows using encrypted SQLite schema v9 and additive v8â†’v9 migration. See [procurement slice](CT-17-20-PROCUREMENT-REPORT.md), [instrumentation XML](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml), [Kotlin JVM XML](../android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml), and [lint report](../android/app/build/reports/lint-results-debug.txt). These tests establish only the listed prototype behavior. UI was exercised by automated activity-launch/render assertions, not a manual usability review. Purchase-order drafts, multi-line/partial receipts, returns, verified supplier tenders, full CT-17â€“20, CAT-051B edge qualification, authenticated sync, most Android screens, owner dashboard, continuous Bluetooth, physical hardware/field testing, independent security review, and production readiness remain incomplete.


## Superseding verification â€” multi-line receipt slice, 29 October 2026

The procurement dialog now supports up to ten active catalogue products in one direct receipt, and the JVM suite validates two-product stock/payable posting. Latest run: Node 48/48, Kotlin JVM 25/25, API 37 instrumentation 17/17, APK builds PASS, lint 0 errors / 68 warnings (90 tests). Multi-line UI submission remains unverified. This is only a partial procurement slice; see [CT-17â€“20 report](CT-17-20-PROCUREMENT-REPORT.md). All other product/security/hardware gates remain as previously documented.


## Superseding verification â€” procurement return slice, 29 October 2026

Latest run: Node 48/48, Kotlin JVM 26/26, API 37 instrumentation 18/18, APK builds PASS, lint 0 errors / 68 warnings (92 total). Schema v10 adds receipt-linked purchase returns with an additive migration from the prior schema; instrumented reopen and UI-render assertions pass. Return accounting reduces available stock and outstanding supplier payable, and preserves audit/idempotency. Aggregate stock is not lot-tracked; return dialog input is not manually UI-qualified. This is prototype scope only. See [CT-17â€“20 report](CT-17-20-PROCUREMENT-REPORT.md).


## Superseding verification â€” CT-25 Expenses, 29 October 2026

Latest full verification: Node 48/48, Kotlin JVM 27/27, API 37 instrumentation 19/19, APK builds PASS, lint 0 errors / 70 warnings. Schema v11 adds an expense ledger through additive migration. Validation, idempotency, rollback, encrypted reopen, audit persistence and expense-screen rendering passed for synthetic test data. Manual emulator form input created a synthetic staff-declared cash rent expense of INR 250.50; it appeared in history and survived force-stop/relaunch (screens: `../test-evidence/2026-10-29/ct25-saved.png`, `ct25-restart.png`). Daily cash reconciliation, production security and deployment are not qualified. Details: [CT-25 report](CT-25-EXPENSES-REPORT.md).


## Latest continuation - CT-26 Cash Register, 29 October 2026

The current verified run is Node 48/48, Kotlin JVM 28/28, API 37 Android instrumentation 20/20 (96 total across separate suites), debug and instrumentation APK builds PASS, and lint 0 errors / 73 warnings. SQLCipher schema v12 adds cash drawer sessions and optional drawer-session references for cash events. Synthetic session arithmetic, overdraw rejection, unresolved variance, encrypted reopen and v8-to-v12 migration passed. The emulator UI was manually used to open a drawer at INR 50.00 and close it with a counted INR 50.00; the screen showed INR 0.00 variance. See [CT-26 report](CT-26-CASH-REGISTER-REPORT.md) and `../test-evidence/2026-10-29/ct26-closed.png`. This does not qualify CT-27 shifts, CT-28 daily closing, physical devices, production accounting, security review, sync or the complete MVP.


## Superseding continuation - CT-28 session closing review, 29 October 2026

Current full verification: Node 48/48, Kotlin JVM 28/28, API 37 Android instrumentation 21/21 (97 total), debug/instrumentation APK builds PASS, lint 0 errors / 74 warnings. SQLCipher schema v13 adds a persisted one-per-session closing-review acknowledgement. The review reports session-attributed cash totals and separately displays current open orders, pending UPI and receivables. A synthetic INR 50.00 session was manually closed, reviewed and acknowledged; after force-stop/relaunch the review and zero variance remained visible. See [CT-28 report](CT-28-DAILY-CLOSING-REPORT.md) and `../test-evidence/2026-10-29/ct28-restart.png`. This remains session-level prototype reporting, not calendar-day accounting or production closing. CT-27, recovery fault gates, sync/backend, owner dashboard, continuous Bluetooth, physical trials, independent security review and production readiness remain open.

## Superseding continuation - CT-27 shift and cash handover, 29 October 2026

Schema v14 adds optional operator-entered shift and handover labels/notes to drawer sessions; the v13-to-v14 migration is additive. A CT-27 screen starts/ends a cash-linked shift with counted amounts and shows only session-linked cash totals. It explicitly says labels are unauthenticated and does not claim all activity is attributed. Latest complete results: Node 48/48, Kotlin JVM 28/28, API 37 instrumentation 22/22 (98 total), APK builds PASS, lint 0 errors / 75 warnings. The emulator UI start/handover was manually exercised and remained visible after force-stop/relaunch. Instrumentation verifies v8-to-v14 migration and backup/restore of labels. This is a partial local workflow, not employee authentication, multiple-register control, comprehensive accountability, calendar-day closing, production security, or field qualification. See [CT-27 report](CT-27-STAFF-SHIFTS-REPORT.md), `../test-evidence/2026-10-29/ct27-restart.png`, and [test report](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml).

## Superseding CAT-051B run — 29 October 2026

Node 48/48, Kotlin JVM 28/28 and Android API 37 instrumentation 23/23 passed. Debug and instrumentation APK builds passed; lint reported 0 errors and 77 warnings. The added synthetic recovery test covers a missing Keystore wrapping key, rejection on local shop-identity mismatch with byte-for-byte preservation, successful restore under a fresh local key, retained old ciphertext, sale/payment restoration and idempotent replay. An import-stage partial-write fault also passed. These are emulator and injected-failure results, not real storage exhaustion, hardware key invalidation or hard process-kill qualification. CAT-051B, independent security review, second-device recovery and production readiness remain open. Details: [CAT-051B implementation report](CAT-051B-IMPLEMENTATION-REPORT.md).


## Superseding CAT-051B verification after recovery UI startup fix — 29 October 2026

The recovery screen now opens without attempting to unlock the local database first, and SQLCipher workspace open/refresh are performed away from the Android UI thread. Emulator screenshots and hierarchy evidence are in `test-evidence/2026-10-29/cat051b-recovery-entry.png/.xml` and `cat051b-main-start.png/.xml`. The API 37 full instrumentation rerun after these changes passed 23/23; Kotlin JVM 28/28, Node 48/48, APK builds PASS, lint 0 errors / 77 warnings. CAT-051B still is not complete: storage exhaustion, hard termination/power loss matrix, hardware key invalidation, second-device and second-person recovery, and independent security review remain pending. See the [detailed report](CAT-051B-IMPLEMENTATION-REPORT.md).

## Final CAT-051B continuation verification — 29 October 2026

The restore completion path also reopens SQLCipher off the UI thread and closes any newly opened store if the activity is destroyed. After this final lifecycle change, the complete verification command `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain` completed successfully: Kotlin JVM 28/28, Android API 37 instrumentation 23/23, APK builds PASS, lint 0 errors / 77 warnings. `node --test prototype/*.test.cjs` passed 48/48. The API 37 suite ran after the final Android source changes. Evidence: `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, `android/app/build/reports/lint-results-debug.txt`, and `test-evidence/2026-10-29/cat051b-key-loss-recovery-run.txt`.

CAT-051B remains open; this run did not perform a new SAF export/restore UI drill. Actual storage exhaustion, abrupt process/power loss across journal transitions, hardware Keystore invalidation, second-device and second-person recovery, and independent security review remain pending. Do not treat the prototype as production-ready.

## Superseding full verification — CT-11 date history and schema v16 — 30 October 2026

Fresh current-tree results: Node 48/48, Kotlin JVM 30/30, API 37 Android instrumentation 34/34, debug/test APK build tasks PASS, lint 0 errors / 104 warnings (112 automated tests). SQLite v16 adds order creation timestamps using an additive migration; migrated orders are kept undated. CT-11 search and date filtering are emulator-tested, but manual date-picker usability and payment-event timestamps remain pending. CAT-051B hard interruption/storage/key-loss and independent review gates, authenticated sync, complete Android/web screens, true continuous Bluetooth, hardware/field testing and production qualification remain open. Detailed CT-11 status and evidence: [CT-11 report](CT-11-ORDER-HISTORY-REPORT.md); current XML and lint evidence under `../android/app/build/`.

## Superseding verification — CT-10 staff-declared UPI follow-up — 30 October 2026

A native Payment screen and matching browser/Kotlin transaction command now resolve a pending UPI attempt only when the operator explicitly records the amount actually received. The engine appends a linked cancellation of the pending attempt and a separate `staff_declared` collection event; it does not mark the collection provider-verified. Amount limits, order revision and idempotency are enforced. Full verification on the current workspace: Node 49/49, Kotlin JVM 31/31, Android API 37 instrumentation 36/36 (116 total), APK build tasks PASS, lint 0 errors / 107 warnings. Evidence is in the Node output and the Android XML/lint files listed in [CT-10 continuation report](CT-10-PAYMENT-FOLLOWUP-REPORT.md). CAT-051B, manual payment-dialog use, full payment UI, authenticated sync, physical Bluetooth, field qualification and production readiness remain open.

## Superseding CT-10 and recovery status — 30 October 2026

The amount-entry/confirm path for the staff-declared UPI follow-up is now exercised end to end in API 37 instrumentation. Latest full run: Node 49/49, Kotlin JVM 31/31, Android instrumentation 37/37; APK tasks PASS, lint 0 errors / 107 warnings. CAT-051B's synthetic SAF export and same-emulator uninstall/reinstall restore already have UI screenshots, archive hash, and post-restore record evidence in `test-evidence/2026-10-30/`; the functional UI gate passes at this scope. Cross-device/second-person recovery, actual low-storage and hard power-loss trials, hardware Keystore invalidation, independent review, and production readiness remain open.

## Superseding verification — CT-22 customer detail workflow — 30 October 2026

CT-22 is now a native screen linked from Customer Directory. It reads the local encrypted account and payment records, shows aggregate credit/repaid/reversed/outstanding amounts, per-order credit links, and payment/repayment history, and offers the existing staff-declared repayment command when a due balance exists. The API 37 test follows the directory action and checks a synthetic account (₹30 credit, ₹10 repaid, ₹20 due). Full current verification: Node 49/49, Kotlin JVM 31/31, Android API 37 instrumentation 38/38 (118 total), APK tasks PASS, lint 0 errors / 107 warnings. CT-22 remains partial: customer profiles/contacts/search and date-aware timeline are not built.

## Superseding verification — CT-21 customer search — 30 October 2026

Customer Directory now filters existing local customer account IDs case-insensitively and links each account to CT-22 details. A synthetic two-account API 37 test verifies filtering; it does not imply full customer CRM or named/contact profiles. Latest full verification: Node 49/49, Kotlin JVM 31/31, Android instrumentation 39/39 (119 total), APK tasks PASS, lint 0 errors / 107 warnings. See `specs/CT-21-CUSTOMER-DIRECTORY-REPORT.md`.

## Superseding CT-21 profile and schema v17 verification — 30 October 2026

CT-21 now creates encrypted customer profiles with account ID, display name, optional consented phone, and search by ID/name. Tests verify audit redaction, duplicate/no-consent rejection, encrypted persistence across business mutations, additive v16→v17 migration, preservation of existing orders/credit, and profile survival in encrypted portable backup restore. The actual add-profile UI was exercised by the API 37 accessibility test. Latest full run: Node 49/49, Kotlin JVM 31/31, Android API 37 instrumentation 42/42 (122 total), APK tasks PASS, lint 0 errors / 108 warnings. This is not a complete CRM. See `specs/CT-21-CUSTOMER-DIRECTORY-REPORT.md`.

## Superseding CT-21 profile maintenance verification — 30 October 2026

CT-21 supports profile editing and contact-consent revocation. Revocation clears the phone from encrypted profile storage; the transaction preserves profile creation time, updates modification time, appends redacted audit/receipt/outbox records, and leaves business balances untouched. API 37 instrumentation covers edit/revoke UI; persistence assertions verify audit redaction and no credit change. Latest complete run: Node 49/49, Kotlin JVM 31/31, API 37 instrumentation 43/43 (123 total), debug and instrumentation APK tasks PASS, lint 0 errors / 109 warnings. Deletion/retention, sync, independent security, physical Bluetooth, field workflows, and production readiness remain open.

## Superseding CT-24 repayment interaction — 30 October 2026

The partial cash repayment dialog is now exercised on API 37 with synthetic records. The UI posts a ₹5.00 staff-declared cash repayment, and a fresh encrypted-store read verifies the receivable decreases from ₹20.00 to ₹15.00 with the repayment event retained separately. Full verification: Node 49/49, Kotlin JVM 31/31, Android instrumentation 43/43, APK tasks PASS, lint 0 errors / 109 warnings. This is one tested workflow, not full CT-24 or product qualification.

## Superseding CT-16 physical count verification — 30 October 2026

CT-16 has a connected multi-product stock-count UI route. API 37 automation enters two synthetic counts including zero through Inventory Overview, then verifies stock, movement history and outbox persistence. A Kotlin JVM test validates all-or-nothing behavior, duplicate protection and invalid-count rejection. No purchase/payable or sale is created by a stock count. Latest complete run: Node 49/49, Kotlin JVM 32/32, API 37 instrumentation 44/44 (125 total), APK builds PASS, lint 0 errors / 110 warnings. This does not qualify variance approval, count history, low-stock alerts, physical-shop counting or the full product.

## Final CT-16 form validation and full test run — 30 October 2026

API 37 coverage now verifies invalid count input causes no write and does not discard the other selected product's value; correcting the entry successfully records the atomic multi-product count. Current complete results: Node 49/49, Kotlin JVM 32/32, API 37 instrumentation 44/44 (125 total), APK build tasks PASS, lint 0 errors / 113 warnings. This is a tested inventory workflow only, not full screen/product qualification.

## Superseding CAT-052 Android outbox payload qualification — 30 October 2026

The SQLite schema is now v18. Supported order, stock and non-customer-linked payment mutations persist a minimized allow-listed `projection_delta.v1` payload. Outbox event UUIDs are separate from native command IDs. Historical rows and unsupported entity mutations remain explicitly blocked rather than reconstructed or silently dropped. Customer-linked credit, repayment and payment events are blocked because current account IDs can include names or phone numbers; no such data is eligible for a future upload until an opaque identity and privacy/consent contract exists. Current verification: Node 49/49, Kotlin JVM 33/33, Android API 37 instrumentation 44/44, `assembleDebug` and `assembleDebugAndroidTest` PASS, `lintDebug` PASS with 0 errors / 113 warnings. Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, and `android/app/build/reports/lint-results-debug.txt`. No Android request was sent. Account/device enrollment, server revision, envelope conversion, protected session credentials, uploader, retries, acknowledgements, conflict UI and server-side financial projections remain unimplemented. CAT-052 and the overall Phase 0/product qualification remain open.

## Superseding CT-06 Quick Sale search/filter UI — 30 October 2026

Quick Sale now filters configured sellable products by name, product ID, category and aliases with case-insensitive matching, and supports category/favourite selection and a clear no-match state. A synthetic API 37 UI test verified each path while leaving the transaction engine and records unchanged. Current app verification: Node 49/49, Kotlin JVM 33/33, Android instrumentation 45/45, APK build tasks PASS, lint 0 errors / 114 warnings. See `specs/CT-06-NATIVE-QUICK-SALE-REPORT.md`. CT-06 is still partial and this does not qualify counter usability or the full Android application.


## CT-30 low-stock threshold implementation — 30 October 2026

SQLCipher schema v19 adds an optional per-product minimum stock threshold; v18→v19 is additive and preserves existing records. Alerts compare the configured threshold only against physically confirmed current stock and do not fabricate quantities or trigger purchases. Model validation, migration preservation, and rendered alert text passed focused tests. Current verification: Kotlin JVM 35/35, Node 49/49, backend payload contract 5/5, APK builds pass, lint 0 errors/114 warnings. The latest complete Android instrumentation attempt passed 46/47; a pre-existing customer-profile revocation UI assertion failed in that run and passed in isolation. This is not a clean full-suite pass. The product-editor threshold dialog was not directly qualified. CAT-052 authenticated Android sync, remaining screens, owner dashboard, hardware/field/security/deployment gates remain open.

## Superseding regression record — 30 October 2026

Corrected mojibake in visible native currency and punctuation strings in MainActivity, QuickSaleActivity and ProductCatalogueActivity. A fresh full instrumentation run reproduced the customer-profile contact-revocation failure. Inspection found that the test's accessibility helper accepted an empty EditText hint before sending the requested clear action. The helper has been corrected; a complete regression run is in progress. Do not update qualification status until the new test XML, process exit code, unit test, APK and lint results are checked. Physical headset/microphone, noisy-counter, field, cross-device, independent security and production gates remain separate and unqualified.


## Final regression result ? 30 October 2026

After correcting native UI currency/punctuation text and fixing the contact-revocation UI test helper, full verification passed: Node 49/49, Kotlin JVM 35/35, Android API 37 instrumentation 47/47, backend projection contract 5/5. Gradle unit tests, APK builds, lint, and emulator instrumentation succeeded. Lint: 0 errors / 114 warnings. Instrumentation result: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` (exit code 0). The threshold editor submission remains without direct UI automation. No production, physical headset, noisy-shop, field, independent security, or cross-device qualification is established.


## Superseding CT-30 UI test ? 30 October 2026

The catalogue threshold editor is now covered end to end on the emulator: edit ? save ? encrypted persistence readback ? low-stock model evaluation. The focused test passed and the full suite passed 48/48. Latest combined verified totals: Android JVM 35/35 and instrumentation 48/48; Node 49/49 and backend payload contract 5/5 from the prior host run. APK build tasks and lint passed, with 0 lint errors / 114 warnings. These results qualify the tested local prototype paths only; cross-device recovery, authenticated sync, security review, physical voice and field use remain unqualified.

## Superseding continuation — CAT-052 durable outbox outcome journal — 30 October 2026

Implemented encrypted SQLite schema v20 with a `server_revision` on sync outbox rows and persistence APIs to read sendable pending events, read the current acknowledged server revision, and record accepted/duplicate/conflict outcomes. Event acknowledgement is atomic with row status and the cursor update; repeated identical acknowledgements are idempotent; conflicting or altered replays are rejected. A conflict records the server revision and blocks later queued events from being returned. The follow-up guard now rejects an acknowledgement for any event other than the earliest currently eligible event, preventing an out-of-order response from advancing the cursor past earlier work. Historical non-sendable/blocked rows remain excluded. Added an API 37 instrumentation scenario that downgrades a synthetic database to v19, verifies the additive v19→v20 migration preserves queued events, tests out-of-order rejection, accepted/duplicate outcomes, a conflict, durable statuses and cursor blocking. The focused test passed 1/1 after the final guard change.

Verification from this continuation: Node 49/49; Kotlin JVM 35/35; backend projection-contract tests 5/5; debug APK and Android-test APK build tasks PASS; lintDebug PASS with 0 errors (114 warnings). The complete API 37 instrumentation run executed 49 cases and failed 5 UI tests (44 passed): catalogue editor, multi-product inventory count, customer profile dialog, staff-declared UPI receipt, and customer repayment UI. The recorded failures were missing accessibility controls; the payment screen failure also captured a Pixel Launcher not-responding dialog. Two unrelated launch tests (device-permission and daily-report) passed individually after the earlier full run. Therefore the emulator suite is NOT green and overall Android UI qualification remains unresolved. The focused sync-journal instrumentation test passes; it does not clear the five UI failures. Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, JVM XML under `android/app/build/test-results/testDebugUnitTest/`, and `android/app/build/reports/lint-results-debug.txt`.

CAT-052 remains a local persistence foundation only. No authenticated network uploader, retry worker, protected server credential, server-side projection application, or remote synchronization was implemented. The stored cursor is a persisted acknowledgement high-water mark; transport/server ordering and crash-safe request delivery still need an end-to-end design and tests. CAT-051B, complete Android MVP, owner dashboard, continuous Bluetooth, physical-device/field trials, independent security review, and release readiness remain open.

## Superseding verification — emulator launcher recovery and CAT-052 journal — 30 October 2026

The earlier full-suite result with five UI failures was caused by the emulator's Pixel Launcher entering an ANR dialog, which obscured the app from UI automation. Captured evidence at `test-evidence/emulator-launcher-anr.png`; restarting only the emulator launcher cleared the dialog (`test-evidence/emulator-launcher-restarted.png`). The five previously failing UI tests then passed individually. The complete current-tree run subsequently passed: Node 49/49, Kotlin JVM 35/35, API 37 Android instrumentation 49/49 (133 passed across these three app suites), backend projection contract 5/5 (138 method executions across four suites), debug APK and instrumentation APK tasks PASS, lint 0 errors / 114 warnings. Full command: `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug connectedDebugAndroidTest --console=plain` from `android/`; Node and backend contract commands were run separately. The API 37 XML at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` now reports 49 tests, 0 failures, 0 errors, 0 skipped. This verifies the emulator's current test run, not physical-device or field behavior.

CAT-052 update: schema v20 durably records per-event accepted/duplicate/conflict outcome and server revision; the stored server-revision cursor advances monotonically and an out-of-order response cannot acknowledge past the earliest eligible outbox event. The v19→v20 migration, retries, conflict blocking, cursor persistence, and this queue-head guard pass an API 37 test. Android network delivery, credential/device enrollment, background retries, conflict-resolution UI, and authoritative server financial projection are still outstanding. CAT-051B functional same-emulator recovery is documented as passed in its implementation report; independent security review, hard power-loss/low-storage qualification and second-device recovery remain open. The six-wave project remains in progress.

## CAT-052 Android sync-runner slice — 30 October 2026

Added a manually invokable Android sync runner over the existing allow-listed outbox. It builds the authenticated backend envelope, pushes one event per request in revision order, validates event-correlated responses and persists accepted/duplicate/conflict results through the schema-v20 journal. The HTTP transport requires HTTPS/platform TLS, forbids redirects and credentials in URLs, bounds response sizes, and keeps bearer tokens out of persistent storage/logging. Android INTERNET permission is declared; cleartext remains disabled. Focused API 37 tests passed 4/4. Kotlin JVM 35/35, Node 49/49, backend contract 5/5, APK build tasks PASS, lint 0 errors/114 warnings. The latest full Android run remains flaky: 48/53 passed; the same five accessibility UI tests failed while Pixel Launcher was in ANR. No authenticated network server was contacted. Login/session provisioning, protected token vault, settings/endpoint UX, WorkManager/retry, conflict UI, server projections, and real HTTP integration remain unimplemented. See `CAT-052-ANDROID-SYNC-RUNNER-REPORT.md`.
## CAT-052 Android session storage follow-up — 30 October 2026

`SyncSessionVault` now persists an HTTPS endpoint, business/shop/device UUIDs and bearer token only as an AES-GCM ciphertext envelope under an app/device-bound Android Keystore key. API 37 instrumentation passed 3/3 vault tests (reopen, validation, explicit clear, missing-key preservation) plus 4/4 sync-runner tests. Debug APK, Android-test APK and JVM unit-test tasks passed. Lint: 0 errors / 118 warnings. These focused checks do not demonstrate remote authentication or end-to-end synchronization. The latest full Android instrumentation attempt remains 48/53; five UI tests failed during emulator launcher ANR behavior. Bluetooth, physical-device, field, clean-install backup recovery on a second device, and independent security review remain unqualified.

### Superseding full emulator regression — 30 October 2026

After restarting the emulator launcher, the current full API 37 instrumentation suite passed 56/56 with 0 failures/errors. Kotlin JVM 35/35, prototype Node 49/49, backend contract tests 5 passed with 4 PostgreSQL integration cases skipped because no test database was started; debug and Android-test APK builds passed; lint reported 0 errors / 118 warnings. Full test XML: `test-evidence/cat052-session-vault/full-instrumentation.xml`. The session vault and sync-runner tests use only synthetic credentials and fake transport. No real authenticated network sync, Bluetooth hardware, noisy-shop test, independent security review, or production qualification is implied.

### Superseding account setup/manual sync screen — 30 October 2026

Added `SyncAccountClient` and `CloudSyncActivity`: owner-gated HTTPS server registration, re-login for a device already registered to the shop, manual outbox sync, and explicit server-revoked disconnect. Account contract and UI smoke are emulator-tested; no live network backend was contacted. Latest full API 37 suite passed 62/62; Kotlin JVM 35/35; prototype Node 49/49; backend tests 5 passed and 4 DB integration tests skipped; APK builds passed; lint 0 errors / 128 warnings. Evidence: `test-evidence/cat052-session-vault/full-instrumentation-with-account-ui.xml`. This is not authenticated-sync qualification: no new-device enrollment, WorkManager retry, conflict workflow or authoritative server projection exists. Physical Bluetooth, shop pilot, independent security review and production gates remain open.

## CAT-052 WorkManager continuation — 30 October 2026

Added durable one-time and owner-enabled six-hour background sync scheduling through WorkManager 2.12.0. Jobs require connected network, use exponential backoff, carry no credential or business payload in WorkManager data, and process up to five eligible events per run before queueing a continuation. A process lock serializes manual sync, account/session replacement, logout and the background worker. The worker observes the backup/recovery write barrier, uses the existing Keystore session and encrypted allow-listed outbox, and records only non-sensitive status. Cloud Sync now offers queue, periodic-enable and pause actions; logout cancels queued jobs and waits for in-flight sync before revoking/clearing credentials. Session decryption was moved off the activity main thread.

Final-source verification: Node 49/49; Kotlin JVM 35/35; Android worker instrumentation 3/3; debug and instrumentation APK compilation/build PASS; lint 0 errors / 138 warnings. Backend tests 5 passed / 4 skipped because PostgreSQL integration was not running. Full Android instrumentation did not complete: emulator services failed during the 65-case run, and isolated Cloud Sync screen launches hit a Pixel Launcher ANR before the test could launch the activity. Worker runner and Gradle logs are under `test-evidence/cat052-workmanager/`; launcher ANR screenshots and failing UI XML are recorded there or under Android build outputs. This is not a complete Android regression pass and the modified screen remains emulator-unqualified.

CAT-052 is still not end-to-end sync qualified: no live HTTPS request occurred; server financial projections, new-device enrollment, conflict-resolution UI and production server operation are absent. The full app suite must be rerun on a stable emulator. Physical device behavior, field qualification, independent security review and product release readiness remain open.

Superseding lint verification after the final Cloud Sync main-thread change: `:app:lintDebug` passed with 0 errors and 139 warnings. `:app:testDebugUnitTest` also passed after that change; the JVM report remains 35/35. The warning count supersedes the earlier 138-warning line in this continuation.

## Superseding CT-11/CT-12 integrated regression — 31 October 2026

Added an emulator-driven Sales History to audited refund workflow test. The final full Gradle run passed Node 49/49, Kotlin JVM 40/40, Android instrumentation 70/70 (55 persistence/UI tests, 5 account-client tests, 3 background-work tests, 4 sync-runner tests and 3 session-vault tests), debug and instrumentation APK tasks, and lint with 0 errors / 139 warnings. Backend tests passed 5 contract cases; 4 PostgreSQL integration tests were skipped because no test database was configured. Preserved evidence: `test-evidence/2026-10-31/ct11-ct12-full-instrumentation.xml` and `ct11-ct12-regression.md`. This qualifies the listed synthetic emulator flows only. Real Bluetooth microphone and shop-noise testing, independent security review, full shopkeeper MVP, live backend synchronization and production readiness remain unqualified.

## Superseding CT-10 payment slice — 31 October 2026

The payment screen now supports an atomic completed-order cash-plus-Udhar collection, in Kotlin and the browser transaction engine. Latest targeted full verification: Node 50/50; Kotlin JVM 43/43; Android API 37 instrumentation 71/71; debug/instrumentation APK builds PASS; lint 0 errors/140 warnings. Backend 5 pass/4 PostgreSQL-dependent skipped. Synthetic UI evidence confirms the selected customer's receivable and staff-declared cash survive encrypted SQLite reopen. This is a prototype screen flow, not a complete payment module or production qualification. See `CT-10-PAYMENT-FOLLOWUP-REPORT.md` and `../test-evidence/2026-10-31/ct10-split-instrumentation.xml`.
## Superseding CT-10 payment history regression — 31 October 2026

Full current-source verification passed: Node 50/50, Kotlin JVM 43/43, API 37 instrumentation 72/72, debug and instrumentation APK builds, lint 0 errors/140 warnings. Backend tests passed 5/9; four PostgreSQL-dependent cases were skipped because no isolated test database was configured. API 37 coverage includes Payment History evidence labels and explicit saved-phone/contact-consent revocation. Evidence is preserved in `test-evidence/2026-10-31/ct10-history-final-gradle.log`, `ct10-history-final-instrumentation.xml`, `ct10-history-node-tests.log`, and `ct10-history-backend-tests.log`. This is synthetic emulator qualification only. CAT-051B destructive-failure/independent-review gates, physical Bluetooth, noisy-counter trials, live server integration, remaining product screens and production readiness remain open.
## Superseding CT-10 cash-plus-UPI-pending split — 31 October 2026

Full current-source verification passed: Node 51/51, Kotlin JVM 44/44, Android API 37 instrumentation 73/73, debug and instrumentation APK builds, lint 0 errors/141 warnings. Backend tests passed 5/9; four PostgreSQL-dependent tests were skipped because no isolated test database was configured. The new CT-10 UI journey records staff-declared cash and a separate pending UPI attempt, closes/reopens the activity, then resolves the attempt with an explicitly staff-declared receipt. Assertions cover pending cancellation linkage, paid/due totals, stock unchanged and zero provider-verified records. Evidence: `../test-evidence/2026-10-31/ct10-cash-upi-full-gradle.log`, `ct10-cash-upi-instrumentation.xml`, `ct10-cash-upi-kotlin.xml`, `ct10-cash-upi-node.log`, `ct10-cash-upi-backend.log`, `ct10-cash-upi-lint.txt`. These synthetic API 37 flows do not qualify external UPI integration, live backend sync, hardware voice, full recovery/security gates or production readiness.
## Superseding CT-10 direct UPI UI regression — 31 October 2026

After adding direct staff-declared UPI receipts, final full verification passed: Node 52/52, Kotlin JVM 45/45, API 37 instrumentation 74/74, debug and instrumentation APK builds PASS, lint 0 errors/141 warnings. Backend: 5 passed, 4 PostgreSQL-dependent tests skipped because no isolated test database was configured. The new emulator UI case records a partial direct UPI receipt, reopens encrypted storage and verifies paid/due totals, unchanged stock, no pending attempt and no provider-verified entry. The adjacent split-payment test verifies cash plus pending UPI, restart recovery and later staff-declared resolution. Evidence: `../test-evidence/2026-10-31/ct10-direct-upi-full-gradle.log`, `ct10-direct-upi-instrumentation.xml`, `ct10-direct-upi-kotlin.xml`, `ct10-direct-upi-node.log`, `ct10-direct-upi-backend.log` (backend run from the same source snapshot), and `ct10-direct-upi-lint.txt`. Only synthetic prototype flows are qualified; external payment provider, production sync, CAT-051B hard-failure/security, remaining screens, continuous Bluetooth, physical/shop trials and release gates remain open.

## Superseding recovery qualification — 1 November 2026

Added a synthetic API 37 instrumentation case for an encrypted export that partially writes and then fails. The active order remains intact, private snapshot files are cleaned, the maintenance barrier releases, and a follow-up sale succeeds. After synchronizing an accessibility-driven consent-revocation test with UI idle, the complete current-source regression passed: Node 52/52, Kotlin JVM 45/45, Android instrumentation 75/75, debug and instrumentation APK tasks PASS, lint 0 errors / 141 warnings. Logs: `../test-evidence/2026-11-01/cat051b-final-gradle-rerun.log` and `../test-evidence/2026-10-31/cat051b-final-node.log`; current reports are under `../android/app/build/`. One earlier full run had two UI failures (the profile assertion raced the action; customer-credit launch timed out during emulator focus delay); both failed cases passed individually after cold restart, and the synchronized profile test plus complete suite passed on rerun. This does not qualify actual disk exhaustion, hard process/power interruption, physical Keystore invalidation, cross-device/person recovery or independent security review. Phase 0 and the six-wave project remain incomplete.

## SAF export cleanup validation status — 1 November 2026

After the complete run above, the owner-facing SAF export failure path was updated to delete a partial document when the provider permits it and to warn the owner if it may remain. The helper has JVM coverage for successful deletion, provider refusal/exception, preservation of the original write failure and no deletion on success. Latest source verification is Node 52/52, Kotlin JVM 46/46, APK build tasks PASS, lint 0 errors / 141 warnings. The focused emulator recovery-screen rerun did not execute because the AVD was not connected to ADB. Therefore the 75/75 full instrumentation result is from immediately before this last app UI change; runtime qualification of the new deletion branch is still pending.

## CT-09 timestamped audit entries — 1 November 2026

Order Details now reads persisted audit timestamps and renders them in the device's local timezone with an explicit warning that device-clock time can be inaccurate and is not server-verified. Node 52/52, Kotlin JVM 46/46, debug/instrumentation APK build tasks passed, and lint reported 0 errors / 141 warnings. The focused API 37 instrumentation rerun was blocked because no emulator was connected to ADB. The preceding full API 37 run (75/75) predates the latest SAF cleanup and CT-09 change; current UI runtime qualification is pending. See `../test-evidence/2026-11-01/ct09-audit-timeline-gradle.log` and the CT-09 report. Phase 0 and production readiness remain incomplete.

## Maintenance barrier reentrancy regression — 1 November 2026

Nested backup/restore maintenance now restores the outer operation's visible maintenance label after the nested helper completes. An exception thrown by a UI listener during initial registration is isolated, and maintenance action failures still release the write lock and permit later commits. JVM tests cover nested maintenance, observer failure, action failure and resumed writes. Current checks: Node 52/52; Kotlin JVM 48/48; debug/instrumentation APK build tasks PASS; lint 0 errors/141 warnings. `connectedDebugAndroidTest` is blocked before execution because no ADB device is connected. The previous 75/75 API 37 full run predates these changes.

## Wave 4 OW-01 and backend overview — 1 November 2026

Implemented an owner-authenticated overview endpoint backed by accepted sync events and a same-origin web interface. A fresh isolated PostgreSQL 17 run passed 13/13 backend tests including authenticated event submission, summary calculations, wrong-shop denial, RLS and logout. Node 52/52; Kotlin JVM 48/48; APK build tasks PASS; lint 0 errors/141 warnings. Android device-ID display compiled but awaits emulator runtime; ADB reports no connected device. Backend summary remains a limited journal projection with customer credit, expenses, supplier records, shifts, unsynced data and profit excluded. No browser visual review or production deployment was performed. See `../specs/OW-01-OVERVIEW-REPORT.md` and `../test-evidence/2026-11-01/cat052-owner-overview-integration.log`.

## CT-18 supplier invoice reference — 1 November 2026

Optional manually entered supplier invoice references are now supported on direct and purchase-order receipts, with trim/length/control-character validation, per-supplier case-insensitive uniqueness, audit description, receipt-history display, and SQLCipher v21→v22 additive migration. Node 52/52; Kotlin JVM 55/55; focused API 37 migration 1/1 and receiving UI 1/1 pass; debug and Android-test APK builds PASS; lint 0 errors/154 warnings. Full API 37 suite: 63/77 passed, 14 failed after Pixel Launcher/System UI ANRs. A focused payment UI rerun reproduced a System UI ANR, so full Android regression is not green. Full XML, Gradle logs and focused evidence are under `../test-evidence/2026-11-01/ct18-*`. CAT-051B, production sync, full Android MVP, owner web screens, Bluetooth hardware/field work, independent security review and release qualification remain incomplete. See [CT-18 report](CT-18-SUPPLIER-INVOICE-REFERENCE-REPORT.md).

## CAT-051B pre-commit restore cutover recovery — 1 November 2026

A focused API 37 instrumentation test passed for the state after a replacement SQLCipher database is active but before the durable restore commit marker. Startup restored the prior encrypted database, preserved the uncommitted replacement for diagnosis, and cleared the journal. This is deterministic on-disk fault-state testing, not a hard process termination or power-loss drill. Evidence: `../test-evidence/2026-11-01/cat051b-precommit-recovery-focused.xml`. The latest complete Android instrumentation run remains 63/77 with 14 UI failures caused by Pixel Launcher/System UI ANRs; the full suite is not green. Node 52/52, Kotlin JVM 55/55, APK builds pass, lint 0 errors/154 warnings. Cross-device recovery, real low-storage/interruption, physical Keystore invalidation, second-person drill, independent security review, the full product and release qualification remain open.

## CT-20 supplier settlement workflow — 1 November 2026

Staff can now record cash or UPI supplier settlement in the existing supplier ledger. UPI is explicitly staff-declared, not provider-verified, and does not change cash drawer totals; cash remains reconciled as cash outflow. No schema migration was necessary. Kotlin JVM 56/56, focused API 37 supplier ledger UI 1/1, APK builds pass, lint 0 errors/154 warnings. Current full-source Android regression was not rerun; the preceding 77-test full run passed 63 and failed 14 after System UI/Pixel Launcher ANRs. Details/evidence: `CT-17-20-PROCUREMENT-REPORT.md`, `../test-evidence/2026-11-01/ct20-supplier-upi-ui-focused.xml`. Overall product and security gates remain open.

## CT-03 local staff identity and role gates — 2 November 2026

SQLCipher v24 adds local staff credential rows through an additive v23→v24 migration. Owner-only staff management creates/disables synthetic cashier/manager accounts. PINs are salted PBKDF2-HMAC-SHA256 verifiers (600,000 iterations), with persistent failure lockout; the API 37 test covers wrong/correct PIN, role flags, persistence and disable. Counter cashier/manager route gates are implemented. Latest verification: prototype Node 52/52, Kotlin JVM 56/56, API 37 instrumentation 83/83, backend 11 passed / 4 PostgreSQL-dependent skipped, APK builds passed, lint 0 errors / 161 warnings. Evidence: `../test-evidence/2026-11-02/ct03-staff-full-instrumentation.xml` and `../test-evidence/2026-11-02/ct03-staff-verification.md`. CT-03 remains prototype scope: end-to-end staff sign-in/account-management UI tests, idle timeout, PIN reset, transaction actor attribution, remote identity/revocation and independent security review remain open. CAT-051B physical interruption/storage gates, real device/Bluetooth/field trials, authenticated production sync, broad Android screens and release qualification remain open.

## CT-32 Shop Profile & Settings - 19 November 2026

Added the owner-facing Shop Profile & Settings entry from the counter and Shop Home. Existing shop profiles use the profile-edit label; empty profiles use Shop Setup. The current encrypted profile editor updates shop name, business type, and language using existing owner-only persistence and audit rules. API 37 instrumentation verifies UI save, encrypted reopen, audit, and no order/payment/stock side effects. Current verification: Node 52/52, Kotlin JVM 56/56, Android API 37 instrumentation 90/90, APK build tasks PASS, lint 0 errors / 161 warnings. See [CT-32 report](CT-32-SHOP-SETTINGS-REPORT.md). This does not complete CT-32 device administration, full shop settings, or Phase 0 external/security gates.

## CT-25 expense date history and daily totals — 19 November 2026

New expense entries store their device-clock timestamp in the existing `created_at` field; no schema migration was needed. Expenses now groups totals by device-local calendar date and discloses the timezone/device-clock limitation. Legacy rows without timestamps remain “date unavailable.” JVM summary coverage and API 37 tests verify grouping, encrypted persistence, screen rendering, and exact timestamp preservation through encrypted portable snapshot restore. Current full Android regression: Kotlin JVM 57/57, API 37 instrumentation 90/90, debug and test APK builds pass, lint 0 errors / 161 warnings. Node 52/52 is the latest prior unchanged result and was not rerun for this slice. No cross-device date trust or end-to-end expense sync is qualified. See `specs/CT-25-EXPENSES-REPORT.md`.

## CT-29 Android SAF export runtime — 19 November 2026

On the installed API 37 emulator, created a synthetic local owner, navigated to Daily Reports, opened Android's Create Document picker, saved the CSV to shared Downloads, observed the success message, and pulled the resulting 940-byte file for content inspection. The ledger was empty and all exported amounts were zero; therefore this proves picker/destination/file-write/success-path operation only, not nonzero financial aggregation. It does not qualify cancellation, write failure, provider variation, calendar-day reports, profit, tax export or manual usability. Evidence screenshots and CSV are ignored under `test-evidence/2026-11-19/ct29-*`; see `specs/CT-29-CSV-EXPORT-REPORT.md`.

## Recovery test follow-up — 20 November 2026

The SAF export test cleanup adjustment builds successfully (`assembleDebugAndroidTest` PASS), and the focused API 37 emulator test `ownerBackupMenuCreatesEncryptedSyntheticBackupThroughSafUi` passed 1/1 in 103.405 seconds with zero failures. The emulator had to finish booting before the successful run. The previous full instrumentation result remains 92 tests / 4 failures and was not rerun, so the full suite is not green-qualified. CAT-051B remains partial; broader picker/activity stability, hard interruption/storage exhaustion, physical key invalidation, second-device/person recovery, and independent security review remain open. The current test XML is local generated output at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`.

Same-tree checks on 20 November: prototype Node 52/52; Kotlin JVM 59/59; `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` completed successfully; lint remains 0 errors / 161 warnings. The focused UI pass is separate from the full 92/4 instrumentation result. No PostgreSQL-backed backend suite was run in this follow-up.

## Superseding full Android emulator regression — 20 November 2026

A fresh API 37 run of `connectedDebugAndroidTest` completed successfully: 92/92 tests, 0 failures, 0 errors, 0 skips, in 9m53s. It includes the SAF export picker with the 30-second accessibility wait and DocumentsUI cleanup, recovery-secret dialog dismissal, and SAF content-URI verification; all passed. This supersedes the 19 November 92/4 full-suite result. Same-source checks: prototype Node 52/52; Kotlin JVM 59/59; debug and instrumentation APK build tasks PASS; lint 0 errors / 161 warnings. The JUnit XML is generated locally at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` and is not committed. CAT-051B remains partial: physical Keystore invalidation, hard power/process interruption, real storage exhaustion, second-device/person recovery, independent security review and production security qualification remain open. No PostgreSQL-backed backend suite was run in this regression.

## Current-source restore recovery process-death drill - 21 November 2026

Added a debug-only, non-exported recovery harness service in a separate Android process. The API 37 instrumentation test creates a synthetic order in SQLCipher, binds the harness, moves the active encrypted database into the preserved restore slot, writes the durable pending-restore journal, and syncs a checkpoint marker. The test unbinds and confirms the helper process exits. A newly started helper process then opens the normal `SqliteCounterPersistence` path, which performs startup recovery from the journal. It reports the recovered order ID and paise total, original audit event, cleared pending marker, and consumed previous-store slot; the instrumentation process then independently reopens the SQLCipher database and checks the same sale and audit record.

Focused test passed 1/1. The complete API 37 instrumentation suite excluding only `LiveBackendSyncE2ETest` passed 100/100, 0 failures, 0 errors, 0 skips. Kotlin JVM passed 63/63; Node passed 52/52; debug and Android-test APK assembly passed; lint reported 0 errors / 171 warnings. Android evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`. The backend suite run without a configured database passed 19 tests and skipped 4 PostgreSQL-dependent integration tests; the separate isolated PostgreSQL 23/23 and live SQLCipher outbox retry 1/1 evidence remains as documented in the CAT-052 report.

This demonstrates an abrupt helper-process death after the durable restore checkpoint and journal recovery in a fresh process using the production persistence startup path. It does not kill the foreground application process, cut device power, simulate filesystem/power-loss guarantees, exhaust physical storage, invalidate hardware Keystore keys, or establish second-device/person recovery. Those gates, independent security review, and production qualification remain open; CAT-051B remains partial.
## Superseding restore crash-window qualification - 22 November 2026

Extended the debug-only separate-process crash harness to cover the later restore cutover window as well as the earlier preserved-database window. The second API 37 case creates distinct synthetic original and replacement SQLCipher stores under the same local key, commits the durable pending-restore journal, moves the original to `.pre-restore`, activates the replacement, and terminates the helper process before `recordCommitted`. A fresh helper process opens the normal persistence startup path. Recovery restores the original order and audit history, clears the pending marker, consumes `.pre-restore`, and preserves the uncommitted replacement as `.restore-interrupted`; the test opens that preserved encrypted replacement separately and verifies its order and audit event remain readable.

Current full API 37 instrumentation passed **101/101**, zero failures/errors/skips, including both helper-process crash checkpoints; Kotlin JVM passed **63/63**; Node passed **52/52**; `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest` passed; lint reported **0 errors / 171 warnings**. Backend tests without a configured database passed **19**, failed **0**, and skipped **4** PostgreSQL-dependent cases. JUnit XML is `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` (local generated output).

The injected process deaths occur in a dedicated debug-only helper process. They exercise durable restore state transitions and startup recovery in a fresh process, but do not terminate the foreground application/instrumentation process or simulate device power loss, real filesystem exhaustion, physical Keystore invalidation, OEM backup behavior, or a second device/person. Independent security review and production readiness remain separate gates. CAT-051B remains partial.