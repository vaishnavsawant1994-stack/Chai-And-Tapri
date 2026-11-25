# CT-25 expense operator attribution — 25 November 2026

## Delivered

Expenses history now looks up the expense command's encrypted local audit record and shows its recorded operator ID. Legacy or unattributed records show `not recorded`. The screen explains that this device-local session ID is not independent proof of a person's identity.

No schema migration was needed: expense records already use the command ID, and the append-only audit record already stores the actor ID. This change does not add employee names, attendance claims or new authorization behavior.

## Verification

- API 37 emulator instrumentation: **1/1 passed**, `SqliteCounterPersistenceTest.expenseHistoryShowsDeviceLocalAuditActorWithIdentityLimit`. The synthetic owner records a wages expense; the encrypted record is reopened, the activity displays `operator ID: local-owner`, and the identity limitation is visible. JUnit: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.
- Kotlin JVM: **94/94 passed**.
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`: passed; lint **0 errors / 189 warnings**.
- `git diff --check`: passed.
- Node and backend suites were not rerun because this Android-only UI slice changes neither JavaScript nor backend code.

## Limits

This displays the local actor ID already attached to the audit event. It does not establish a verified human identity, add per-expense approval, or qualify cloud synchronization. CT-25 remains partial, and product, security, field, hardware and release gates remain open.
