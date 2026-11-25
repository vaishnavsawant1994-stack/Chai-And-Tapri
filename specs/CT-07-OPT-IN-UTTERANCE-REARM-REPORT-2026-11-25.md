# CT-07 opt-in one-utterance re-arming — 25 November 2026

## Change

The Android counter now offers an unchecked-by-default **Auto-listen after Apply/Reject (separate utterances)** setting. When enabled during an explicitly started foreground session, an accepted manual review, rejection, no-match, or speech-timeout can schedule one new Android `SpeechRecognizer` utterance after a short delay. The setting never applies a proposed business command automatically. Ambiguous/unresolved transcripts and other recognition failures do not re-arm. Pause, Stop, leaving the foreground, Bluetooth route disconnection, recovery maintenance, and disabling the setting cancel scheduled re-arming; returning to the foreground requires an explicit Resume.

This is repeated single-utterance capture, not uninterrupted or background continuous recognition. The UI retains an explicit disclosure. Bluetooth route presence does not verify which microphone the recognition provider used.

## Verification

- Kotlin JVM: **98/98 passed** (`testDebugUnitTest`; increased from 96 with two policy tests).
- Android API 37 emulator: **1/1 focused instrumentation passed** (`voiceCounterAutoRearmsOnlyAfterManualReviewAndStopCancelsNextUtterance`). It verified default opt-out, one manually applied order, scheduled next-utterance callback, and Stop cancellation without duplicate posting.
- Prototype Node: **52/52 passed**.
- Backend Node: **33 passed, 6 skipped** (all six require PostgreSQL; 0 failed).
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`: **BUILD SUCCESSFUL**. Android lint reported **0 errors / 191 warnings**.
- A subsequent full current-source API 37 regression passed **151/151** (0 failures/errors/skips) in 43m21s, including this feature. This supersedes the previously reported 149/150 result. See `ANDROID-REGRESSION-RERUN-2026-11-25.md` and its 26 November addendum.

## Limitations

No physical microphone, headset input source, background service, genuinely continuous recognition, noisy-counter accuracy, battery endurance, or field behavior was tested. Re-arming after no-match/timeout is an opt-in retry, not a command result. The proposal still requires explicit Apply/Reject, and the normal typed/touch fallback remains available.

Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (focused one-test API 37 report). Kotlin JVM XML reports are under `android/app/build/test-results/testDebugUnitTest/`.
