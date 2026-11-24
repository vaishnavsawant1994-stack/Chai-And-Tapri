# CT-22 Customer Details Profile Edit — 24 November 2026

## Implemented

The owner can now add a missing customer profile or edit an existing profile from that customer's CT-22 details screen. The form uses the existing encrypted customer-profile APIs and the existing owner-only persistence authorization. Phone storage is optional and explicitly consented. Unchecking consent removes the phone from the submitted profile; a separate action clears a previously stored number and revokes consent. The customer account ID remains fixed while editing.

The screen continues to label contact information as consent-based, display only the last four phone digits, and preserve the existing credit and payment history. No marketing or messaging behavior was added.

## Verification

- Focused API 37 instrumentation: **4 passed across four separate invocations, 0 failures**. `customerDirectoryOpensDetailsWithPersistedCreditAndRepaymentHistory` covers CT-21 to CT-22 navigation and cancel/no-mutation. `customerDetailsEditRevokesConsentThroughUiWithoutChangingCreditOrStock` edits the display name, revokes phone consent through the Android dialog, and verifies encrypted persistence, unchanged ₹24 receivable, one order, and four remaining units. `customerDetailsAddProfileUiCreatesOptionalContactWithoutChangingCredit` creates a profile without collecting phone/consent and verifies the existing ₹10 receivable, order, and stock remain unchanged. `managerCustomerDetailsHidesOwnerOnlyProfileEditor` verifies the owner-only editor is hidden for a manager.
- An initial run failed only because the new test expected stock 2 instead of the correct 3 (5 opening units minus 2 sold). The assertion was corrected; the rerun passed.
- The edit test initially exposed instability in Android's active-window accessibility tree. The test now accesses the shown dialog through a `@VisibleForTesting` reference and drives the actual EditText, consent checkbox and save button, avoiding timing-dependent accessibility-tree discovery.
- Full-source Android verification: Kotlin JVM **81/81**; debug APK, instrumentation APK and unit-test build tasks passed; lint **0 errors / 182 warnings**. The four focused API 37 UI cases above each passed in their own invocation; the manager visibility case was rerun last and passed 1/1 (`tests=1`, `failures=0`, `errors=0`).
- Evidence: generated Gradle report at `android/app/build/reports/androidTests/connected/debug/index.html`. Focused JUnit XML copies are in ignored local directory `test-evidence/2026-11-24/ct22-customer-details/`: `ct22-navigation-cancel.xml`, `ct22-edit-revoke.xml`, `ct22-add-profile.xml`, and `ct22-manager-visibility.xml`. These artifacts are local and not committed.

## Scope and remaining work

This qualifies the tested owner add/edit flows, phone-consent revocation, editor cancellation, and manager visibility restriction on API 37. Existing model-level tests cover profile validation, encrypted persistence, audit redaction, and no credit mutation. CT-22 remains partial; customer messaging, profile deletion, authenticated server history, and visual/manual usability review are not included.

## Subsequent emulator regression attempt — 24 November 2026

A later attempt to rerun the full connected instrumentation suite did not complete. It was stopped after the CT-28 daily-closing test launch stalled. A clean, single-test retry then failed because the test could not find its acknowledgement control; the captured UI was the Android system dialog `Pixel Launcher isn't responding`. JUnit reports one failure and no test errors. This does not invalidate the earlier focused CT-22 passes, but the complete current-source instrumentation suite is not qualified by this rerun. Local ignored artifacts: `test-evidence/2026-11-24/android-regression-investigation/ct28-daily-closing-focused-failure.xml` and `ct28-daily-closing-focused-logcat.txt`. The emulator issue needs a clean runtime investigation before claiming broad regression success.
