# CAT-049 Android Bluetooth Counter — Implementation Status

## Current result

The native Kotlin prototype now builds as a debug APK. The clean build and native JVM tests pass locally; runtime installation and physical-device qualification remain open. See [CAT-049A build status](CAT-049A-ANDROID-BUILD-STATUS.md).

## Implemented prototype

- Native counter screen with explicit Start, Pause, Resume, Stop, and “Listen for next command” controls.
- Runtime microphone permission and a language selector for Hindi/Hinglish (`hi-IN`), Marathi (`mr-IN`), and English India (`en-IN`).
- Android `SpeechRecognizer` adapter for one utterance at a time, surfaced as an editable transcript and proposed command. Applying requires an explicit operator action; reject leaves business state unchanged. Typed transcript entry remains available.
- Android communication-device listing and a user-selected Bluetooth communication route on API 31+. The UI explicitly labels the route and actual recognition microphone source as unverified.
- Local demo order model for a limited product/command vocabulary, with separate order/payment state, guarded transitions, idempotent command IDs and audited cancellation. SQLite persists orders, stock, command receipts, audit events, stock movements and an outbox atomically; its Android instrumentation test compiles but has not run.
- Listener cleanup on pause/stop/backgrounding and headset removal; route is released and does not auto-resume.

## Not implemented or not qualified

- The Android model is a Kotlin demo subset. It is not connected to the browser CAT-047 transaction engine and does not provide full contract parity. SQLite now persists order/line state, payment and fulfillment statuses, stock, command receipts, audit entries, stock movements, customer credit/repayment records and a pending sync outbox atomically. It has no encryption, sync worker, multi-user identity, production-grade conflict handling or restore qualification.
- Product setup is hard-coded; only a small command vocabulary is supported. Credit/udhar, partial handover, split payment, inventory purchasing and full accounting are not implemented in this Android model.
- Recognition is a segmented, single-utterance `SpeechRecognizer` flow that the user manually arms again. It is not continuous Bluetooth counter listening and does not run in the background.
- No booted emulator or physical phone was available. A SQLite restart/idempotency instrumentation test is written and compiles, but has not been executed. Microphone audio, headset microphone source, language accuracy, interruptions, battery, screen lock and shop noise have not been tested.
- CAT-048B/C physical speech trials and CAT-046 visual/browser verification remain pending.

## Technical boundary

The Android speech adapter produces transcript events only; it does not commit transactions. The operator reviews and applies a structured proposal, which is validated by the local Kotlin demo model. This is a safety prototype, not a production transaction service.

Android `SpeechRecognizer` is documented as unsuitable for continuous recognition; a dedicated streaming/on-device strategy must be evaluated before Mode B2. See the [SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer). `AudioManager.setCommunicationDevice` is available from API 31; selecting an output communication device does not prove the speech service consumed its matching Bluetooth input. See [AudioManager API](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)).

## Next qualification step

Run the APK on a booted Android 12+ emulator or physical phone. Verify launch and demo commands first, then measure actual captured input with a Bluetooth headset and handset microphone. Keep hardware evidence separate from JVM test results. Implement a native durable event store and shared command contract before treating this as a shop pilot build.

See [emulator diagnosis](CAT-049-EMULATOR-DIAGNOSTIC.md) and separate [CAT-049B](CAT-049B-MICROPHONE-STATUS.md) / [CAT-049C](CAT-049C-CONTINUOUS-SESSION-STATUS.md) status reports.



