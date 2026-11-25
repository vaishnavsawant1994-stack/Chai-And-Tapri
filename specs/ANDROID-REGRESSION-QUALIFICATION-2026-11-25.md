# Android Regression Qualification - 25 November 2026

## Result

The full serial-connected API 37 emulator instrumentation suite passed **145/145**, with **0 failures, 0 errors, and 0 skipped tests**, on `Tapri_Recovery_Isolated (AVD) - 17`. The run completed successfully in 21m37s. JUnit evidence is retained at [full instrumentation XML](../test-evidence/2026-11-25/android-full-regression-145/full-instrumentation.xml).

The same source revision passed **92/92 Kotlin JVM tests**, **52/52 prototype Node tests**, and the backend no-database run passed **33 tests with 6 PostgreSQL-dependent tests skipped** (39 total, 0 failures). `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` passed. Lint reports **0 errors and 189 warnings**. The `npm` PowerShell shim was blocked by the host execution policy; the backend package's `node --test test/*.test.mjs` command was run directly and passed.

## Corrections made

- Updated six instrumentation assertions whose expected Quick Sale draft labels predated configured base-unit display. They now assert the visible `units` label used by those synthetic products.
- Guarded the initial transaction snapshot in `CounterModel.apply` against a recovery/backup maintenance barrier. A rejected write now returns an explicit "not recorded" result before any mutation. Equivalent guarded snapshot handling already protects the two Quick Sale sale-entry paths because they read persisted product configuration before reaching the commit barrier.
- The focused `maintenanceBarrierRejectsAndRollsBackBusinessWritesThenReleases` emulator test passed after the fix; the full suite then passed.

The first full run against the unit-label UI change exposed six stale assertions and one maintenance-barrier exception escape. The updated full run verifies those corrections. The barrier was not weakened; conflicting writes remain rejected.

## Scope and limits

This qualifies the current test suite on one synthetic API 37 emulator and the covered flows. It does not establish full product completion, physical-device compatibility, Bluetooth microphone routing, noisy-shop speech accuracy, cross-device recovery, independent security review, browser visual authorization, production synchronization, or production readiness. CAT-051B's broader user-led clean-install recovery gate and the remaining Wave 1-6 scope remain tracked separately.