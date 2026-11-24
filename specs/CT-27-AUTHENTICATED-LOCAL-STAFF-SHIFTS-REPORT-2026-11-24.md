# CT-27 — Authenticated Local Staff Shifts

**Status:** Implemented and emulator-qualified for the documented synthetic flows. The latest serial-pinned full API 37 instrumentation run passed 137/137. An earlier run had become unstable with a stale System UI dialog; after rebooting the disposable AVD, the previously failing sales-history export case passed alone and the complete suite passed. This remains a local shift/cash workflow, not complete attendance or staff accountability.

## Changes

- New shift start derives its identity from the active local owner/staff session. The UI no longer asks the operator to type the shift owner's name.
- The encrypted local shift record stores the authenticated actor ID in additive SQLite schema v30. Existing v29 shift rows migrate with a null actor ID, preserving the recorded cash and handover fields.
- A cashier can start a shift and close only their own authenticated shift. A manager can review and close another operator's shift. Cash drawer cash-in/out and drawer close remain manager restricted.
- Cashiers see only their own shift history. Managers can see all local shift records. Legacy unauthenticated labels remain explicitly marked unverified and require manager handling.
- Local staff actor IDs, operator labels, receiver labels and handover notes remain omitted from the server staff-shift projection.

## Verification

- `node --test prototype/*.test.cjs`: 52 passed, 0 failed.
- `cd backend; npm.cmd test`: 30 passed, 5 PostgreSQL-dependent skipped, 0 failed.
- `android/gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --console=plain`: passed. Kotlin JVM reports 81 passed, 0 failed; APK packaging passed; lint 0 errors, 182 warnings.
- Focused API 37 run: 5 passed, 0 failed. It covered authenticated actor persistence; denying a different cashier's shift-close attempt without changing the active shift; preserving an existing shift through the v29-to-v30 migration; existing drawer projection behavior; and synthetic recovery snapshot behavior.
- Full serial-pinned offline API 37 run using `android/scripts/run-isolated-connected-tests.cmd emulator-5554`: 137 passed, 0 failed, 0 errors, 0 skipped, including all five shift/migration/backup cases. JUnit: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`; HTML: `android/app/build/reports/androidTests/connected/debug/index.html`.
- Failed-first-attempt provenance: a previous run used the same AVD while it was left on a System UI “not responding” dialog and recorded 99/137. The AVD was rebooted, both APKs reinstalled, the originally failing sales-history export test passed 1/1, and the clean full run above passed. The failed attempt is not the current qualification result.
- `git diff --check`: passed.

## Limits

- Local login identity does not establish government or employment identity. PIN/device access security is only as strong as the configured local credentials and device controls.
- The shift view attributes only cash events linked to a drawer session. It does not assign all sales, refunds, inventory actions, or attendance to a staff member.
- Legacy sessions have no verified actor and can only be reviewed by a manager after this change.
- No multi-register handoff policy, payroll, attendance rules, cross-device identity proof, or production staff security review is included.
- Physical device, field/shop, independent security review, and production qualification remain separate gates.
