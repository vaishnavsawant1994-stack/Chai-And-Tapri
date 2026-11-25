# Android Regression Qualification - 25 November 2026

## Result

The full serial-connected API 37 emulator instrumentation suite passed **145/145**, with **0 failures, 0 errors, and 0 skipped tests**, on `Tapri_Recovery_Isolated (AVD) - 17`. The latest exact-source run completed successfully in 31m07s. JUnit evidence is retained at [full instrumentation XML](../test-evidence/2026-11-25/android-full-regression-145/full-instrumentation.xml).

The current Android source passed **92/92 Kotlin JVM tests**; `testDebugUnitTest assembleDebug assembleDebugAndroidTest` passed. Prototype Node passed **52/52**. Backend no-database tests passed **33 with 6 PostgreSQL-dependent tests skipped** (39 total, 0 failures); the package-declared Node test command was run directly because PowerShell blocked the `npm.ps1` shim. `lintDebug` passed with **0 errors and 189 warnings**.

## Corrections made

- Updated six instrumentation assertions whose expected Quick Sale draft labels predated configured base-unit display. They now assert the visible `units` label used by those synthetic products.
- Added a shared guarded mutation-snapshot path and early recovery-barrier checks where mutation validation reads persisted catalogue data before snapshotting. `CounterModel.apply`, Quick Sale and split tender, opening/physical stock counts, product setup, purchase-order drafting, purchase receiving, parser product resolution and sale-line resolution fail closed if recovery blocks a catalogue read. Snapshot-first mutations return the same explicit no-write result if maintenance is active.
- Expanded `maintenanceBarrierRejectsAndRollsBackBusinessWritesThenReleases` to attempt nine mutations: command order, Quick Sale, split tender, opening stock, physical count, product addition, purchase-order draft, expense and cash collection. The API 37 instrumentation test passed and verifies that data, stock, payment, expense, catalogue and movement history do not change. The full suite then passed on the exact current source.

The first full run against the unit-label UI change exposed six stale assertions and one exception escaping from `CounterModel.apply` during a maintenance snapshot. The updated unit assertions, generalized fail-closed validation, focused nine-operation barrier test and full suite now pass. The barrier was not weakened; conflicting writes remain rejected.

## Recovery evidence

The project has a separate focused API 37 synthetic same-emulator clean-app-install restore qualification, recorded in [the CAT-051B implementation report](CAT-051B-IMPLEMENTATION-REPORT.md) and [the recovery qualification report](CAT-051B-SAF-UI-QUALIFICATION-2026-11-24.md). The ordinary full suite intentionally does not repeat that destructive drill without explicit arguments. This 145-test run is not a new clean-install restore run. Cross-device/person recovery, abrupt power loss, physical storage exhaustion, physical Keystore invalidation and independent security review remain unqualified.

## Scope and limits

This qualifies the current test suite on one synthetic API 37 emulator and covered flows. It does not establish full product completion, physical-device compatibility, Bluetooth microphone routing, noisy-shop speech accuracy, independent security review, browser visual authorization, production synchronization or production readiness. The remaining Wave 1-6 scope remains tracked separately.