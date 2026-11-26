# CAT-049 Android Prototype

This directory contains the native Android shopkeeper application. It now connects a substantial set of synthetic-test-qualified counter, product, stock, customer credit, procurement, cash, staff, closing, reporting, recovery and sync workflows to encrypted local persistence. The overall 32-screen plan remains partial; Android speech is operator-started, one utterance at a time, and reviewed before application. This is not a production POS or a qualified continuous Bluetooth counter.

## CAT-052 Android HTTPS catalogue E2E — 26 November 2026

The focused opt-in `LiveBackendSyncE2ETest#realBackendStoresAndroidEventAndMakesLostAcknowledgmentRetryIdempotent` passed on the API 37 emulator against a disposable loopback PostgreSQL 17 backend. It registers a synthetic account, records encrypted local product/stock/sale events, tests a committed event with a lost acknowledgment and idempotent retry after SQLCipher reopen, then fetches the authenticated catalogue snapshot and applies it to the encrypted catalogue cache. The test now derives expected cursor/revisions from the actual outbox, including catalogue events. For local emulator TLS use ADB reverse and a certificate-valid `https://localhost:<port>` endpoint; the test certificate is not valid for `10.0.2.2`. Results: focused Android E2E 1/1, Kotlin JVM 106/106, prototype Node 52/52, backend PostgreSQL 44/44, APK builds PASS, lint 0 errors / 201 warnings. See [report](../specs/CAT-052-ANDROID-HTTPS-CATALOGUE-PULL-E2E-REPORT-2026-11-26.md). This is not physical-device, production backend, multi-device, Bluetooth or shop-field qualification.

## Superseding current-source regression — 24 November 2026

**Latest 25 November result:** the serial-pinned API 37 instrumentation suite now passes **138/138** (12m57s), including the new CT-27 authenticated shift start/handover UI journey. This supersedes the 137-test count below. The run used `android/scripts/run-isolated-connected-tests.cmd emulator-5554`; see the current [qualification report](../specs/ANDROID-REGRESSION-QUALIFICATION-2026-11-24.md).

After rebooting the disposable API 37 AVD, the serial-pinned full instrumentation suite passed **137/137** with 0 failures, errors, or skips. Node prototype tests passed 52/52, Kotlin JVM tests 81/81, and backend Node tests passed 30 with 5 PostgreSQL-dependent skips. Debug and instrumentation APK build tasks passed; lint reported 0 errors and 182 warnings. See [`ANDROID-REGRESSION-QUALIFICATION-2026-11-24.md`](../specs/ANDROID-REGRESSION-QUALIFICATION-2026-11-24.md) and the current [project progress audit](../specs/PROJECT-PROGRESS-AUDIT-2026-11-24.md).

This result supersedes older counts in this README. It covers only the listed synthetic emulator flows. Bluetooth hardware, noisy-shop trials, independent security review, cross-device recovery, visual browser review, and production deployment remain unqualified.

## Current-source continuation — OW-10 shift summary — 24 November 2026

Android outbox projection now includes anonymous cash drawer/staff shift summaries using stable opaque IDs, device time and declared cash values; operator names and handover notes are excluded. Kotlin JVM 73/73 and focused API 37 shift instrumentation 1/1 passed. A live synthetic Android SQLCipher outbox → HTTPS sync → restricted-role PostgreSQL → owner shift projection E2E also passed 1/1 with nine events. Backend integration passed 31/31 on an isolated test database; prototype Node passed 52/52; debug APK and lint passed with 0 errors. The broad connected suite attempt failed after 88 tests with 4 test failures, followed by emulator package-service/ADB disconnection; it is not a passing regression. The OW-10 summary is not authenticated attendance/payroll. See [OW-10 report](../specs/OW-10-ANONYMOUS-SHIFT-SUMMARIES-REPORT-2026-11-24.md).

## Current-source qualification — 22 November 2026

CT-29 has API 37 coverage for nonzero SAF CSV export and picker cancellation with ledger snapshot immutability. CT-30 low-stock alert review opens Inventory and is verified read-only. The isolated emulator full instrumentation suite passed 106/106 with zero failures/errors/skips; endpoint-dependent live-backend instrumentation is excluded and tested separately. Current Node tests are 52/52, Kotlin JVM tests 63/63, APK build tasks pass, and Android lint reports 0 errors / 172 warnings. To avoid testing every connected AVD, use `scripts/run-isolated-connected-tests.cmd <adb-serial>`; this validates the serial and sets `ANDROID_SERIAL` before invoking Gradle. Hardware Bluetooth, physical shop, write-failure, independent security and release qualification remain open. See the [CT-29 report](../specs/CT-29-CSV-EXPORT-REPORT.md), [CT-30 report](../specs/CT-30-LOW-STOCK-ALERTS-REPORT.md), and [current progress audit](../specs/PROJECT-PROGRESS-AUDIT-2026-11-22.md).

## Current source continuation — 19 November 2026

The encrypted outbox now includes a privacy-minimized expense projection for accepted local expense commands: expense ID, category, paise amount, tender method and declared status. Free-form notes and drawer-session links remain local. Backend validation keeps legacy `projection_delta.v1` events without `expenses` readable and rejects unknown expense fields. Current checks: Kotlin JVM 56/56; backend 16 passed / 4 PostgreSQL-dependent skipped; prototype Node 52/52; debug and instrumentation APK builds passed; lint 0 errors / 161 warnings. The current API 37 instrumentation run did not complete: the emulator stalled before producing a result XML. A focused maintenance-barrier test passed earlier; the current financial-restore focused attempt did not start its test runner. Evidence is under `../test-evidence/2026-11-19/`. This source/build scope is not Android emulator qualification of expense upload or the full recovery workflow.

## Build

Requirements used in this workspace: Android Studio 2026.2.2, JDK 21, Android SDK Platform 37, Build Tools 36.0.0, Gradle wrapper 9.7.1 and AGP 9.4.1.

From this directory on Windows:

```powershell
.\gradlew.bat clean assembleDebug testDebugUnitTest --console=plain
```

The verified debug APK output is `app/build/outputs/apk/debug/app-debug.apk`. The current clean run passes 23 Kotlin JVM tests and 10 SQLite/backup/profile instrumentation methods on the API 37 AVD. CT-01 Welcome and CT-02's first shop-profile slice are opened in the emulator; the shop name/type/language/currency write persisted through process restart. This is emulator qualification for the listed prototype flows only; see [CT-01/02 implementation report](../specs/CT-01-02-IMPLEMENTATION-REPORT.md), [master tracker](../specs/MASTER-IMPLEMENTATION-TRACKER.md), [Phase 0 qualification report](../specs/PHASE-0-QUALIFICATION-REPORT.md), [CAT-050B report](../specs/CAT-050-NATIVE-TRANSACTION-PARITY.md), [CAT-051 requirements](../specs/CAT-051-SECURE-OFFLINE-REQUIREMENTS.md), [build status](../specs/CAT-049A-ANDROID-BUILD-STATUS.md), and [emulator diagnosis](../specs/CAT-049-EMULATOR-DIAGNOSTIC.md).

### Isolate instrumentation to one emulator

`connectedDebugAndroidTest` can target every ADB-connected emulator. When more than one device is running, invoke `scripts/run-isolated-connected-tests.cmd <adb-serial>` so the authorized serial is checked and passed through `ANDROID_SERIAL`. Example: `scripts/run-isolated-connected-tests.cmd emulator-5556`. By default, `OfflineQualificationRunner` excludes the four opt-in backend tests (`LiveBackendSyncE2ETest`, `LiveBackgroundSyncRetryE2ETest`, `LiveBackgroundSyncProcessRestartE2ETest`, and `LiveSyncMaintenanceBarrierE2ETest`). The live tests require an isolated HTTPS/PostgreSQL endpoint; process restart requires explicit phase arguments, and the maintenance-race test additionally requires a test-only control token and a server configured with `SYNC_E2E_HOLD_ACK_ON_SYNC_REQUEST=1`. Run the maintenance-race test alone with the opt-in property, `syncE2eEndpoint`, and `syncE2eControlToken` runner arguments. Its test server binds to loopback, requires a long random token for control routes, and must use synthetic data and a disposable PostgreSQL cluster. Do not expose the test control endpoint publicly or point it at production. For a focused Gradle test, set `$env:ANDROID_SERIAL` to the intended test AVD before calling `gradlew.bat` and confirm the Gradle output lists only that serial.

