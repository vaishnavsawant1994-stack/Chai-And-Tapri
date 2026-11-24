# CAT-051B SAF Backup/Restore UI Regression — 24 November 2026

## Result

The current-source isolated API 37 default instrumentation suite passed **121/121 tests, 0 failures, 0 errors, 0 skipped**. This includes a real Android Storage Access Framework export and restore flow with synthetic records. The flow created an encrypted backup in Downloads, selected the file through the OpenDocument picker, verified the recovery secret, confirmed replacement, reopened the encrypted database, and checked restored sales, split cash/Udhar payments, repayments, refunds, inventory, audit records, and duplicate-command behavior.

A test-harness defect caused the prior 121-test run to fail at `ownerBackupMenuCreatesEncryptedSyntheticBackupThroughSafUi`: the test did not locate the picker filename editor and reused the default backup filename. Its broad `Save` text lookup and accessibility action did not reliably activate DocumentsUI's final Save control. The test now locates the picker `EditText`, chooses a unique `.ctbackup` filename, prefers an exact Save label, injects a correctly timed tap at the actual Save button bounds, and asserts that the picker returns to the app before polling export status. This changes instrumentation behavior only; no backup cryptography or financial business logic was changed.

The separate SAF UI test passed **1/1** before the full run, and the final full run passed **121/121**. The synthetic SAF export/restore output and report are included in the suite evidence; no real business data was used.

## Additional verification

- Kotlin JVM: **59/59 passed**.
- Prototype JavaScript: **52/52 passed**.
- Backend Node: **26 passed, 4 PostgreSQL-dependent skipped, 0 failed**.
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`: **PASS**.
- Android lint: **0 errors, 176 warnings**.
- Full connected Android instrumentation: **121/121 passed**.

Evidence is under `test-evidence/2026-11-23/android-full-regression-follow-up/`, including `full-after-saf-fix-121.xml`, `full-after-saf-fix-TestRunner.log`, `saf-roundtrip-isolated-pass.xml`, and screenshots from the synthetic UI drill. The JUnit XML and emulator's log timestamps use the emulator clock; host verification ran on 24 November 2026.

## Scope limits

This qualifies the current synthetic Android emulator scenarios only. It does not complete CAT-051B's hard power-loss/low-storage/physical-key-loss matrix or independent security review. It does not qualify physical Bluetooth microphones, noisy-shop use, second physical devices, production synchronization, deployment, or the complete six-wave product. These gates remain open.

## Fresh API 37 emulator installation recovery drill - 24 November 2026

Follow-up on a newly created disposable AVD, `Tapri_Recovery_Clean_2026` (API 37, Google Play x86_64, 2 GB RAM), leaving the previously active AVD and its app data untouched:

1. The Android UI export test seeded only synthetic orders and records, then created `cat051b-saf-roundtrip-1795485549510.ctbackup` through Android's CreateDocument picker in Downloads. Result: **1/1 passed**.
2. A separate Gradle instrumentation invocation installed the app again on the disposable AVD. PackageManager logged a new app code path; the target package was absent after the instrumentation invocation, while the Downloads backup remained present outside private app storage.
3. The explicit clean-restore test then opened that Downloads file through Android's OpenDocument picker, authenticated it with the fixture recovery secret, confirmed replacement, and verified restored orders, partial refund, Udhar balance, repayment, cash, UPI-pending state, stock, audit/outbox counts, duplicate-command protection, and usable local Keystore key material. Result: **1/1 passed**.

The backup crossed the app uninstall/reinstall boundary on the same isolated emulator profile. This supports clean-app-install recovery on that emulator, not cross-device recovery. Synthetic JUnit evidence is `../test-evidence/2026-11-24/cat051b-clean-install/clean-install-restore.xml`; the encrypted synthetic artifact is `../test-evidence/2026-11-24/cat051b-clean-install/synthetic-saf-backup.ctbackup`. These files are local and git-ignored. No customer/shop data was used.

CAT-051B remains partially qualified: no actual low-storage exhaustion, physical Keystore invalidation, abrupt power-loss matrix, second physical device, second-person recovery, or independent security review was performed. The original active AVD's app database was not run through this destructive drill.
