# CAT-051B - Backup, Restore, Recovery UI and Failure Qualification

## Superseding current-source SAF recovery drill - 19 November 2026

The actual Android recovery UI was exercised again on the API 37 emulator using a fresh, test-generated synthetic archive from the current source. The archive was placed in emulator Downloads by the focused instrumentation test `verifiedBackupRestoresSyntheticFinancialHistoryIntoSeparateStoreWithNewLocalKey` (PASS 1/1). The app was reinstalled to a clean app installation, opened through its exported Welcome screen, and navigated to Backup & Recovery. Through the Android SAF document picker, the archive was selected; the recovery secret was entered; authenticated verification displayed the supported schema/format and shop-identity preview; and replacement was explicitly approved. Restore completed and returned to first-owner setup, which is expected because this archive contains business data rather than a portable login account. A synthetic owner was created, and the reopened counter displayed all three restored orders with their expected statuses and balances.

Evidence: `../test-evidence/2026-11-19/cat051b-current-ui-orders.png`; UI hierarchy captures `recovery-preview.xml`, `recovery-replacement-confirmation.xml`, `recovery-restore-result.xml`, `recovery-owner-created.xml`, and `recovery-orders.xml`; Android result XML `../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`. The generated archive and UI evidence are local ignored test artifacts and are not committed. The focused instrumentation assertions verify multiple orders, staff-declared cash, UPI pending, partial refund, customer credit and repayment, stock movements, audit events, outbox entries, idempotency and new local-key material in a separate restored store. UI runtime evidence confirms that all three order records render after the SAF restore. Together this qualifies the synthetic same-emulator clean-install restore UI flow for current source.

This is not a full UI-driven export-plus-uninstall-plus-restore cycle on 19 November: the archive itself was produced by instrumentation and then consumed through the UI. It also does not exercise a second physical device or person. CAT-051B remains partial because real storage exhaustion, live process termination/power loss, hardware Keystore invalidation, OEM/device-transfer behavior, second-device/person recovery, independent security review, and production security qualification remain open. Do not treat the emulator's synthetic restore as proof of those cases.

## Superseding current-source regression — 19 November 2026

The later current-source full Android run completed successfully: Kotlin JVM 56/56, Android API 37 instrumentation 89/89, debug and instrumentation APK build tasks passed, lint 0 errors / 161 warnings. Current focused Android runs also passed `maintenanceBarrierRejectsAndRollsBackBusinessWritesThenReleases` (1/1) and `verifiedBackupRestoresSyntheticFinancialHistoryIntoSeparateStoreWithNewLocalKey` (1/1). The full SAF picker/export/uninstall/reinstall/restore drill was not repeated in this run; earlier same-emulator synthetic evidence remains the basis for that specific claim. This update does not close CAT-051B. Real storage exhaustion, hard process/power interruption, hardware Keystore invalidation, separate-device/person recovery, full current-source SAF UI replay and independent security review remain open. The first full instrumentation attempt had one intermittent receivable-projection assertion failure; focused and subsequent full runs passed, but its cause was not established. See `PROJECT-PROGRESS-AUDIT-2026-11-19.md`.

**Date:** 29 October 2026  
**Status:** Partial implementation; selected synthetic emulator checks pass. CAT-051B acceptance is **not complete**.

## Implemented

- Added a native Backup & Recovery entry point with encrypted backup and restore actions using Android Storage Access Framework `CreateDocument` and `OpenDocument` contracts. No broad storage permission is requested.
- Backup setup prompts for a recovery secret and confirmation. Restore asks for the secret, performs a cryptographic/schema/business-integrity preflight, shows limited metadata, shows the authenticated generated shop-identity suffix, then requires explicit replacement confirmation. Progress and failure/success status are shown. Secret input fields and in-memory `CharArray` values are cleared on the implemented lifecycle paths; secrets are not intentionally logged or persisted as plaintext.
- Added a process-wide fair read/write maintenance barrier. Existing persistence reads/commits must acquire a permit; maintenance prevents new work and waits for in-flight work. New commits during maintenance fail safely and model rollback restores in-memory state. UI reports maintenance state. Export/restore releases the barrier in `finally`; a durable journal handles process interruption during database cutover before normal DB open.
- Restore stages an encrypted local-key database, verifies SQLCipher and business invariants, preserves the previous database family, cuts over, verifies the active database, and records the imported snapshot before cleanup. Startup recovery restores the prior database when the cutover did not commit, or retains verified imported data when the commit marker exists. Ambiguous/missing recovery files fail closed.
- Added non-mutating financial snapshot checks for order lines, payment/refund allocations, credit projections, payment ledger projections and stock values. Inconsistent snapshots are rejected rather than repaired.
- Added fault-injection coverage at preservation and replacement boundaries, plus a separate named-store recovery scenario with a new local key namespace. This scenario is in the same Android app sandbox and is not a clean-install test.
- Fixed the post-recovery maintenance banner so a completed or failed recovery returns to the accurate ready state rather than continuing to display “operation starting”; the rebuilt app was installed and its ready state was captured.

## Actual verification