## Current behavior

- Operator explicitly starts a microphone utterance; Android requests `RECORD_AUDIO` at runtime.
- Android `SpeechRecognizer` returns a transcript to an editable field. It proposes a supported command; the operator must apply or reject it. Typed input is always available.
- Hindi/Hinglish, Marathi and English India are selectable. The native command vocabulary and product list are intentionally small.
- A Bluetooth communication route can be selected on Android 12+ after permission. The app reports route selection separately from actual captured microphone source; the latter requires device validation.
- Listening is stopped when paused, stopped, backgrounded or the selected device disconnects. There is no background service or silent auto-resume.
- Audio is not saved by the app. Android's recognition provider may handle audio according to its own service/network behavior; this prototype cannot guarantee on-device recognition or provider retention behavior.
- A SQLite `SQLiteOpenHelper` store persists current orders/lines, payment and fulfillment states, stock projection, idempotency receipts, append-only audit entries, stock movements, customer credit/repayment records, append-only payment entries, and a pending sync outbox in one local database transaction per accepted command. Additive schema migrations v1-to-v6 preserve prior rows. The Android model is not the browser transaction engine and does not yet cover its full business operations.

## Qualification boundary

The implementation is a segmented, manually re-armed speech prototype, not uninterrupted Mode B2. Android's standard `SpeechRecognizer` documentation says it is not intended for continuous recognition and may stream audio to remote services: [official reference](https://developer.android.com/reference/android/speech/SpeechRecognizer). Bluetooth route selection is implemented with API 31+ `AudioManager.setCommunicationDevice`; route acceptance alone does not verify which microphone the recognition service used: [official API reference](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)).

The emulator launch and the described SQLite instrumentation cases are verified. Physical microphone/headset source, noisy-shop trial, and battery measurement have not been completed. The database is SQLCipher-encrypted with an Android Keystore-wrapped device key. Backup/recovery remains partially qualified; there is no authenticated sync worker or production recovery qualification. See the [emulator diagnosis](../specs/CAT-049-EMULATOR-DIAGNOSTIC.md), CAT-049A build status and CAT-051 requirements.

Detailed status reports: [CAT-049B microphone/Bluetooth](../specs/CAT-049B-MICROPHONE-STATUS.md), [CAT-049C voice session](../specs/CAT-049C-CONTINUOUS-SESSION-STATUS.md), and [CAT-049 Android spike](../specs/CAT-049-ANDROID-BLUETOOTH-SPIKE.md).





## CAT-051 secure storage update

The native database is now SQLCipher-encrypted, with its random passphrase wrapped by an Android Keystore AES-GCM key. Existing plaintext v1-v6 files migrate non-destructively through verified copies. Instrumentation verifies ciphertext, missing-key refusal, corrupt-input preservation, v1 migration, restart/idempotency, rollback, portable backup/restore, tamper/replay rejection, and encrypted shop-profile persistence/write-barrier behavior. Android Auto Backup and device transfer are explicitly excluded. Latest local results: Node 48/48; Kotlin JVM 23/23; Android instrumentation 10/10; APK builds pass; lint 0 errors (45 warnings).

CAT-051B remains open beyond its current functional emulator scope. A synthetic encrypted backup was exported and restored through SAF after uninstall/reinstall on the same API 37 emulator; a rich fixture's restored orders and payment states were visible. Cross-profile/device restore, second-person recovery, real low-storage and hard-interruption tests, broader Keystore invalidation, and independent review remain pending; generated shop UUID mismatch protection is tested. Do not treat the prototype as production-ready; see [CAT-051B report](../specs/CAT-051B-IMPLEMENTATION-REPORT.md).



See [CAT-051B implementation and qualification report](../specs/CAT-051B-IMPLEMENTATION-REPORT.md) for current acceptance status and evidence. CAT-051B is not complete.

## Current implementation note � 29 October 2026

The native app now includes a partial CT-13/14 product catalogue/editor backed by encrypted SQLCipher schema v7. Starter prices are unconfirmed examples. The app counter restricts order creation to active products with an explicitly confirmed owner price; configured integer-paise pricing and product aliases are used for parsing. New products start at zero stock and need a separate receiving/stock workflow before fulfillment.

Latest local verification: Node 48/48, Kotlin JVM 23/23, API 37 Android instrumentation 11/11; debug and instrumentation APK builds pass; lint 0 errors / 48 warnings. See `../specs/CT-13-14-CATALOGUE-REPORT.md`. These checks do not establish full screen, hardware, independent security review, or production qualification.

Latest verification update � 29 October 2026: Node 48/48, Kotlin JVM 23/23, API 37 Android instrumentation 12/12 (83 total); debug and instrumentation APK builds pass; lint reports 0 errors / 52 warnings. The first native CT-06 Quick Sale and partial CT-13/14 product catalogue/editor are now present. See `../specs/CT-06-NATIVE-QUICK-SALE-REPORT.md` and `../specs/CT-13-14-CATALOGUE-REPORT.md` for scope and qualification limits.

Latest same-day screen update: a partial CT-08/09/10 Live Orders screen is now connected to the persisted model, with revision-checked fulfillment and cash/UPI-pending/Udhar actions. It rendered its empty state on the API 37 emulator; those UI buttons were not manually exercised. Latest automated totals remain 48 Node + 23 Kotlin JVM + 12 Android instrumentation = **83 passing**, with APK build pass and lint 0 errors / 52 warnings. See `../specs/CT-08-10-LIVE-ORDERS-REPORT.md`.

## CT-05 and counter verification update — 29 October 2026

The native Shop Home screen is now available from the counter and backed by encrypted local orders, payment events, and credit records. It shows lifetime local totals (not daily results), separates pending UPI from collected cash, counts only active products with owner-confirmed prices, and withholds profit because costs/expenses are missing. Inventory totals are intentionally not shown because opening stock is unqualified. See [CT-05 report](../specs/CT-05-SHOP-HOME-REPORT.md) and screenshot/hierarchy `../test-evidence/2026-10-29/ct05-shop-home.png` and `.xml`.

A synthetic CT-06 checkout was manually exercised on the API 37 emulator: one confirmed ₹10 item sold for staff-declared cash, stock reduced once, and the persisted GIVEN order appeared in Live Orders. The evidence is listed in [CT-06 report](../specs/CT-06-NATIVE-QUICK-SALE-REPORT.md).

Latest verification after the CT-05 summary change: Node 48/48, Kotlin JVM 23/23, Android instrumentation 12/12, debug/instrumentation APK tasks PASS, lint 0 errors / 54 warnings. The emulator tests verify model and persistence behavior; they do not qualify physical Bluetooth audio, the full Android screen set, secure production recovery, cloud sync, staff authentication, or shop operations.

CT-05 Shop Home and CT-11 Sales History were added as partial native screens on 29 October 2026. Home uses local encrypted records, avoids claiming daily sales or profit, and labels inventory opening counts unqualified. Sales History reads persisted order/payment records but has no date filters, search, export or timestamps. Both have API 37 UI evidence under `../test-evidence/2026-10-29/`; see `../specs/CT-05-SHOP-HOME-REPORT.md` and `../specs/CT-11-SALES-HISTORY-REPORT.md`.

Latest verification after these screens: Node 48/48, Kotlin JVM 23/23, API 37 instrumentation 12/12; debug/instrumentation APK builds PASS; lint 0 errors / 54 warnings. Total 83 passing automated tests across separate suites. Physical Bluetooth, production storage/recovery qualification, authenticated sync, most screens/modules, independent security review and field trials remain pending.

### Stock baseline confirmation - 29 October 2026

