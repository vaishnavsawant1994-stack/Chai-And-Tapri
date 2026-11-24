# CT-27 — Authenticated Local Staff Shifts

**Status:** Implemented and qualified for the focused synthetic API 37 flows. The full connected instrumentation regression is not green because the emulator/System UI became unresponsive during unrelated UI cases. This remains a local shift/cash workflow, not complete attendance or staff accountability.

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
- Current full offline API 37 run: 137 tests, 99 passed, 38 failed, 0 errors, 0 skipped. All five shift/migration/backup tests listed above passed in the full run. The first UI failure was a sales-history export control lookup; subsequent failures commonly captured the Android System UI dialog “System UI isn't responding,” and multiple UI tests then could not interact with the app. Treat the full regression as failed/unqualified, not as a product-wide pass. JUnit: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`; HTML: `android/app/build/reports/androidTests/connected/debug/index.html`.
- `git diff --check`: passed.

## Limits

- Local login identity does not establish government or employment identity. PIN/device access security is only as strong as the configured local credentials and device controls.
- The shift view attributes only cash events linked to a drawer session. It does not assign all sales, refunds, inventory actions, or attendance to a staff member.
- Legacy sessions have no verified actor and can only be reviewed by a manager after this change.
- No multi-register handoff policy, payroll, attendance rules, cross-device identity proof, or production staff security review is included.
- Physical device, field/shop, independent security review, and production qualification remain separate gates.
