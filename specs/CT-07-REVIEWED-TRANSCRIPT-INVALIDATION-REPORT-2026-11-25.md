# CT-07 reviewed transcript invalidation — 25 November 2026

## Change

Editing the recognized/typed transcript now immediately clears the pending structured command and its command ID. The screen explains that the transcript changed and the operator must propose it again before applying. Typed edits therefore cannot accidentally commit an earlier review. Re-proposing produces a fresh command ID and parses the edited contents.

This applies to normal text edits and speech-recognition results, because both update the same `EditText`. It does not change the transaction engine or the one-utterance speech model.

## Verification

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; 80 Kotlin JVM tests passed, 0 failed.
- Focused API 37 emulator test `editingRecognizedTranscriptInvalidatesReviewedCommandUntilReproposed`: **1/1 passed**.
- The instrumentation test creates a synthetic owner and configured product, proposes a two-item order, edits the transcript to one item, verifies the old proposal is gone and Apply creates no order, then re-proposes and verifies the one-item order is recorded.
- `lintDebug`: **BUILD SUCCESSFUL**, 183 existing/project warnings reported by the HTML report; no lint failure. Report: `android/app/build/reports/lint-results-debug.html`.
- The later full default API 37 instrumentation regression passed **139/139**, 0 failures/errors/skips, including this CT-07 test and the CT-06 receipt journey. JUnit: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.

## Scope limits

This verifies stale-proposal invalidation in the native UI on an emulator. It does not qualify speech recognition accuracy, continuous listening, Bluetooth routing, physical devices, noisy-shop use, the complete Android screen inventory, production security or release readiness.