SQLite schema v8 initializes fresh catalogue inventory at zero and records explicit physical-count confirmation. Legacy stock values are retained but unconfirmed until an owner records an audited count. Strict configured sales/handovers reject unconfirmed stock. The catalogue's limited single-item count flow writes stock movement, audit, idempotency receipt and local outbox atomically. API 37 UI evidence: ../test-evidence/2026-10-29/ct13-count-saved.png. Latest full local tests after this change: Node 48/48, Kotlin JVM 23/23, Android instrumentation 13/13, debug/instrumentation APK tasks PASS, lint 0 errors / 58 warnings. This is not full inventory, receiving or a multi-product stock-count module. Real shop opening balances must be established by physical count; the synthetic emulator data is not business data.

Latest CT-15 screen verification - 29 October 2026: the Inventory Overview was opened from the counter UI on API 37; evidence is ../test-evidence/2026-10-29/ct15-inventory.png and ct15-inventory.xml. Superseding complete test run: Node 48/48, Kotlin JVM 23/23, Android instrumentation 13/13, APK tasks PASS, lint 0 errors / 59 warnings. The screen is a partial inventory view; purchases, receiving, low-stock rules, and a full stock-count session remain unimplemented.

Latest CT-09 continuation - 29 October 2026: read-only order details are connected from live orders and sales history. The actual activity is covered by an emulator instrumentation test that checks persisted order, sale and cash totals, stock movement, and audit text. Latest run: Node 48/48, Kotlin JVM 23/23, Android 14/14, APK tasks PASS, lint 0 errors / 60 warnings. Details: ../specs/CT-09-ORDER-DETAILS-REPORT.md.

Latest CT-10 update - 29 October 2026: Live Orders supports optional partial cash and partial Udhar amounts via exact rupee-to-paise input, in addition to full cash, UPI pending, and full Udhar. Transaction validation remains in CounterModel. The amount dialogs are not yet manually exercised; this is build-qualified but not UI-qualified. Latest full suites: Node 48/48, Kotlin JVM 23/23, Android 14/14, APK tasks PASS, lint 0 errors / 62 warnings. See ../specs/CT-10-PARTIAL-PAYMENT-UI-REPORT.md.

Latest CT-21/23/24 update - 29 October 2026: added the partial local customer-credit/repayment screen and an API 37 instrumentation scenario for persisted Udhar balance and repayment history. Latest run: Node 48/48, Kotlin JVM 23/23, Android instrumentation 15/15, APK tasks PASS, lint 0 errors / 63 warnings. The repayment-entry dialog has not been manually exercised. See ../specs/CT-21-23-24-CUSTOMER-CREDIT-REPORT.md.


## Latest implementation status — 29 October 2026

Superseding local verification: Node 48/48, Kotlin JVM 24/24, API 37 instrumentation 17/17; debug and instrumentation APK builds pass; lint 0 errors / 64 warnings. CT-17–20 has a partial procurement screen for local suppliers, direct single-item stock receipt, supplier payable and staff-declared cash settlement. Schema v9 migration is additive. See [procurement report](../specs/CT-17-20-PROCUREMENT-REPORT.md). This does not qualify complete purchase orders, receiving, returns, supplier payment verification, or the entire Android UI. All data in the emulator tests is synthetic; sync is not implemented. CAT-051B remains partial and physical microphone/Bluetooth and shop trials remain unqualified.


Current follow-up: CT-17–20 direct receipts can contain up to ten active catalogue items per receipt. Latest run is Node 48/48, Kotlin JVM 25/25, Android instrumentation 17/17, APK builds PASS, lint 0 errors / 68 warnings. Multi-line UI submission has not yet been verified; see the procurement report.


Latest procurement continuation: receipts support up to ten active product lines; receipt-linked return events reduce available stock and outstanding payable and are persisted in encrypted schema v10. The API 37 instrumentation verifies persistence after reopen and screen rendering. Verification now stands at Node 48/48, Kotlin JVM 26/26, Android instrumentation 18/18, APK build PASS, lint 0 errors / 68 warnings. Return-dialog input has not been manually qualified; lot provenance and full procurement workflows remain unsupported.


CT-25 Expenses is now a partial native workflow backed by encrypted SQLite schema v11. It records operating category, exact paise, staff-declared cash/UPI/card method and optional note, with local audit/idempotency/outbox. Latest test counts: Node 48/48, Kotlin JVM 27/27, API 37 instrumentation 19/19, APK builds PASS, lint 0 errors / 70 warnings. Form entry has not been manually exercised; daily closing, staff permissions and sync are not implemented.


## Latest verified local slice - 29 October 2026

The current Android schema is SQLCipher v12. CT-25 has a partial expense form/history and CT-26 has a partial cash-drawer session screen. CT-26 stores counted opening cash, linked declared cash events, reasoned cash-in/out, counted closing cash and any remaining variance. Cash transactions outside an active drawer session remain unlinked; the app does not infer their shift. Latest full tests: Node 48/48, Kotlin JVM 28/28, API 37 instrumentation 20/20; APK builds pass; lint 0 errors / 73 warnings. Manual emulator evidence: `../test-evidence/2026-10-29/ct26-closed.png`. See [CT-26 report](../specs/CT-26-CASH-REGISTER-REPORT.md). This remains prototype scope: staff shifts/identity, daily close, payment verification, production sync, continuous Bluetooth, physical device/field qualification and independent security review are not complete.


## CT-28 session close review - 29 October 2026

The current SQLCipher schema is v13. The CT-28 screen summarizes one selected closed cash-drawer session and stores a review acknowledgment; the acknowledgment survived emulator force-stop/relaunch. It keeps current open orders, pending UPI and customer credit distinct from session cash. Current full tests: Node 48/48, Kotlin JVM 28/28, API 37 instrumentation 21/21; APK builds pass; lint 0 errors / 74 warnings. Evidence: `../test-evidence/2026-10-29/ct28-restart.png`; details in [CT-28 report](../specs/CT-28-DAILY-CLOSING-REPORT.md). It is not a calendar-day accounting report, staff-shift workflow or production closing process.

## CT-27 staff shifts - 29 October 2026

The current SQLCipher schema is v14. CT-27 records an operator-entered staff label at cash-drawer shift start and a receiving label/note plus counted cash at shift end. Shift changes use the existing atomic audited command path. The label is not authenticated; only drawer-linked cash records are included and the screen does not claim complete staff activity attribution. Latest tests: Node 48/48, Kotlin JVM 28/28, API 37 instrumentation 22/22; APK builds pass; lint 0 errors / 75 warnings. Manual start/handover survived force-stop/relaunch on API 37. See [CT-27 report](../specs/CT-27-STAFF-SHIFTS-REPORT.md) and `../test-evidence/2026-10-29/ct27-restart.png`. Full employee authentication, roles, multiple registers, calendar-day close, production sync and field qualification remain open.

## CAT-051B key-loss recovery status — 29 October 2026

Full run PASS: Node 48/48, Kotlin JVM 28/28, Android API 37 instrumentation 23/23, APK builds PASS, lint 0 errors / 77 warnings. Synthetic tests cover local shop identity mismatch rejection after simulated key loss, same-shop restore with a new local Keystore key, old encrypted-file archive, restored sale/payment and duplicate-command behavior. An injected partial import write verifies staging cleanup and write-barrier release. CAT-051B remains open; real storage exhaustion, hard power/process interruption, hardware key invalidation, second-device and second-person recovery, and independent security review are not verified. See [CAT-051B report](../specs/CAT-051B-IMPLEMENTATION-REPORT.md).


## Superseding CAT-051B verification after recovery UI startup fix — 29 October 2026

The recovery screen now opens without attempting to unlock the local database first, and SQLCipher workspace open/refresh are performed away from the Android UI thread. Emulator screenshots and hierarchy evidence are in `test-evidence/2026-10-29/cat051b-recovery-entry.png/.xml` and `cat051b-main-start.png/.xml`. The API 37 full instrumentation rerun after these changes passed 23/23; Kotlin JVM 28/28, Node 48/48, APK builds PASS, lint 0 errors / 77 warnings. CAT-051B still is not complete: storage exhaustion, hard termination/power loss matrix, hardware key invalidation, second-device and second-person recovery, and independent security review remain pending. See the [detailed report](CAT-051B-IMPLEMENTATION-REPORT.md).

## Final CAT-051B continuation verification — 29 October 2026

