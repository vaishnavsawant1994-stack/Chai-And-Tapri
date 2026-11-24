# CT-22 Customer Details Profile Edit — 24 November 2026

## Implemented

The owner can now add a missing customer profile or edit an existing profile from that customer's CT-22 details screen. The form uses the existing encrypted customer-profile APIs and the existing owner-only persistence authorization. Phone storage is optional and explicitly consented. Unchecking consent removes the phone from the submitted profile; a separate action clears a previously stored number and revokes consent. The customer account ID remains fixed while editing.

The screen continues to label contact information as consent-based, display only the last four phone digits, and preserve the existing credit and payment history. No marketing or messaging behavior was added.

## Verification

- Focused API 37 instrumentation test: **1 passed, 0 failures** (`customerDirectoryOpensDetailsWithPersistedCreditAndRepaymentHistory`). It navigates from CT-21 to CT-22, opens the new edit action, dismisses without saving, then reopens encrypted local records and verifies the profile, ₹20 outstanding credit, one order, and three remaining units are unchanged.
- An initial run failed only because the new test expected stock 2 instead of the correct 3 (5 opening units minus 2 sold). The assertion was corrected; the rerun passed.
- Final combined Android verification: Kotlin JVM **81/81**; focused API 37 instrumentation **1/1**; debug APK, instrumentation APK and unit-test build tasks passed; lint **0 errors / 182 warnings**.
- Evidence: Gradle report at `android/app/build/reports/androidTests/connected/debug/index.html`; JUnit XML at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (generated, not committed).

## Scope and remaining work

This qualifies the profile-editor entry point and its cancel/no-mutation behavior, not the complete edit, consent-revocation, add-profile, or manager-permission UI paths. Existing model-level tests cover profile validation, encrypted persistence, consent revocation, audit redaction, and no credit mutation. Those UI paths still need dedicated instrumentation. CT-22 remains partial; customer messaging, profile deletion, authenticated server history, and visual/manual usability review are not included.
