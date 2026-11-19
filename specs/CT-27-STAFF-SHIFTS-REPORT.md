# CT-27 — Staff Shifts (local prototype slice)

**Status:** Implemented and emulator-tested for operator-entered shift labels, cash handover, and restart persistence. This is not authenticated staff access or full employee accountability.

## Delivered

- Added a native Staff Shifts screen linked from the counter and Shop Home.
- Shift start opens a counted cash-drawer session and records an operator-entered staff label in the same audited, idempotent persistence command.
- Shift end counts the cash, records a receiving-operator label and optional handover note, and closes the linked drawer session in one transaction.
- The screen displays the active shift's opening/expected cash and session-linked cash collections, cash expenses, and supplier cash settlements. History shows counted handover and unresolved variance.
- Shop records explicitly state that labels are not authenticated identities and that this view does not attribute all sales or actions to a person.
- SQLCipher schema v14 adds staff label, receiving label and note fields to drawer sessions. The v13-to-v14 migration is additive; older v8 migration coverage now upgrades to v14.
- Portable snapshot restoration includes the added session fields and the rich synthetic backup test checks their values after restore.

## Verification

- `node --test prototype/*.test.cjs`: 48 passed, 0 failed.
- `android/gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain`: BUILD SUCCESSFUL.
- Kotlin JVM: 28 passed, 0 failed.
- Android API 37 instrumentation: 22 passed, 0 failed, 0 skipped.
- Debug and instrumentation APK builds: passed.
- Android lint: 0 errors, 75 warnings.
- Emulator UI flow: started synthetic Morning operator shift with INR 50.00 opening cash; ended it with INR 50.00 physical count and Evening staff handover. The zero variance, labels and note remained visible after force-stop/relaunch.
- Evidence: [active shift](../test-evidence/2026-10-29/ct27-active.png), [ended shift](../test-evidence/2026-10-29/ct27-ended.png), [post-restart shift history](../test-evidence/2026-10-29/ct27-restart.png), [post-restart hierarchy](../test-evidence/2026-10-29/ct27-restart.xml), [instrumentation report](../android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0%20%28AVD%29%20-%2017.xml), [Kotlin test report](../android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml), and [lint report](../android/app/build/reports/lint-results-debug.txt).

## Limits

- Staff labels are self-entered and can be inaccurate; there is no login, PIN, identity validation, role or permission enforcement.
- Only cash events explicitly linked to the shift's drawer are summarized. Existing orders and non-cash actions are not comprehensively attributed to staff.
- A shift is coupled to one drawer session; multiple counters/registers and controlled transfer between operators are unsupported.
- Variances are surfaced, not resolved or attributed. No approvals, salary, attendance policy, daily calendar report, or production audit process is provided.
- This is an emulator-qualified prototype workflow, not field or production qualification.