The restore completion path also reopens SQLCipher off the UI thread and closes any newly opened store if the activity is destroyed. After this final lifecycle change, the complete verification command `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain` completed successfully: Kotlin JVM 28/28, Android API 37 instrumentation 23/23, APK builds PASS, lint 0 errors / 77 warnings. `node --test prototype/*.test.cjs` passed 48/48. The API 37 suite ran after the final Android source changes. Evidence: `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, `android/app/build/reports/lint-results-debug.txt`, and `test-evidence/2026-10-29/cat051b-key-loss-recovery-run.txt`.

CAT-051B remains open; this run did not perform a new SAF export/restore UI drill. Actual storage exhaustion, abrupt process/power loss across journal transitions, hardware Keystore invalidation, second-device and second-person recovery, and independent security review remain pending. Do not treat the prototype as production-ready.

### Latest continuation — 29 October 2026

Shop profile and product catalogue database access/mutations use the shared recovery executor so SQLCipher work does not block the UI thread. Synthetic API 37 interaction evidence includes opening stock, order #12 fulfillment plus staff-declared cash, and order #13 READY state. Latest complete command results: Kotlin JVM 28/28, API 37 instrumentation 23/23, APK tasks PASS, lint 0 errors/83 warnings; Node suite PASS 48/48. UI screenshots and the honest CAT-051B recovery status are documented in `../specs/CAT-051B-IMPLEMENTATION-REPORT.md`.

The older supplied backup failed header authentication safely; do not treat it as a successful or valid backup. No fresh UI SAF export + clean-install restore was completed in this continuation. CAT-051B, hardware and field qualification, authenticated sync, and full product/screen scope remain incomplete.

### CT-29 Daily Reports — 30 October 2026

`DailyReportActivity` is a partial native report view backed by encrypted local records. It distinguishes recorded sales, staff-declared cash, UPI pending, receivables, refunds, expenses, supplier payables and delivered quantities. It does not claim a daily date range or profit. The screen's persisted-value and limitation disclosures are checked by API 37 instrumentation. Latest full run: Node 48/48, Kotlin JVM 28/28, Android instrumentation 24/24, APK tasks PASS, lint 0 errors/84 warnings. The exact evidence locations are in `../specs/PROJECT-PROGRESS-AUDIT-2026-10-29.md`.

### CT-30 Business Alerts — 30 October 2026

`BusinessAlertsActivity` reads local encrypted orders, UPI pending, credit, stock confirmations, supplier balances, cash drawer sessions and cash differences. It identifies low-stock thresholds, credit due-date aging and sync alerts as unavailable until the required configuration/server data exists. API 37 instrumentation checks the empty state and disclosures. Latest integrated verification: Node 48/48, Kotlin JVM 28/28, Android instrumentation 25/25, APK tasks PASS, lint 0 errors/84 warnings. This is a partial screen; see the master tracker for missing alert coverage and product gates.

### CT-30 Business Alerts and latest integrated run — 30 October 2026

CT-30 reads grounded local alerts from the encrypted ledger and states when low-stock thresholds, overdue-credit dating and server-sync error data are unavailable. CT-29 Daily Reports and CT-30 Alerts have persisted/empty-state instrumentation coverage. Latest complete run: Node 48/48, Kotlin JVM 28/28, Android instrumentation 25/25, APK tasks PASS, lint 0 errors/84 warnings. The prior CT-28 launch timeout was followed by a passing full rerun after its DB access was moved off the UI thread. CAT-051B is still open; no fresh SAF clean-install restore drill was completed. These partial screens do not qualify the rest of CT-01–32 or production readiness.

### CAT-051B SAF caller update — 30 October 2026

SAF streams used for backup verification and restore are now closed deterministically. The full run after the change passed Node 48/48, JVM 28/28, API 37 instrumentation 25/25, APK tasks and lint (0 errors/84 warnings). Manual UI attempt reached the Downloads picker, synthetic file selection and secret entry, then stayed on the disabled busy screen without metadata review. It is not a passing recovery drill. CAT-051B remains open; see `../specs/CAT-051B-IMPLEMENTATION-REPORT.md` and `../test-evidence/2026-10-30/`.

### CT-31 Voice Settings — 30 October 2026

The partial native screen stores `hi-IN`, `mr-IN` or `en-IN` and per-product aliases in encrypted local storage. API 37 instrumentation verified persistence, audit recording and no sale created by changing settings. Latest full results: Node 48/48, Kotlin JVM 28/28, Android instrumentation 26/26, APK tasks PASS, lint 0 errors/92 warnings. This is not a microphone, continuous-session or Bluetooth qualification. See `../specs/CT-31-VOICE-SETTINGS-REPORT.md`.

### CAT-051B secret-dialog dismissal fix — 30 October 2026

The SAF recovery-secret dialog moves when the keyboard opens. Its outside/back dismissal previously left the app busy before verification started. Cancel, Back and outside dismissal now clear secret fields and release the temporary busy state. API 37 cancellation regression and SAF `content://` verification callback passed. Manual UI returned `Backup header authentication failed` for the selected synthetic file and did not replace active data; clean-install UI restoration remains unqualified. Latest full results: Node 48/48, Kotlin 28/28, Android 28/28, APK tasks PASS, lint 0 errors/94 warnings. See `../specs/CAT-051B-IMPLEMENTATION-REPORT.md` and `../test-evidence/2026-10-30/verification.txt`.

### Final full verification — 30 October 2026

Latest full current-tree run is recorded in `../test-evidence/2026-10-30/node-full-final.log` and `gradle-full-final.log`: Node 48/48, Kotlin JVM 28/28, Android API 37 instrumentation 28/28, APK tasks pass, lint 0 errors / 94 warnings. The UI now releases its busy state on secret-dialog cancellation. SAF content-URI verification is instrumented; successful verification does not mean the restore completed. A manual attempt returned header authentication failure without replacement. CAT-051B remains open.

### CAT-051B synthetic SAF round-trip qualification — 30 October 2026

The API 37 emulator completed a same-emulator clean-install round trip through the user-facing SAF UI. Create encrypted backup wrote an encrypted file to Downloads; the app verified it; after app uninstall/reinstall, the file was restored with explicit replacement confirmation. Restored sales, credit/refund/reversal, payment-state, and inventory movements were inspected in their native screens. See `../specs/CAT-051B-IMPLEMENTATION-REPORT.md` for evidence and remaining limitations. This does not qualify cross-device restore, physical device key invalidation, production security, or independent review.

Latest suite result: Node 48/48, Kotlin JVM 28/28, API 37 instrumentation 28/28; APK tasks pass; lint 0 errors / 94 warnings.

### CT-12 Refund & Correction — 30 October 2026

A partial native refund/correction screen is registered from Counter and Sales History. It supports reasoned cancellation before handover, item-return review and amount-correction review through the encrypted transaction model. Cash refund confirmation is explicit and staff-declared. Returned stock is not restocked while product returnability policy is unconfigured. Android instrumentation verifies eligible-order screen rendering; complete dialog submission still requires a manual emulator qualification. See `../specs/CT-12-REFUND-CORRECTION-REPORT.md`.

### CT-12 manual synthetic item-refund drill — 30 October 2026

A one-item ₹15 refund of order #14 was completed through the native review dialogs on API 37. The reason and cash-back confirmation were entered; cash was recorded as staff-declared. Persisted order/history and the inventory screen showed refunded ₹15, paid ₹0, revision 2, a negative cash refund event, and no stock restock. Evidence: `../test-evidence/2026-10-30/ct12-refund-committed.png`, `ct12-postrefund-history.png`, `ct12-postrefund-inventory.png`. This qualifies one synthetic UI path only.

### CT-04 Device Permissions — 30 October 2026

The app includes an optional permission explanation/request screen from Welcome and Counter. It asks for microphone/Bluetooth only after user action, preserves typed fallback, and defers camera permission until scanning exists. API 37 instrumentation checks the screen content; allow/deny flows remain pending. See `../specs/CT-04-DEVICE-PERMISSIONS-REPORT.md`.

## CT-03 owner access implementation — 30 October 2026