| Check | Result | Evidence |
|---|---:|---|
| Node | PASS, 48/48 | `node --test prototype/*.test.cjs` (29 Oct 2026) |
| Kotlin JVM | PASS, 23/23 | `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml` |
| Android instrumentation | PASS, 9/9, 0 failures | `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` |
| Debug APK + test APK build | PASS | `android/app/build/outputs/apk/debug/app-debug.apk`; `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Android lint | PASS, 0 errors / 45 warnings | `android/app/build/reports/lint-results-debug.txt` |
| SAF backup and clean-install restore UI | PASS for synthetic records on API 37 emulator; export file survived app uninstall/reinstall; authenticated preview and explicit replacement were completed | `test-evidence/2026-10-29/cat051b-ui-backup.ctbackup`; `cat051b-backup-created.png`; `cat051b-clean-installed.png`; `cat051b-clean-restore-result.png`; `cat051b-restored-orders.png` |
| Rich synthetic recovery drill | PASS through instrumentation and Android UI; two orders restored, including cash-declared and UPI-pending states; test verifies refunds, credit, repayments, stock, audit and outbox projections in an isolated store | `test-evidence/2026-10-29/cat051b-full-synthetic.ctbackup`; `cat051b-rich-clean-restore.png`; `cat051b-rich-restored-orders.png` |
| Shop identity and device key | PASS for tested path; authenticated identity appeared in preview, blank install adopted backup identity, and local wrapped-key envelope changed after reinstall | Instrumentation XML and the UI evidence above; envelope hashes retained in the local run notes (no key material exported) |

The run command was `\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest --console=plain` from `android/`. Node tests were run separately.

Instrumentation methods include: encrypted DB and missing-key fail-closed, SQLite rollback, maintenance barrier rejection/release, refund persistence, portable snapshot validation/rollback/idempotency, synthetic isolated-store restoration with a distinct key, corrupt legacy preservation, v1 migration/restart, and authenticated shop-identity mismatch rejection. `test-result-exit-code.txt` is `0`. A targeted instrumentation rerun of the rich financial-history restore test also passed and saved the encrypted synthetic snapshot to emulator Downloads for the UI drill.

## Security review preparation (internal self-review, not independent)

| Threat / failure | Implemented control | Remaining concern |
|---|---|---|
| Lost device exposes DB file | SQLCipher with Keystore-wrapped local key | Rooted/unlocked device, process memory and compromised app remain out of scope |
| Backup stolen | Separate recovery secret, PBKDF2-derived key, authenticated header and encrypted SQLCipher payload | Recovery-secret strength/rotation, user education, and independent crypto review remain |
| Wrong/corrupt backup replaces shop data | Preflight, schema and integrity checks, staged restore, explicit confirmation, previous DB retention | Authenticated generated shop UUID is checked against existing active records; real shop/account identity and operator authorization are not established |
| App/process stops during cutover | Durable pending/committed journal and startup recovery; fault injection at selected cutover points | True process kill/power loss and file-system behavior not exercised |
| Concurrent sale during snapshot/restore | Fair exclusive barrier; commits/reads use shared permits; UI maintenance state | Future write pathways/workers must use this contract; prototype has no sync worker |
| Sensitive data appears in Android backups | `allowBackup=false` and extraction exclusions | Supported OS/OEM transfer behaviors require platform matrix review |
| Temp files leak data | encrypted snapshot staging and cleanup; plaintext migration preservation behavior is documented | forensic erase on flash is not guaranteed; low-storage and interrupted temp cleanup not fully tested |
| Key absent/inaccessible | fail closed; no silent empty-store initialization | invalidation/reinstall/lost-key recovery scenarios need dedicated tests |

Reproduction package: use the source at this workspace, API 37 AVD, the Gradle command above, then inspect the connected-test XML, lint report, APK outputs, and two UI hierarchy XML files. Security review should inspect SQLCipher configuration, Keystore key lifecycle, backup rules, barrier acquisition coverage, journal state transitions, logging, and synthetic fault tests. This report is an internal preparation artifact; no independent security review has occurred.

## Acceptance gaps / not qualified

- SAF export and restore were completed through the actual Android document pickers on the API 37 emulator. The user-visible encrypted file survived app uninstall/reinstall, was selected through the restore picker, authenticated, previewed, explicitly approved, and restored into the new empty app installation. A richer synthetic backup was also restored through the UI; the instrumentation test separately verifies its financial, stock, audit, idempotency, and outbox records. This is same-emulator clean-install evidence, not cross-device qualification.
- Schema v6 stores a generated shop UUID. Backup container v3 authenticates that UUID; import checks it against the encrypted payload and refuses replacement when an active shop with business activity has a different identity. A regression test verifies the active records survive mismatch rejection. This UUID is a technical store identity, not a user-managed business identity or staff authorization.
- A second emulator profile/device and cross-device recovery are not demonstrated. The UI drill used uninstall/reinstall on the same API 37 emulator, then restored from a user-visible Downloads file using newly provisioned local Keystore material.
- A second-person recovery drill is not done.
- Fault-injected output failure is tested, but real storage exhaustion is not. Some interruption points are injected; hard process kill/power loss is not tested.
- Missing Keystore key is simulated; hardware-backed key invalidation and reinstall recovery are not qualified.
- No sync worker currently exists, so future sync mutation pause behavior is not exercised.
- Independent security review, real customer/shop data, production security, and production backup support are out of scope.

Therefore CAT-051B completion gate is **not met**. Continue with synthetic data only. Do not start CAT-052 until actual SAF export/restore, clean-install recovery and remaining functional failure tests have acceptable evidence. Independent security review remains its own gate before real shop data.

## Next executable work

1. Add true low-storage and broader cutover fault tests; verify startup recovery after a real forced process termination (fault injection currently covers selected cutover points).
2. Exercise actual Keystore invalidation/reinstall/key-loss cases beyond the tested clean reinstall and generated local key.
3. Run a second-person recovery drill and commission an independent security review.
4. Continue the Android shopkeeper MVP using synthetic data; keep independent security review and cross-device recovery as release gates.

## Superseding recovery hardening run - 29 October 2026

This addendum supersedes only the older test counts above. A device-local shop-identity binding is now recorded after a readable encrypted backup is exported. If the SQLCipher Keystore key becomes unavailable, restore requires all three identities to agree: the authenticated backup header, the decrypted backup payload, and that local binding. A mismatch is rejected before key rotation or database replacement. On a match, restore stages the snapshot using a fresh Keystore wrapping key, journals old/new key envelopes, verifies the restored store, commits replay protection, and retains the old unreadable encrypted database under a `.key-lost-*` archive name. Interrupted uncommitted recovery restores the previous database and prior key envelope through the journal. The app explicitly loads SQLCipher for isolated recovery stores as well as the default store.

Verification exercised a synthetic shop on the API 37 emulator: exported an authenticated portable snapshot, simulated loss of its original Keystore wrapping key, rejected a deliberately mismatched local identity while asserting the old encrypted bytes remained unchanged, injected a failure after the new key switch and verified byte-for-byte database plus key-envelope rollback, restored after reinstating the correct local identity using a fresh local key, reopened the restored order and cash event, rejected duplicate command replay, and verified the old ciphertext archive remained. This is a simulated missing-key scenario using Android Keystore in an emulator; it is not hardware key invalidation qualification.

An import-staging fault injector now simulates a partial file write followed by an ENOSPC-style exception. The test verifies the current shop remains intact, temporary import/staging files are cleaned and the maintenance barrier is released. This is a deterministic injected short write, not actual device storage exhaustion.

Latest full local verification after the changes:

| Check | Result | Evidence |
|---|---:|---|
| Node | PASS, 48/48 | `node --test prototype/*.test.cjs` |
| Kotlin JVM | PASS, 28/28 | `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml` |
| Android instrumentation | PASS, 23/23, 0 failures | `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` |
| Debug APK and instrumentation APK | PASS | `android/app/build/outputs/apk/debug/app-debug.apk`; `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Android lint | PASS, 0 errors / 77 warnings | `android/app/build/reports/lint-results-debug.txt` |
| Focused key-loss test | PASS, 1/1 | Same connected-test XML; method `authenticatedBackupCanRecoverSameShopAfterDeviceKeyLossWithoutDiscardingCiphertext` |

The complete Gradle command was `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain` from `android/`; Node tests ran separately. The previous full instrumentation run exposed an invalid test fixture that attempted payment before a successful handover. After correcting the fixture and explicitly loading SQLCipher for isolated stores, the focused test and full suite both passed.

**CAT-051B remains incomplete.** Key-loss replacement is now tested at synthetic emulator scope, and clean-install SAF restore evidence remains the earlier same-emulator evidence listed above. Still unqualified: true process kill/power loss at each journal edge, actual storage exhaustion, hardware Keystore invalidation, replacement on a separate emulator/device, second-person recovery, comprehensive screen-level failure flows, all future background write pathways, and independent security review. CAT-052 and production use remain gated on these requirements.


### Verification note — key-envelope rollback assertion

After the 23/23 full instrumentation run, a final assertion was added to the key-loss test to inject a failure after activation of the replacement Keystore envelope. The test confirmed the original encrypted database bytes and the original wrapped-key envelope were restored; the focused API 37 instrumentation rerun passed 1/1. The preceding complete Android suite passed 23/23 before this test-only assertion. That focused result was superseded by a subsequent complete 23/23 instrumentation run after the UI/startup change and final test assertion.

## Recovery-screen launch and non-blocking database open — 29 October 2026

A runtime UI check exposed an Android not-responding dialog when the recovery screen was entered while the activity synchronously opened SQLCipher. `MainActivity` now has a recovery-only entry that displays recovery before opening the shop database, and normal database initialization/reload run on the existing background executor. Emulator screenshots and hierarchy captures are in `test-evidence/2026-10-29/cat051b-recovery-entry.png`, `cat051b-recovery-entry.xml`, `cat051b-main-start.png`, and `cat051b-main-start.xml`. The recovery screen rendered from the direct entry, and the normal counter reached its ready banner without a new ANR after the fix; the earlier ANR log is from the pre-fix runtime check.

After this UI/runtime fix, the full Android instrumentation suite was rerun: 23/23 passed, 0 failed. Kotlin JVM 28/28, APK builds, and lint 0 errors / 77 warnings also passed in the preceding full Gradle run; Node remains 48/48 (Node sources were unchanged by the UI fix). The focused key-loss test also passed 1/1 after its rollback assertion was added. The latest connected test XML contains the complete 23-test run. This validates emulator startup/recovery-entry behavior only; the SAF pickers were not traversed in this turn, so CAT-051B remains open.

## Final CAT-051B continuation verification — 29 October 2026

The restore completion path also reopens SQLCipher off the UI thread and closes any newly opened store if the activity is destroyed. After this final lifecycle change, the complete verification command `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain` completed successfully: Kotlin JVM 28/28, Android API 37 instrumentation 23/23, APK builds PASS, lint 0 errors / 77 warnings. `node --test prototype/*.test.cjs` passed 48/48. The API 37 suite ran after the final Android source changes. Evidence: `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, `android/app/build/reports/lint-results-debug.txt`, and `test-evidence/2026-10-29/cat051b-key-loss-recovery-run.txt`.

CAT-051B remains open; this run did not perform a new SAF export/restore UI drill. Actual storage exhaustion, abrupt process/power loss across journal transitions, hardware Keystore invalidation, second-device and second-person recovery, and independent security review remain pending. Do not treat the prototype as production-ready.

## Continuation: non-blocking shop setup/catalogue and counter UI trial — 29 October 2026

`ShopSetupActivity` now opens/reads and saves the SQLCipher-backed shop profile on the shared recovery executor. `ProductCatalogueActivity` likewise opens the encrypted store and runs catalogue writes/opening-stock confirmation off the UI thread; screen rendering uses a loaded snapshot. `MainActivity` limits displayed backup-validation exceptions to a sanitized, short argument message while keeping unexpected exception details out of the user-facing status. These changes address UI-thread I/O and make a rejected backup's safe failure reason actionable.

On the API 37 emulator, synthetic shop setup and opening-stock confirmation were exercised. A reviewed voice-command flow created order #12, advanced it to READY and GIVEN, then recorded a staff-declared cash collection. A separate order #13 was created and advanced to READY. Evidence: `test-evidence/2026-10-29/cat051b-setup-saved-now.png`, `stock-confirmed-current.xml`, `order-created-current.png`, `order-ready.png`, `order-given-current.png`, `order-cash-current.png`, `order-13-created.png`, and `order13-ready.png`. These screenshots demonstrate selected local counter interactions, not the requested eight-workflow recovery dataset. Android's connected-test task later removed its installed app package as configured for tests, so these synthetic records are not represented as surviving that test run.

An older external `.ctbackup` was offered to the restore UI with the previously documented synthetic secret; it was rejected with `Backup header authentication failed` before replacement. This is a safe rejection and does not establish that the artifact/secret pair is valid. No fresh SAF export or clean-install restore completed in this continuation. Do not use that older file as recovery evidence.

Latest complete verification after the current Android source changes: `node --test prototype/*.test.cjs` PASS 48/48; `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain` PASS; Kotlin JVM 28/28, API 37 instrumentation 23/23, debug and instrumentation APK tasks PASS, lint 0 errors / 83 warnings. Evidence: `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, and `android/app/build/reports/lint-results-debug.txt`.

CAT-051B is still **open**. A new successful SAF export, same-identity clean-install restore with reconciliation, real low-storage/interruption behavior, hardware Keystore invalidation, separate-device/person recovery, and independent security review remain unverified. The suite's synthetic internal-store restore tests do not substitute for the UI recovery drill.

## 30 October 2026 continuation

The CT-29 report addition and asynchronous Shop Setup, Product Catalogue and Daily Closing database workflows were verified in the latest full run: Node 48/48, Kotlin JVM 28/28, Android API 37 instrumentation 24/24, APK tasks PASS, lint 0 errors/84 warnings. The expanded suite includes persisted-value/disclosure coverage for Daily Reports. CT-29 reporting does not qualify or close the recovery milestone. No new UI-based SAF export/clean-install restore was completed; an older supplied artifact failed header authentication safely before replacement. CAT-051B remains open for that UI drill, failure matrix, hardware/replacement-device tests, and independent review.

## 30 October 2026 — integrated verification after CT-29/30

The current complete run passed: Node 48/48, Kotlin JVM 28/28, Android API 37 instrumentation 25/25, debug/instrumentation APK tasks PASS, lint 0 errors/84 warnings. CT-29/30 tests verify persisted report values/disclosures and grounded alert empty-state/disclosures. The full run also passed after moving CT-28 database loads and review writes off the UI thread. CAT-051B remains open: no fresh UI SAF export and clean-install restore has been demonstrated, and low-storage/interruption, hardware key invalidation, separate-device/person recovery and independent review remain unverified.

## Superseding integrated continuation — CT-29/30 — 30 October 2026

CT-30 Business Alerts now presents locally supported exceptions from the encrypted database with explicit disclosures for unavailable low-stock thresholds, dated credit aging and sync status. CT-29 Daily Reports and CT-30 Alerts are registered Android screens with instrumentation coverage. Shop setup, catalogue and daily-close database operations are dispatched through the shared serial executor. Latest complete run: Node 48/48, Kotlin JVM 28/28, API 37 instrumentation 25/25, APK tasks PASS, lint 0 errors/84 warnings. Evidence: `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, `android/app/build/reports/lint-results-debug.txt`.

An earlier full run timed out launching CT-28 after the long recovery tests; focused CT-28 succeeded, DB operations were moved off the UI thread, and the subsequent complete suite passed. The timeout remains part of the test history.

CAT-051B is not complete: there was no fresh SAF UI export plus clean-install restore in this run. Actual storage exhaustion/hard interruption, hardware key invalidation, second-device/person recovery, and independent security review remain open. These prototype flows are not production-qualified.

## SAF stream ownership correction and manual UI recovery attempt — 30 October 2026

The SAF backup verification and restore callers now close `ContentResolver` input streams with `use`, including failure paths. The complete verification rerun after this source change passed: Node 48/48, Kotlin JVM 28/28, API 37 emulator instrumentation 25/25, APK build tasks PASS, lint 0 errors/84 warnings. Evidence: `test-evidence/2026-10-30/verification.txt` and reports under `android/app/build/`.

A synthetic rich encrypted fixture was generated into the emulator Downloads directory by the passing `verifiedBackupRestoresSyntheticFinancialHistoryIntoSeparateStoreWithNewLocalKey` instrumentation test using `cat051b.saveRichBackup=true`. The actual Android UI was then used to open the SAF Downloads picker, select that file, and enter the fixture recovery secret. The UI remained with disabled business controls and the “Recovery operation starting” banner; it did not reach authenticated metadata review or replacement confirmation. No successful UI restore is claimed. The local database listing showed no staging/pre-restore database family at that point; the selected empty-shop dataset had not been replaced. Screenshots/hierarchies: `recovery-fresh-ui.png`, `restore-picker-live.png`, `afterselect.png`, `backup-verify-wait.png`, `recovery-progress.png`, and `restore-stuck.xml` under `test-evidence/2026-10-30/`. The emulator also produced a cold-start focus-timeout ANR during this manual session and multiple HWUI frame delays; emulator graphics/runtime instability is part of the qualification limitation, not evidence of a successful recovery.

CAT-051B remains open. Next work: reproduce the SAF verification callback with targeted lifecycle/instrumentation diagnostics, ensure busy-state release on Activity recreation/cancellation/failure, then complete the user-facing export and clean-install restore with a reconciled synthetic dataset. Hard interruption, true storage exhaustion, hardware Keystore invalidation, separate device/person recovery and independent security review remain unqualified.

## 30 October 2026 — recovery secret-dialog cancellation diagnosis and fix

A repeat emulator walkthrough captured the document-picker and secret-entry control bounds. The dialog's Continue button moves from `[737,1251][978,1393]` before the keyboard appears to `[737,796][978,938]` after the password editor takes focus. The prior manual coordinate landed outside the moved dialog. Outside/back dismissal closed the prompt while leaving `recoveryBusy=true`, which disabled the shop controls although no verification task had started. This root cause is now addressed: the prompt explicitly permits outside cancellation, clears the secret inputs on negative/cancel/dismiss, and releases the busy state on every unaccepted dismissal. The emulator regression `dismissingRecoverySecretDialogReleasesBusyStateAndClearsFields` passed.

A separate instrumentation regression exercises `verifyBackup` with a real MediaStore `content://` URI and synthetic authenticated backup. It passed, observes the success callback and confirms the order ledger is untouched by verification. The manual document-picker attempt with the old Downloads fixture reached the app's verification result but showed `Backup header authentication failed`; that attempt is not a successful restore and did not replace data. The recovery dialog now recovers from dismissal, but an end-to-end valid-secret SAF restore and record reconciliation remain open.

Latest full verification after the cancellation fix: Node 48/48, Kotlin JVM 28/28, API 37 instrumentation 28/28 (104 total), APK tasks PASS, lint 0 errors / 94 warnings. The focused SAF-URI verification regression also passed after adding the busy-release assertion. The complete build artifacts and reports are under `android/app/build/`; walkthrough images in this folder include `recovery-ui-menu.png`, `menu-current.png`, `picker-now.png`, `secret-dialog-clean.png`, and `restore-after-verify.png`. Screenshots containing password entry were overwritten with non-sensitive screens. A programmatic attempt to drive the dialog positive button inside instrumentation was unreliable and was removed; the test continues to verify the actual asynchronous verification callback through its SAF URI, while manual input remains a separate qualification requirement.

## Final saved verification — 30 October 2026

Re-ran the complete current tree after the CAT-051B cancellation fix and final test source. `node --test prototype/*.test.cjs` passed 48/48. From `android/`, `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain` completed `BUILD SUCCESSFUL` in 7m20s: Kotlin JVM 28/28, API 37 instrumentation 28/28, both APK tasks pass, lint 0 errors / 94 warnings. Total: 104 passing automated tests across three suites. Fresh command logs: `test-evidence/2026-10-30/node-full-final.log` and `gradle-full-final.log`. Fresh reports: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, and `android/app/build/reports/lint-results-debug.txt`. The instrumentation XML includes both `dismissingRecoverySecretDialogReleasesBusyStateAndClearsFields` and `recoveryActivityCompletesBackupVerificationCallbackForSafContentUri`.

CAT-051B remains open: the full Activity callback test uses an authenticated synthetic backup through a real MediaStore `content://` URI, but the manual SAF attempt with the pre-existing Downloads fixture did not authenticate its header and no restore occurred. No clean-install financial reconciliation is claimed.

## Superseding recovery UI round-trip qualification — 30 October 2026

The earlier note that CAT-051B had no successful UI restore is superseded for the synthetic API 37 emulator path below. A fresh rich synthetic snapshot (schema v14, format v3, 200,704 encrypted payload bytes) was selected through Android OpenDocument, authenticated with its recovery secret, previewed, and restored after explicit replacement confirmation. The app reported that it reopened the encrypted database. Sales History then showed three restored orders with the expected staff-declared cash, UPI-pending, partial refund, credit and due values; Customers & Udhar showed the expected repayment and credit-reversal events with ₹0 outstanding; Inventory Overview showed the expected 98 Regular Chai / 99 Samosa balances and sale/refund movement history.

A separate SAF CreateDocument flow exported that restored synthetic dataset to Downloads (200,828-byte `.ctbackup`). The app then selected and cryptographically verified that UI-exported file in the restore flow; preview showed matching authenticated shop-ID suffix and payload size. After preserving the exported encrypted artifact outside app-private storage, the test app was uninstalled and freshly installed on the same API 37 emulator. The UI-exported archive was selected again, verified, explicitly approved for replacement, and restored successfully. Post-restore Sales History displayed all three expected orders. This is a same-emulator uninstall/reinstall clean-install round trip, not a second physical device or cross-device qualification.

Artifacts: `test-evidence/2026-10-30/cat051b-ui-exported-synthetic.ctbackup` (SHA-256 `E6C04A130FBD007B6BC63672E5A08563FEC5575CEDC84F81AD755555FE924A3A`); `export-completed.png`, `exported-backup-verified.png`, `roundtrip-restored-home.png`, `roundtrip-restored-sales.png`, `restored-credit.png`, `restored-inventory.png`; corresponding `*-xml` UI hierarchies. The earlier encrypted fixture copied from emulator Downloads is also retained as `cat051b-ui-restored-synthetic.ctbackup`. The recovery secrets are deliberately not recorded in this report or artifacts.

Fresh verification after the recovery-secret dialog fix and UI drill: Node 48/48; Kotlin JVM 28/28; Android API 37 instrumentation 28/28; debug APK and Android test APK builds PASS; lint 0 errors / 94 warnings. Total 104 automated checks across separate suites. The connected instrumentation suite includes `dismissingRecoverySecretDialogReleasesBusyStateAndClearsFields` and `recoveryActivityCompletesBackupVerificationCallbackForSafContentUri`; rich snapshot integrity is also asserted by `verifiedBackupRestoresSyntheticFinancialHistoryIntoSeparateStoreWithNewLocalKey`. Current results are in the JVM and connected-test XML reports and lint report under `android/app/build/`.

## Additional restart-recovery fault-state test — 30 October 2026

Added `restoreJournalRecoversOriginalDatabaseAfterProcessStopsBeforeReplacement` to the API 37 instrumentation suite. With a synthetic order persisted in SQLCipher, the test closes the store, moves the encrypted active database family to the journal's `.pre-restore` location, writes a pending restore marker, and reopens normal persistence. Startup recovery restores the original order, amount and audit event, clears the journal, and removes the prior-file marker. This validates deterministic startup rollback from the pre-activation interruption state; it is not a hard process kill, power loss, filesystem exhaustion or hardware Keystore test.

Latest full verification after the regression: Node 48/48; Kotlin JVM 28/28; Android API 37 instrumentation 32/32; debug and instrumentation APK build tasks PASS; lint 0 errors / 104 warnings (108 tests total). The new case is in `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`. CAT-051B still has the physical interruption/storage/key-loss and independent-review gates described above.

### Superseding verification — committed restore recovery — 30 October 2026

Added `restoreJournalFinalizesVerifiedReplacementAfterCommitMarker` to cover startup finalization when the restore commit marker is already durable. The active synthetic encrypted database remains readable; startup removes the redundant prior database and clears the pending journal. The rollback-before-activation and finalize-after-commit paths now both pass on API 37. Final current run: Node 48/48, Kotlin JVM 28/28, Android instrumentation 33/33, APK build tasks PASS, lint 0 errors / 104 warnings (109 total tests). This remains deterministic fault-state simulation, not a process kill, power-loss, actual low-storage or hardware key-invalidation test.

**Status:** The CAT-051B user-facing SAF export, authenticated verification, explicit restore and same-emulator clean-install recovery criteria now pass for synthetic data. Continue to describe the larger CAT-051 security/recovery scope as prototype-qualified, not production-qualified. Still open: actual device storage exhaustion; hard process-kill/power-loss matrix; hardware Keystore invalidation; second-device and second-person recovery; comprehensive Android-version/OEM backup behavior; future background-writer coverage; independent security review. No production customer data was used.

## Superseding recovery failure test — partial export destination write — 1 November 2026

Added `partialBackupExportFailurePreservesShopDataCleansSnapshotAndReleasesWrites` to the API 37 instrumentation suite. It uses a synthetic `OutputStream` that accepts a 96-byte prefix and then throws, exercising a destination failure after an encrypted archive has been partially emitted. The test verifies that the existing order remains readable with its original total, the private SQLCipher export snapshot and WAL are removed, the maintenance barrier is released, and a subsequent sale can commit. Focused run: 1/1 passed (`test-evidence/2026-10-31/cat051b-partial-export-instrumentation-rerun.log`). This is deterministic destination-failure injection; it does not claim real low-storage or process/power-loss qualification.

The first full regression attempt after adding the case executed 75 Android tests and had two failures: an immediate profile-revocation read raced the accessibility click, and the customer-credit activity launch timed out while emulator focus processing was delayed. Both UI cases passed individually after a cold emulator restart. The profile test now waits for UI idle before reading the revoked contact state. The clean-boot complete rerun passed: Node 52/52, Kotlin JVM 45/45 (42 CounterModel + 3 account-picker tests), API 37 instrumentation 75/75, debug and instrumentation APK tasks PASS, and lint 0 errors / 141 warnings. Exact current-run evidence: `test-evidence/2026-11-01/cat051b-final-gradle-rerun.log`, `test-evidence/2026-10-31/cat051b-final-node.log`, Android XML `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, JVM XML files under `android/app/build/test-results/testDebugUnitTest/`, and `android/app/build/reports/lint-results-debug.txt`.

CAT-051B remains synthetic-emulator qualified only. Actual storage exhaustion, hard process termination/power loss, physical Keystore invalidation, second-device/person recovery and independent security review remain open. The complete product and the six delivery waves remain in progress.

## Synthetic financial backup restore regression — 1 November 2026

Re-ran `verifiedBackupRestoresSyntheticFinancialHistoryIntoSeparateStoreWithNewLocalKey` on the API 37 emulator after the current Wave 2 source changes. It passed 1/1. The test constructs a synthetic store with multiple orders, declared cash, UPI pending, partial item refund/restock, customer udhar and repayment, cash drawer/closing records, audit and outbox events; it exports, verifies and restores to an isolated database/key namespace. It checks order/payment/credit/refund/stock/audit/outbox counts, expected cash, duplicate-command rejection and that restored local key material differs from the source key. Evidence: `../test-evidence/2026-11-01/cat051b-rich-backup-restore-focused.xml`.

This is not the separate UI-driven SAF export/import/uninstall-reinstall scenario, nor a real process-kill/power-loss, low-storage, hardware key invalidation, second-device/person or independent review result. CAT-051B's documented synthetic-emulator acceptance remains distinct from those open gates. The current-source full Android suite also remains unresolved; this focused case passed individually.

## Current-source full Android regression — 1 November 2026

The current API 37 emulator suite passed 80/80, 0 failures/errors/skips, including encrypted persistence, backup/restore, recovery journal, customer credit/refunds, stock, procurement and the new supplier UI tests. Evidence: `../test-evidence/2026-11-01/ct19-20-current-full-instrumentation.xml`. This supersedes earlier current-source regression status; it does not qualify hard power loss, real low storage exhaustion, physical Keystore invalidation, second-device/person recovery, independent review, or production use.

## SAF partial-export cleanup follow-up — 1 November 2026

The owner-facing export path now runs through `BackupExportPolicy`: if SAF output creation or writing fails, it attempts `DocumentsContract.deleteDocument` after the stream closes. It preserves the write exception, records whether the provider deleted the partial document, never deletes after success, and displays distinct success/failure guidance. If deletion is refused, the UI warns that an incomplete file may remain and must be removed before retrying. JVM tests cover cleanup success, cleanup refusal/throw without masking the original write failure, and no deletion after successful export.

After this final source change, `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` passed; Kotlin JVM is 46/46, Node remains 52/52 (no JS source changed), APK build tasks pass, lint 0 errors / 141 warnings. The last complete API 37 instrumentation run was 75/75 before this SAF cleanup UI change. A focused recovery-screen instrumentation rerun was attempted but Gradle reported `No connected devices`; the emulator process did not register with ADB after relaunch. Evidence: `test-evidence/2026-11-01/cat051b-export-cleanup-gradle.log` and `cat051b-export-cleanup-focused-instrumentation.log`. **The new document-deletion UI branch is build- and JVM-tested, not emulator-qualified yet.** Retry its runtime qualification when the API 37 AVD is available. This remains synthetic recovery work; no real storage exhaustion or external-provider deletion result is claimed.

## Superseding final verification — 30 October 2026

After adding the partial CT-12 Refund & Correction screen and restricting returned-stock UI to the supported not-restocked policy, the complete verification passed:

- Node: 48/48 (`node --test prototype/*.test.cjs`).
- Kotlin JVM: 28/28.
- API 37 emulator instrumentation: 29/29, including `refundCorrectionScreenShowsAuditedItemAndAmountPathsForEligibleOrder`.
- Debug APK and Android instrumentation APK: PASS.
- Android lint: 0 errors / 96 warnings.
- Total: 105 tests across the three suites.

The earlier full run with the initial CT-12 fixture failed because that test did not configure a persisted product and opening stock. The fixture was corrected to use the normal catalogue and count commands; the targeted test and final complete suite passed. The final suite covers rendering, not manual dialog submission; see `specs/CT-12-REFUND-CORRECTION-REPORT.md`.

CAT-051B user-facing recovery functional gate: PASS at synthetic API 37 emulator scope. The app-created encrypted backup survived outside app storage, verified successfully, and restored after uninstall/reinstall via the real SAF picker; restored orders are visible in Sales History. See the preceding recovery round-trip evidence and report. Device/cross-device, hardware key invalidation, real power-loss/storage-exhaustion, independent review, physical Bluetooth, and field gates remain pending.

## Superseding full verification — CT-10 payment UI confirmation — 30 October 2026

Added an API 37 instrumentation workflow that drives the real native Payment screen through accessibility actions: open the pending UPI follow-up, enter ₹7.50, accept the staff-declaration confirmation, and verify that the order records a partial collection, the pending attempt is cancelled in the append-only ledger, the receipt status remains `staff_declared`, and no event is provider-verified. Cleanup hides the emulator keyboard and finishes the activity before removing its synthetic database fixture. The test passes independently and in the complete suite.

Complete current-tree verification: Node 49/49, Kotlin JVM 31/31, Android API 37 instrumentation 37/37 (117 total); debug and instrumentation APK build tasks PASS; lint 0 errors / 107 warnings. The current Android XML result contains 37 tests and zero failures. The full command was `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain` from `android/`, plus `node --test prototype/*.test.cjs` from the project root. The encrypted synthetic SAF backup artifact and same-emulator uninstall/reinstall recovery evidence remain verified at the earlier documented scope; its SHA-256 was rechecked against the report. Neither this test run nor that recovery drill qualifies another device, physical Bluetooth, field operation or production security.

## Cross-project superseding verification — 30 October 2026

After CT-04 Device Permissions was added, the complete project run passed: Node 48/48, Kotlin JVM 28/28, Android API 37 instrumentation 30/30, debug and instrumentation APK builds PASS, lint 0 errors/99 warnings (106 tests across three suites). This supersedes earlier test totals in the append-only history above.

Since the suite run, a manual CT-12 synthetic item-refund drill passed: order #14 received a reasoned and explicitly confirmed staff-declared ₹15 cash refund through the live UI; Sales History showed the negative refund event, and Inventory Overview confirmed no restock. Evidence: `test-evidence/2026-10-30/ct12-refund-committed.png`, `ct12-postrefund-history.png`, and `ct12-postrefund-inventory.png` with hierarchy files. No application source changed after the passing full suite; the manual check exercised the built APK.

## Superseding result — order-date range and schema v16

The API 37 full instrumentation suite passed 34/34, Kotlin JVM passed 30/30, Node passed 48/48, APK builds passed, and lint reported 0 errors / 104 warnings (112 automated tests). SQLite v15→v16 migration and CT-11 local order-date range are covered. Legacy dates remain unknown. CT-11 manual usability, payment-event dates, hard process interruption, low storage, physical key invalidation and independent security review remain open.

## Superseding CT-22 test run — 30 October 2026

After connecting Customer Directory to the new Customer Details activity, the complete current tree passed: Node 49/49, Kotlin JVM 31/31, Android API 37 instrumentation 38/38 (118 total), APK tasks PASS, lint 0 errors / 107 warnings. The new instrumentation test verifies the directory navigation and persisted synthetic credit/repayment details. `CT-051B` same-emulator SAF clean-install recovery remains functionally passed with hardware/cross-device/security gates still open.

## Superseding current verification — CT-21/22 — 30 October 2026

The current complete test result is Node 49/49, Kotlin JVM 31/31, API 37 Android instrumentation 39/39; APK tasks pass; lint reports 0 errors / 107 warnings. CT-21 ID search and CT-22 customer detail navigation are tested using synthetic records. Customer profile/contact management and broader CRM are incomplete.

## Superseding CT-21 customer profile verification — 30 October 2026

Latest complete run: Node 49/49, Kotlin JVM 31/31, API 37 instrumentation 42/42 (122 total), APK tasks PASS, lint 0 errors / 108 warnings. Encrypted schema v17 stores customer names and optional phone only with recorded contact consent; contact data is masked in customer UI and omitted from audit descriptions. The v16→v17 migration and encrypted backup restore preserve financial and profile data. This is a partial local directory, not a completed CRM.

## Superseding verification — emulator launcher recovery and CAT-052 journal — 30 October 2026

The earlier full-suite result with five UI failures was caused by the emulator's Pixel Launcher entering an ANR dialog, which obscured the app from UI automation. Captured evidence at `test-evidence/emulator-launcher-anr.png`; restarting only the emulator launcher cleared the dialog (`test-evidence/emulator-launcher-restarted.png`). The five previously failing UI tests then passed individually. The complete current-tree run subsequently passed: Node 49/49, Kotlin JVM 35/35, API 37 Android instrumentation 49/49 (133 passed across these three app suites), backend projection contract 5/5 (138 method executions across four suites), debug APK and instrumentation APK tasks PASS, lint 0 errors / 114 warnings. Full command: `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug connectedDebugAndroidTest --console=plain` from `android/`; Node and backend contract commands were run separately. The API 37 XML at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` now reports 49 tests, 0 failures, 0 errors, 0 skipped. This verifies the emulator's current test run, not physical-device or field behavior.

CAT-052 update: schema v20 durably records per-event accepted/duplicate/conflict outcome and server revision; the stored server-revision cursor advances monotonically and an out-of-order response cannot acknowledge past the earliest eligible outbox event. The v19→v20 migration, retries, conflict blocking, cursor persistence, and this queue-head guard pass an API 37 test. Android network delivery, credential/device enrollment, background retries, conflict-resolution UI, and authoritative server financial projection are still outstanding. CAT-051B functional same-emulator recovery is documented as passed in its implementation report; independent security review, hard power-loss/low-storage qualification and second-device recovery remain open. The six-wave project remains in progress.

## Superseding current full regression — 31 October 2026

The current Android source passed a full API 37 instrumentation run of 70/70 tests, including recovery, encryption, migration, restore-journal, key-loss simulation and the new CT-11→CT-12 refund workflow. Kotlin JVM passed 40/40; prototype Node passed 49/49; Android debug and instrumentation APK tasks passed; lint reported 0 errors / 139 warnings. Backend contract tests passed 5/5 and 4 PostgreSQL-dependent integration tests were skipped because no test database was configured. Preserved evidence: `test-evidence/2026-10-31/ct11-ct12-full-instrumentation.xml`, with command/results in `ct11-ct12-regression.md`. These results do not close CAT-051B: actual low-storage/power-loss behavior, physical Keystore invalidation, second-device/person recovery and independent security review remain unqualified. The full project remains in progress.

## Maintenance barrier reentrancy and listener-failure continuation — 1 November 2026

The global maintenance barrier now preserves its outer label across nested maintenance helpers. This prevents the recovery UI from appearing ready while an enclosing snapshot or restore still owns the exclusive lock. Initial observer callbacks are exception-isolated, consistent with later notifications. JVM tests cover nested pauses, injected action failure, listener failure, and successful subsequent write-permit acquisition.

Current-source verification after this change: Node 52/52; Kotlin JVM 48/48; debug and instrumentation APK build tasks PASS; lint 0 errors / 141 warnings. `connectedDebugAndroidTest` was attempted and stopped before test execution with `No connected devices`; the preceding 75/75 emulator suite predates this change and the recent CT-09/SAF UI updates. This does not close CAT-051B. Clean-install same-emulator evidence remains documented above; independent review, second-device/person recovery, actual low-storage exhaustion, hard process interruption, physical Keystore invalidation and current-source emulator regression remain open.

Evidence: `../test-evidence/2026-11-01/cat051b-barrier-gradle.log`, `cat051b-barrier-node.log`, `ct09-audit-timeline-focused-instrumentation.log`.

## Restore cutover crash-window coverage — 1 November 2026

Added API 37 instrumentation for the filesystem/journal state where a replacement encrypted database has been activated, the previous database still exists as `.pre-restore`, and the durable imported/committed marker has not yet been written. On startup, `RestoreRecoveryJournal` moves the uncommitted replacement to `.restore-interrupted`, restores the original encrypted store, clears the pending journal, and leaves the original order and audit evidence readable. The focused test passed 1/1. Evidence: `../test-evidence/2026-11-01/cat051b-precommit-recovery-focused.xml` and `cat051b-precommit-recovery-focused-rerun.log`.

This is deterministic state construction and startup-recovery testing; it does not kill the Android process during a live restore. Actual hard process termination, power loss, real low-storage behavior, physical Keystore invalidation, cross-device/person recovery, and independent security review remain open. The complete API 37 run remains not green (63/77; UI failures followed Pixel Launcher/System UI ANRs). The new test does not change that full-suite status.

## Additional UI export attempt - 19 November 2026

A separate manual run on the API 37 emulator opened Backup & Recovery, entered a synthetic recovery secret in the masked fields, chose Downloads through Android's SAF Create Document picker, and returned to the app's encrypted-backup success message. The resulting `chai-and-tapri-backup (2).ctbackup` was observed in emulator Downloads and copied to ignored local test evidence at `test-evidence/2026-11-19/cat051b-ui-export-synthetic-encrypted.ctbackup`. The file is a synthetic backup and must remain uncommitted; no recovery secret was recorded in evidence.

The emulator's ADB transport then remained `offline`, and its window was minimized and unavailable to the UI automation surface. I did not clear app data or uninstall the app. This particular newly exported archive was not restored, and this attempt does not qualify export-plus-restore as one end-to-end drill. The earlier same-emulator SAF restore documented above remains separate evidence. Restore of this archive, export/restore interruption recovery, and clean-install recovery for this exact export remain unverified.

## Superseding SAF export instrumentation - 19 November 2026

Added `ownerBackupMenuCreatesEncryptedSyntheticBackupThroughSafUi` to the API 37 instrumentation suite. The test seeds synthetic business data, opens the real Backup & Recovery UI, enters matching recovery secrets in the masked dialogs, uses Android's actual CreateDocument picker with Downloads selected, saves the backup, and asserts the app's success state. Focused current-source run: 1/1 passed on `emulator-5554`; evidence: `test-evidence/2026-11-19/cat051b-current-saf-ui-export-ui-only.log` and Android test XML under `android/app/build/outputs/androidTest-results/connected/debug/`.

Host ADB independently observed and pulled the generated Downloads archive `chai-and-tapri-backup (5).ctbackup` (245,884 bytes) to ignored evidence at `test-evidence/2026-11-19/cat051b-current-saf-ui-export-synthetic.ctbackup`. No recovery secret is recorded. This is an actual UI export result. The focused test does not itself reopen or cryptographically verify the external artifact, and this exact archive has not been restored. Thus it does not close clean-install restore, shop-data reconciliation, or CAT-051B. Later host-side MediaStore and shell-command checks were unreliable on this emulator; the successful assertion is the actual app export completion plus independent host ADB file observation. Physical cross-device recovery, second-person testing, actual storage exhaustion/power interruption, physical Keystore invalidation, and independent security review remain open.
