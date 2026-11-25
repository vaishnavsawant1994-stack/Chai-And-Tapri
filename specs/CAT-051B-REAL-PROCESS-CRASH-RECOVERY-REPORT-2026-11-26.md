# CAT-051B Real Process-Crash Recovery Checks — 26 November 2026

## Result

Two synthetic API 37 emulator tests verify startup recovery after an actual Android app process is killed at separate restore cutover checkpoints:

1. **Before replacement activation:** the debug-only harness preserves the original encrypted database, durably writes the pending journal, and calls `Process.killProcess`. A fresh harness process runs the normal startup recovery path. The original order and audit event remain, the pending journal is cleared, and the preserved database is consumed.
2. **After replacement activation, before the commit marker:** the harness activates a staged replacement, durably records the checkpoint, and is killed. A fresh process rolls back to the original order, retains the interrupted replacement for diagnosis, clears the pending journal, and leaves no active recovery staging file.

Both tests assert that the crashed process has exited before starting recovery. The fixtures use synthetic records and a debug-only service restricted to the default test database.

## Harness correction

The first current-source attempt reached fresh-process recovery but timed out waiting for its report. The test had unbound its service immediately after sending the recovery message, which could stop the service before its main-thread handler completed. The harness now waits for an explicit Messenger completion reply before unbinding. This was a test-harness race; no business or recovery data was changed to make the assertion pass.

## Verification

- `restoreStartupRecoversAfterCrashHarnessProcessDiesAtDurableCutoverCheckpoint`: **1/1 passed**, API 37; repeated after the harness fix.
- `restoreStartupRollsBackReplacementAfterCrashBeforeCommitMarker`: **1/1 passed**, API 37.
- Kotlin JVM tests: **98/98 passed**.
- Debug APK and Android instrumentation APK builds: **passed**.
- Android lint: **passed**, 0 errors / 191 warnings.
- Focused JUnit evidence: `test-evidence/2026-11-26/cat051b-process-crash/before-replacement-process-death.xml` and `test-evidence/2026-11-26/cat051b-process-crash/replacement-activated-before-commit-crash.xml` (local, ignored evidence files).
- The latest broad connected Android run was interrupted before its final result while a long customer-credit UI scenario was active and the emulator lost its Package Manager service. The previously documented 151/151 full suite is historical and predates this slice; no full-suite pass is claimed for this exact source.

## Scope and remaining gates

This is real app-process termination and restart recovery on one API 37 emulator. It is not a device reboot or power-loss test. CAT-051B remains partially qualified pending physical storage exhaustion, physical Keystore invalidation, second-device/person recovery, OEM transfer behavior, and independent security review. Production recovery and the six-wave product remain incomplete. Physical Bluetooth, noisy-counter and field qualification are separate outstanding gates.