The app now has an owner access screen with first-run enrollment and subsequent sign-in. The encrypted SQLCipher database stores a salted PBKDF2-HMAC-SHA256 verifier (600,000 iterations); five failed attempts cause a persisted 60-second lockout. Successful sessions are memory-only and can be explicitly locked. The counter and business activities enforce the session gate. Unauthenticated recovery can restore an encrypted backup but cannot export one.

Manual API 37 smoke passed: enroll synthetic owner → enter counter → lock → sign in again. Automated owner/migration coverage passed. Current verified suites: Node 48/48, Kotlin JVM 28/28, Android instrumentation 31/31; debug and instrumentation APK builds pass; lint 0 errors / 103 warnings. See `../specs/CT-03-LOCAL-OWNER-ACCESS-REPORT.md` and `../test-evidence/2026-10-30/ct03-qualification.txt`.

This remains a local single-owner prototype. Separate staff identities, role-based authorization, idle lock, passphrase reset, online identity and independent security review are not implemented. The overall six-wave product plan remains open.

### Superseding CT-04 and current verification — 30 October 2026

CT-04 now distinguishes permission states before request, after a retryable denial, and when Android Settings should be used; denied microphone access continues to expose typed fallback. API 37 instrumentation asserts this behavior with optional permissions revoked. Manual Android permission-dialog allow/deny and Settings-return remain pending. Latest complete suites: Node 48/48, Kotlin JVM 28/28, API 37 instrumentation 31/31; APK tasks PASS; lint 0 errors/104 warnings. CT-03 and the full six-wave product remain partial/incomplete. See `../specs/CT-04-DEVICE-PERMISSIONS-REPORT.md`.

### CT-11 order history and SQLite v16 — 30 October 2026

Order creation timestamps are persisted in SQLite schema v16. The v15→v16 migration is additive; existing orders receive `created_at=0` and remain explicitly undated. CT-11 search and inclusive date-range filtering run against local encrypted records; date filters use order creation time, not payment-event time. Tests: Node 48/48, JVM 30/30, API 37 instrumentation 34/34; APK builds PASS; lint 0 errors/104 warnings. See `../specs/CT-11-ORDER-HISTORY-REPORT.md`. Manual usability review remains pending.

## Superseding CT-10 payment status — 30 October 2026

The native PaymentActivity supports cash collection, UPI pending, customer-credit entry and an explicit follow-up for a matching pending UPI attempt. Follow-up is recorded as a staff-declared UPI receipt, not provider-verified; the attempt cancellation and collection are append-only and idempotent. Latest complete run: Node 49/49, Kotlin JVM 31/31, Android API 37 instrumentation 36/36; debug and instrumentation APK tasks passed; lint 0 errors / 107 warnings. See [CT-10 payment continuation report](../specs/CT-10-PAYMENT-FOLLOWUP-REPORT.md). This remains a prototype and does not qualify the complete payment module or external UPI integration.

## Superseding verification — native payment confirmation — 30 October 2026

API 37 accessibility UI automation submitted a ₹7.50 amount and confirmed the staff-declared UPI receipt through PaymentActivity. The order's partial paid balance, append-only pending-attempt cancellation, `staff_declared` receipt, and absence of provider-verified events were asserted. Latest complete run: Node 49/49, Kotlin JVM 31/31, Android instrumentation 37/37, APK tasks PASS, lint 0 errors / 107 warnings. The clean-install recovery UI drill is evidenced by the encrypted artifact and screenshots listed in CAT-051B report; only same-emulator reinstall is qualified. Cross-device recovery and production security gates remain open.

## Superseding CT-22 status — 30 October 2026

The Customer Directory links to a new local Customer Details activity. Latest tested run: Node 49/49, Kotlin JVM 31/31, API 37 instrumentation 38/38 (118 total); APK build tasks pass; lint 0 errors / 107 warnings. CT-22 is a partial local ledger view, not full customer CRM. Contact profiles, search, dated history, messaging consent, authenticated access, and sync are not provided by this screen.

## Superseding CT-21/CT-22 status — 30 October 2026

The local directory searches existing customer account IDs case-insensitively and opens a details screen with persisted credit/repaid/reversed/due values, order links and payment/repayment events. Full verification: Node 49/49, Kotlin JVM 31/31, Android instrumentation 39/39 (119 total), APK tasks PASS, lint 0 errors / 107 warnings. Customer profiles, contact management, messaging, and cloud CRM remain unimplemented.

## Superseding CT-21 profile delivery — 30 October 2026

Schema v17 introduces the encrypted local customer profile table. CT-21 supports account ID/name, optional consented phone (masked on screen), case-insensitive ID/name search, and links to CT-22. UI tests cover profile creation; persistence tests cover audit/outbox, PII exclusion, consent validation, duplicate prevention, schema upgrade, and encrypted backup/restore. Complete results: Node 49/49, Kotlin JVM 31/31, instrumentation 42/42, APK tasks PASS, lint 0 errors / 108 warnings. No profile editing or messaging integration is claimed.

### Superseding CT-21 profile maintenance — 30 October 2026

Profile editing and contact revocation are available in the directory. Revocation clears the saved number; edit writes are atomic and include redacted audit, command-receipt, and sync-outbox entries. API 37 tests cover create/edit UI and persisted audit/credit invariants. Full verification: Node 49/49, Kotlin JVM 31/31, Android instrumentation 43/43, APK tasks PASS, lint 0 errors / 109 warnings. Profile deletion, retention controls, verified consent evidence, and CRM messaging are not implemented.

### Superseding CT-24 repayment UI qualification — 30 October 2026

An API 37 accessibility test now submits a partial cash repayment through the customer directory. A fresh SQLCipher read checks the resulting receivable and staff-declared event. Verification: Node 49/49, Kotlin JVM 31/31, Android instrumentation 43/43, APK build tasks PASS, lint 0 errors / 109 warnings. Only this repayment UI path is qualified; receipts and other repayment methods are absent.

### Superseding CT-16 multi-product count — 30 October 2026

Inventory Overview links to a physical stock-count screen. Selected counts (including zero) commit together with audit, stock movements, idempotency receipt, and outbox entry; count corrections do not create purchase/payable records. API 37 UI and Kotlin JVM tests cover persistence, rollback and duplicate rejection. Current full verification: Node 49/49, Kotlin JVM 32/32, Android instrumentation 44/44, APK tasks PASS, lint 0 errors / 110 warnings. Variance approval and count-session history are not implemented.

### Final CT-16 validation UI check — 30 October 2026

An invalid selected quantity now displays an inline message while preserving the other selected products and their values. API 37 UI automation verifies correction and durable commit. Latest full suite: Node 49/49, Kotlin JVM 32/32, Android instrumentation 44/44, APK tasks PASS, lint 0 errors / 113 warnings.

## CAT-052 Android outbox payload status — 30 October 2026

The encrypted SQLite schema is v18. Supported order, stock and non-customer-linked payment mutations create a minimized `projection_delta.v1` payload in the outbox. Customer-linked credit and repayment changes are blocked because current account IDs may be names or phone numbers; historical events without safely reconstructable payloads and other unsupported entities are also blocked. The outbox's `pending` status means local payload-ready only; there is no Android uploader, acknowledgement, server revision cursor or authenticated sync yet. Current verification: Node 49/49, Kotlin JVM 33/33, API 37 instrumentation 44/44; APK tasks pass; lint 0 errors / 113 warnings. See `../specs/CAT-052-SYNC-JOURNAL-REPORT.md`.


## CT-06 search and filter continuation — 30 October 2026

Quick Sale now searches case-insensitively across configured product name, stable ID, category and voice/search aliases. A category selector filters all confirmed-price products, favorites or a specific product category. No-match feedback distinguishes an empty result from an empty catalogue. The UI still uses the same confirmed catalogue prices and does not alter the transaction engine. API 37 instrumentation seeded a synthetic favorite product, confirmed its opening quantity, launched Quick Sale, verified mixed-case alias search, category filtering, favorite filtering and the no-match state. Current full verification: Node 49/49, Kotlin JVM 33/33, Android instrumentation 45/45, APK build tasks PASS, lint 0 errors / 114 warnings. Evidence is in `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` under `quickSaleSearchAndCategoryFiltersUseConfiguredProductAliases`. CT-06 remains partial: no UI-level UPI/Udhar exercise, customer lookup, split tender, receipt, discount policy, or accessibility/manual usability qualification.

## CT-30 / SQLite v19 continuation

The current encrypted schema is v19. Product catalog entries have an optional low-stock threshold. CT-30 reports a threshold breach only from physically confirmed local stock. The v18→v19 migration preserves existing product/stock/order records; no threshold is inferred for existing products. Focused migration and alert rendering tests passed. The latest full instrumentation run was 46/47 because customer profile revocation intermittently retained the entered phone in the full-suite UI flow; its isolated rerun passed. Kotlin JVM 35/35, Node 49/49, backend payload contract 5/5, APK tasks pass, lint 0 errors/114 warnings. This prototype still has no Android sync client; outbox `pending` remains local payload-ready, not server-accepted.


### Latest local verification ? 30 October 2026

Latest checked run: Node 49/49; Kotlin JVM 35/35; Android API 37 instrumentation 47/47; backend projection contract 5/5. Android Gradle unit tests, debug APKs, lint and emulator instrumentation passed. Lint reports 0 errors / 114 warnings. This qualifies only the covered prototype flows on the emulator. It does not qualify physical Bluetooth input, continuous listening, real-shop use, cross-device recovery, independent security, or production deployment.

### Superseding CAT-052 account/manual sync slice — 30 October 2026

Shop Home now links to the owner-gated Cloud Sync prototype. `SyncAccountClient` uses the HTTPS-only transport for server registration, login on a device already registered to the same shop, and server-confirmed logout. The screen stores session secrets with Android Keystore AES-GCM, offers an explicit manual sync action, and reports accepted count or a revision conflict. It does not schedule background work. The server/device IDs retained separately are non-secret local values for re-login on this same installation; fresh-device enrollment into an existing shop is not implemented. Account API tests use synthetic fake responses, and the screen smoke test does not contact a server.

Latest full current-source verification: prototype Node 49/49; Kotlin JVM 35/35; Android API 37 instrumentation 62/62; backend contract 5 passed with 4 PostgreSQL integration tests skipped because the database was not running; debug/instrumentation APK builds PASS; lint 0 errors / 128 warnings. Evidence is at `../test-evidence/cat052-session-vault/full-instrumentation-with-account-ui.xml`. No real authenticated HTTPS request, production backend projection, WorkManager retry, cross-device enrollment, Bluetooth hardware or field qualification is claimed.

## CAT-052 WorkManager continuation — 30 October 2026

Added durable one-time and owner-enabled six-hour background sync scheduling through WorkManager 2.12.0. Jobs require connected network, use exponential backoff, carry no credential or business payload in WorkManager data, and process up to five eligible events per run before queueing a continuation. A process lock serializes manual sync, account/session replacement, logout and the background worker. The worker observes the backup/recovery write barrier, uses the existing Keystore session and encrypted allow-listed outbox, and records only non-sensitive status. Cloud Sync now offers queue, periodic-enable and pause actions; logout cancels queued jobs and waits for in-flight sync before revoking/clearing credentials. Session decryption was moved off the activity main thread.

Final-source verification: Node 49/49; Kotlin JVM 35/35; Android worker instrumentation 3/3; debug and instrumentation APK compilation/build PASS; lint 0 errors / 138 warnings. Backend tests 5 passed / 4 skipped because PostgreSQL integration was not running. Full Android instrumentation did not complete: emulator services failed during the 65-case run, and isolated Cloud Sync screen launches hit a Pixel Launcher ANR before the test could launch the activity. Worker runner and Gradle logs are under `test-evidence/cat052-workmanager/`; launcher ANR screenshots and failing UI XML are recorded there or under Android build outputs. This is not a complete Android regression pass and the modified screen remains emulator-unqualified.

CAT-052 is still not end-to-end sync qualified: no live HTTPS request occurred; server financial projections, new-device enrollment, conflict-resolution UI and production server operation are absent. The full app suite must be rerun on a stable emulator. Physical device behavior, field qualification, independent security review and product release readiness remain open.

Superseding lint verification after the final Cloud Sync main-thread change: `:app:lintDebug` passed with 0 errors and 139 warnings. `:app:testDebugUnitTest` also passed after that change; the JVM report remains 35/35. The warning count supersedes the earlier 138-warning line in this continuation.
# Saved customer selection in counter credit flows — 31 October 2026

## Current recovery qualification - 22 November 2026

The isolated API 37 emulator passed the current-source SAF export, app uninstall/reinstall, and rich synthetic clean-install restore UI drill. Full instrumentation passed 101/101, Kotlin JVM 63/63, APK build tasks passed, and lint reported 0 errors / 171 warnings. CAT-051B remains partial for hard power/storage failure, physical key invalidation, separate-device/person, OEM-transfer and independent security review. Details: [`../specs/CAT-051B-IMPLEMENTATION-REPORT.md`](../specs/CAT-051B-IMPLEMENTATION-REPORT.md) and [`../specs/PROJECT-PROGRESS-AUDIT-2026-11-22.md`](../specs/PROJECT-PROGRESS-AUDIT-2026-11-22.md).



CT-06 Quick Sale, CT-10 Payment, and CT-08/09 Live Orders Udhar now share `CustomerAccountPicker`. It searches persisted profiles and legacy credit IDs, displays outstanding balances, and requires choosing an existing account before creating a receivable. The directory can be opened to add a profile; no account is silently created by the sales flow. JVM coverage is `CustomerAccountPickerTest` (3 cases); emulator UI coverage verifies selection in both Quick Sale and Live Orders. See `../specs/CT-06-10-CUSTOMER-PICKER-REPORT.md` for the current regression results and scope limitations.
# CT-06 atomic cash + Udhar split

Quick Sale supports an immediate split between staff-declared cash and a saved customer's receivable. `CounterModel.quickSaleSplit` validates the confirmed catalogue price, available confirmed stock, customer ID and strict partial-cash range, then persists the order, payment entries, credit record and stock movement through one mutation. The matching Kotlin tests cover allocation, duplicate prevention, invalid inputs and injected persistence rollback. API 37 UI instrumentation enters an amount, chooses a saved profile and checks the persisted order/payment/credit/stock state. See `../specs/CT-06-10-CUSTOMER-PICKER-REPORT.md` for current full-suite results and scope.

# CT-11 to CT-12 refund UI regression — 31 October 2026

The API 37 instrumentation suite now drives a synthetic sale from Sales History into the refund screen, enters a return reason, explicitly confirms the staff-declared cash refund, and verifies the refreshed balance plus persisted payment/audit events and unchanged stock where no returnability policy exists. Latest full verification is Node 49/49, Kotlin JVM 40/40, Android instrumentation 70/70, APK tasks PASS, lint 0 errors / 139 warnings. Preserved evidence is in `../test-evidence/2026-10-31/ct11-ct12-regression.md` and its XML report. The screen remains a partial prototype; manual usability, all refund paths, staff roles and production qualification remain open.

## CAT-051B partial-export failure regression — 1 November 2026

Added and passed an API 37 instrumentation test that writes an encrypted backup prefix, injects a destination failure, then verifies active business data is unchanged, private snapshot staging is cleaned, the write barrier is released and a later sale succeeds. Full current-source verification: Node 52/52; Kotlin JVM 45/45; API 37 instrumentation 75/75; debug and instrumentation APK tasks PASS; lint 0 errors/141 warnings. See `../test-evidence/2026-11-01/cat051b-final-gradle-rerun.log` and `../specs/CAT-051B-IMPLEMENTATION-REPORT.md`. This test does not qualify actual storage exhaustion, abrupt power/process loss, physical Keystore invalidation or cross-device recovery.

### SAF destination cleanup behavior — 1 November 2026

After the above run, failed SAF exports attempt to delete their incomplete destination; when the document provider refuses, the UI warns the owner to remove it manually. JVM suite 46/46, Node 52/52, debug/test APK build tasks pass, lint 0 errors/141 warnings. The AVD was unavailable to ADB during the focused runtime retry, so 75/75 is the last complete Android instrumentation result before this final UI change. See `../test-evidence/2026-11-01/cat051b-export-cleanup-gradle.log`, `cat051b-export-cleanup-focused-instrumentation.log` and the CAT-051B report.

### CT-09 audit timeline continuation — 1 November 2026

Order Details displays persisted audit timestamps using local device time and explicitly warns that the device clock may be inaccurate and is not server verified. Source checks: Node 52/52, Kotlin JVM 46/46, APK build tasks PASS, lint 0 errors/141 warnings. The focused emulator instrumentation rerun is pending because the AVD did not register with ADB; the last full 75/75 emulator result predates this revision. See `../specs/CT-09-ORDER-DETAILS-REPORT.md` and `../test-evidence/2026-11-01/ct09-audit-timeline-gradle.log`.

### CAT-051B maintenance barrier continuation — 1 November 2026

The process-wide write barrier now restores an enclosing label after a nested maintenance action and isolates exceptions from initial listener registration. Latest source checks pass Node 52/52, Kotlin JVM 48/48, APK build tasks, and lint (0 errors/141 warnings). The focused connected instrumentation task is blocked because ADB has no connected device; the 75/75 full API 37 run predates this source change. Evidence: `../test-evidence/2026-11-01/cat051b-barrier-gradle.log`.

### Owner dashboard identifier handoff — 1 November 2026

The owner-only Cloud Sync screen now displays selectable shop/device IDs after registration or when a saved identity is loaded. These IDs are identifiers, not credentials; the bearer session stays encrypted in Android Keystore. They let the owner sign in to the same-origin OW-01 prototype using the registered owner email/password. The display is compiled but awaits emulator runtime verification. See `../specs/OW-01-OVERVIEW-REPORT.md`.

### CT-29 local report CSV export — 1 November 2026

Daily Reports now exports the current local ledger summary through Android's Storage Access Framework `CreateDocument` picker. It requests no broad storage permission, reloads the encrypted ledger at export time, and runs database/file work on the shared executor. The CSV explicitly labels itself as all local records rather than calendar-day data, keeps pending UPI separate from staff-declared cash, discloses that values are not profit/verification, and escapes formula-like text fields. Kotlin JVM is 50/50; APK build tasks pass; lint reports 0 errors/146 warnings. The latest ADB inventory has no connected device, so the picker/write lifecycle has not been emulator-qualified. See `../specs/CT-29-CSV-EXPORT-REPORT.md` and `../test-evidence/2026-11-01/ct29-csv-export-gradle-final.log`.
### CT-16 stock count review — 1 November 2026

The stock-count screen reviews each selected product before committing a count. It shows a signed difference only when the stock baseline was confirmed; otherwise it identifies the entry as opening stock. Editing or dismissing the dialog does not post movements; explicit confirmation invokes the existing atomic audited count operation. JVM tests and APK/instrumentation APK builds passed, but this screen revision is not emulator-qualified because ADB had no connected device. See [`CT-16 report`](../specs/CT-16-STOCK-COUNT-REVIEW-REPORT.md).
# CT-17 purchase order drafts — 1 November 2026

The Procurement screen can save a supplier-linked, multi-product draft without changing inventory or payables. An owner can mark it sent outside the app, then post one or more receipts against ordered remaining quantities. Each receipt links to the draft and posts through the established stock/payable/audit transaction. SQLCipher schema v21 migrates additively from v20. JVM tests pass; the Android migration/partial delivery test builds but awaits a connected emulator run. See [`CT-17 report`](../specs/CT-17-PURCHASE-ORDER-DRAFT-REPORT.md).

### CT-17 purchase orders (1 November 2026)

The local Procurement workflow can persist supplier-linked multi-line order drafts, record that an owner sent them outside the app, cancel before delivery, and receive partial/final quantities against the order. Draft values do not post stock or supplier payables. SQLCipher v20→v21 is additive. Focused API 37 migration and receiving-screen instrumentation tests passed 1/1 each. Current Node 52/52 and Kotlin JVM 54/54; APK build tasks pass; lint 0 errors/154 warnings. The pre-fix full 76-test Android run had 18 failures, including four migration replay failures corrected with idempotent table creation and UI failures during emulator System UI ANRs. The full suite is not qualified after that correction. Details and logs: `../specs/CT-17-PURCHASE-ORDER-DRAFT-REPORT.md` and `../test-evidence/2026-11-01/`.

### Current API 37 regression (1 November 2026)

The post-fix full Android instrumentation suite passes 76/76 on the API 37 AVD, including CT-16 stock-count review and CT-17 purchase-order migration/partial receiving. Node 52/52 and Kotlin JVM 54/54 pass; debug and test APK builds pass; lint 0 errors/154 warnings. The current Welcome screen capture and full suite artifacts are stored under `../test-evidence/2026-11-01/`. This qualifies the tested prototype flows only. Physical Bluetooth, real-shop behavior, cross-device recovery, independent security review and release readiness are still unqualified.

### CT-18 supplier invoice references — 1 November 2026

Receipt entry now accepts an optional manually entered supplier invoice reference. SQLCipher schema v22 migrates additively from v21; references are trimmed, validated and unique per supplier. The procurement receipt list displays the reference. Node 52/52 and Kotlin JVM 55/55 pass. Focused API 37 migration and receipt-screen tests pass 1/1 each. Debug APKs build; lint 0 errors/154 warnings. The full 77-case instrumentation attempt was not green (63 passed, 14 UI failures) after the low-memory emulator showed Pixel Launcher/System UI ANRs; an isolated partial-UPI screen rerun also hit System UI ANR. See [CT-18 report](../specs/CT-18-SUPPLIER-INVOICE-REFERENCE-REPORT.md) and `../test-evidence/2026-11-01/ct18-*`. This field is not an attached or verified invoice. Full product, CAT-051B, sync, Bluetooth/device, field and production gates remain open.

### CT-20 supplier settlement update — 1 November 2026

The Procurement screen now records staff-declared cash or UPI payments against supplier payables. UPI is not provider-verified and does not affect cash-drawer reconciliation. Cash/UPI method validation, duplicate prevention, overpayment bounds, ledger persistence and integrity checks pass in Kotlin JVM 56/56. The focused API 37 procurement display test passes 1/1 with both payment records. No database migration was needed. APK builds pass and lint is 0 errors/154 warnings. Current full Android regression has not been rerun after this change; the preceding full run passed 63/77 before emulator UI ANRs disrupted 14 cases. See [`CT-17–20 report`](../specs/CT-17-20-PROCUREMENT-REPORT.md) and `../test-evidence/2026-11-01/ct20-*`. This is not payment-provider verification or complete supplier accounting.
# Latest verified source — 2 November 2026

CT-19/20 supplier profiles now store an optional business phone in SQLCipher schema v23. The additive v22→v23 migration was exercised with an existing supplier receipt/payable; contact set, validation, clearing, UI edit and reopen behavior passed in API 37 instrumentation. CT-03 owner sign-in now also has an end-to-end synthetic UI test. The full API 37 suite passed 82/82 (0 failures/errors/skips); Kotlin JVM 56/56; prototype Node 52/52; APK and instrumentation APK builds passed; lint 0 errors / 155 warnings. Details and JUnit evidence: [`CT-17–20 report`](../specs/CT-17-20-PROCUREMENT-REPORT.md) and [`current verification`](../test-evidence/2026-11-02/ct03-current-verification.md). This is emulator prototype qualification. Staff credentials, supplier contact privacy review, physical device, field, production security, sync and full product qualification remain open.


## Current source update — 2 November 2026

The latest full API 37 instrumentation run passed 83/83; two additional focused staff UI tests each passed 1/1, covering cashier sign-in/menu visibility and owner staff-account creation/disable. Kotlin JVM 56/56, prototype Node 52/52, backend 11 passed with 4 PostgreSQL-dependent cases skipped; debug and instrumentation APK builds passed and lint reports 0 errors / 161 warnings. The 85 instrumentation methods were exercised across the full run and focused runs; the full-suite XML predates the two focused UI methods. CT-03 adds local SQLCipher staff credentials, owner-managed account enable/disable, PIN lockout, and cashier/manager route gates. Session timeout, actor-attributed transaction auditing, production identity, physical device qualification and independent security review remain incomplete. See [CT-03 staff access report](../specs/CT-03-STAFF-ACCESS-REPORT.md) and [dated verification evidence](../test-evidence/2026-11-02/ct03-staff-verification.md).

## CT-03 audit actor attribution — 2 November 2026

SQLCipher schema v25 adds nullable `audit_events.actor_id`; the additive v24→v25 migration preserves previous audit rows. Shared transaction commits and direct audited persistence writes store the authenticated local actor where available. Successful owner/staff authentication is attributed; failed login events and historical records remain null by design. Selected API 37 tests cover actor persistence across encrypted reopen, owner authentication, catalogue/shop profile writes and schema migration. Final full API 37 instrumentation: 87/87 passed; current cross-suite verification is Node 52/52, Kotlin JVM 56/56, backend 11 passed / 4 PostgreSQL-dependent skipped; APK builds passed and lint is 0 errors / 161 warnings. Actor tests are not exhaustive, role authorization for every direct mutation remains open, and this is not server identity. See [actor attribution verification](../test-evidence/2026-11-02/ct03-actor-attribution-verification.md) and `../test-evidence/2026-11-02/ct03-actor-attribution-full-instrumentation-final.xml`.

**CT-03 focused staff actor assertions (2 November 2026):** Account creation by the local owner, successful staff login, unauthenticated failed login and manager-triggered account disable now have explicit actor assertions. API 37 focused instrumentation passed 1/1. Evidence: `../test-evidence/2026-11-02/ct03-staff-account-actor-attribution-focused.xml`. This does not complete role/action authorization, background actor policy, session timeout, remote identity or production security review.

23 November 2026 verification note: the prior isolated runner excluded only `LiveBackendSyncE2ETest`; it now excludes all three endpoint-dependent live sync E2E test classes. A broad 93-test attempt on API 37 failed after 39m13s (37 failures); SAF/launcher stalls and loss of Package Manager occurred, and the repayment UI test failed to locate its item under that run. Following a cold AVD restart the focused repayment test passed 1/1. The corrected broad wrapper has not yet been rerun, so do not report the full Android suite green.

**23 November 2026 — default runner qualification:** Current-source API 37 suite passed 117/117 with zero failures/errors/skips after `OfflineQualificationRunner` injected all three live-sync endpoint test exclusions by default. `InstrumentationConfigurationTest` verifies the default exclusion list; focused rerun also passed after adding opt-in policy coverage. Kotlin JVM 69/69; `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` passed; lint 0 errors/176 warnings. The earlier 93/37 and 118/2 runs were failed investigation runs and are superseded for default offline suite qualification. Live CAT-052 E2E tests remain separately endpoint-gated, and physical-device, production, independent security and shop-field gates remain open.

The `InstrumentationConfigurationTest` also passed 1/1 in explicit opt-in mode with `-Pcat052LiveSyncE2e=true` (configuration test selected alone; no live server test executed).

24 November 2026 regression update: the current-source API 37 default Android instrumentation suite passes 121/121 (0 failures/errors/skips), including the real SAF synthetic export/restore/replacement drill. The prior lone failure was corrected in the picker test helper: it now edits the actual filename field to avoid collisions and activates the exact Save action, then verifies return to the app. Kotlin JVM 59/59, prototype Node 52/52, backend Node 26 passed / 4 PostgreSQL-dependent skipped; APK build tasks pass; lint 0 errors / 176 warnings. This is emulator-only synthetic coverage. CAT-051B hard interruption and independent security gates, physical Bluetooth/microphone and field trials, authenticated production sync, full Android/owner screen scope, and release readiness remain open. See `../specs/CAT-051B-SAF-UI-QUALIFICATION-2026-11-24.md` and `../test-evidence/2026-11-23/android-full-regression-follow-up/`.

### OW-04 supplier settlement — Android sync qualification, 24 November 2026

The isolated API 37 Android-to-PostgreSQL supplier finance test now requests the owner cashflow projection after syncing and asserts the supplier settlement appears separately as a staff-declared outflow. Focused instrumentation passed 1/1; the full restricted-role PostgreSQL suite passed 30/30. This adds a connected cashflow assertion to the supplier/expense/customer outbox E2E. See [`OW-04 report`](../specs/OW-04-CASHFLOW-REPORT.md) and [`CAT-052 supplier report`](../specs/CAT-052-SUPPLIER-FINANCE-SYNC-REPORT-2026-11-23.md). It remains synthetic local qualification; visual browser, production, physical-device, and field gates remain open.
### CT-27 authenticated local shift ownership — 24 November 2026

New staff shifts now use the signed-in local owner/staff account; schema v30 stores the actor ID locally, and v29 migration preserves old shifts with an unknown actor. Cashiers can start/end their own shift and view only their history; managers can review other staff shifts. The actor ID and labels are not sent in the anonymous shift sync projection. Focused API 37 tests: 5/5 passed. Node prototype: 52/52; Kotlin JVM: 81/81; backend Node: 30 passed / 5 PostgreSQL-dependent skipped; APK tasks passed; lint 0 errors / 182 warnings. The full offline instrumentation run was not green: 137 tests, 99 passed and 38 failed after UI automation encountered Android System UI “not responding” dialogs; all five CT-27-focused cases passed. See [CT-27 report](../specs/CT-27-AUTHENTICATED-LOCAL-STAFF-SHIFTS-REPORT-2026-11-24.md). This does not qualify physical devices, attendance/payroll, shop/field use, cross-device identity, independent security review, Bluetooth voice or production release.

## Latest continuation - CT-13/14 configured base-unit labels - 25 November 2026

Added owner-configurable product sell/count unit labels across catalogue, Quick Sale, order details/live orders, inventory, stock counts, movement history and stock-count CSV. SQLite schema v30→v31 applies an additive `unit_label` column with legacy default `units`; a focused migration test confirms stock and sale history are preserved. Labels cannot be changed after an order line or stock movement exists. Labels do not convert pack/carton/piece quantities. Kotlin JVM 89/89; API 37 focused Quick Sale unit-display test 1/1 and schema migration test 1/1; debug and instrumentation APK tasks PASS; lint 0 errors / 189 warnings. Node and full Android instrumentation were not rerun. Details/evidence: [CT-13/14 report](specs/CT-13-14-BASE-UNIT-REPORT-2026-11-25.md), `test-evidence/2026-11-25/ct13-base-unit/`. The catalogue/editor screens and product remain partial; hardware, security-review, field and production gates remain open.

## CAT-051B same-AVD clean-install recovery — 26 November 2026

The dedicated API 37 recovery AVD completed the actual encrypted SAF export, app-package removal/fresh install, and SAF restore path. The backup persisted outside private app storage. Focused rich-data, UI export/restore and clean-install restore tests each passed 1/1; restored sales/refunds/credit/repayments/payments/stock/audit/idempotency were asserted. See [CAT-051B clean-install report](../specs/CAT-051B-CLEAN-INSTALL-RESTORE-REPORT-2026-11-26.md). This is same-AVD recovery qualification, not cross-device/person recovery, physical key-loss/power/storage qualification, independent review, or production readiness.
## CT-13/14 direct purchase-unit receipt UI — 26 November 2026

A dedicated API 37 rerun now passes `directProcurementScreenConvertsConfiguredCratesToBottleStock` 1/1. The actual Procurement screen accepted 2 crates × 12 bottles, posted 24 bottles, updated the supplier payable and left an audit trail after SQLCipher reopen. The earlier headless launcher ANR remains as historical troubleshooting but no longer blocks this exact scenario. The rest of procurement and CT-13/14 are partial. See [report](../specs/CT-13-14-PURCHASE-UNIT-CONVERSION-REPORT-2026-11-26.md).
## CT-14 Product Editor purchase-unit configuration — 26 November 2026

Focused API 37 UI test passed 1/1: the real editor saved `cup`/`crate`/12 conversion metadata, encrypted catalogue reopen retained it, and Quick Sale displayed the configured sell unit and sold at the configured price. See [CT-13/14 report](../specs/CT-13-14-PURCHASE-UNIT-CONVERSION-REPORT-2026-11-26.md). Full catalogue and procurement qualification remains open.