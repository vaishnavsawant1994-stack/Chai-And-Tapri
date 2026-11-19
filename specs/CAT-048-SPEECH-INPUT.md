# CAT-048 â€” Browser Speech Input Spike

## Scope

This prototype adds a feature-detected browser SpeechRecognition adapter for a single utterance at a time. It offers Hindi/Hinglish (`hi-IN`), Marathi (`mr-IN`) and English India (`en-IN`) recognition hints on B1 and B2. Speech is inserted into the existing text field. B1 still requires Preview and Add to draft; B2 still requires Run command. The adapter has no access to the transaction engine.

CAT-048A now normalizes a limited, explicit set of Devanagari Hindi/Marathi words, Devanagari numerals, common order states, product terms, and cash/UPI phrases into the existing parser vocabulary. Parser outputs preserve the original transcript for review and include normalized text for diagnostics. The new `order.remove_line` engine command requires an identified editable order and product, records an audit event, and does not post stock or a sale.

The supported flow is:

`Microphone â†’ browser recognition â†’ editable transcript â†’ existing CT-07 parser â†’ existing validated command engine`

## Safety and fallback

- No recognized result is automatically executed.
- Unsupported browser, microphone denial, empty results and startup/runtime errors leave the typed command path available and report a short status.
- A recognized UPI verification claim remains rejected by the existing parser because this prototype has no payment-provider evidence.
- Recognition is one-shot, not continuous; no raw audio is persisted by this adapter.

## Verification

`node --test prototype/speech-input.test.cjs prototype/transaction-engine.test.cjs prototype/voice-counter.test.cjs`

The adapter tests cover supported recognition callbacks, transcript-only behavior and unsupported-browser fallback. Parser tests cover Devanagari Hindi/Marathi product phrases, order number normalization, handover, cash amount parsing, UPI pending, and safe rejection without an order number. The engine test covers audited draft item removal without stock/sale effects. The current combined Node suite passes 43 tests, including shared parser contract fixtures.

## Limits / completion gate

This is a browser speech-input spike, not CAT-048 qualification. Browser SpeechRecognition support, language availability, network dependence, microphone permission behavior and accuracy vary by browser/device. Hindi/Hinglish is represented by a Hindi locale hint; it is not a dedicated Hinglish model. Devanagari support is a limited phrase normalization dictionary, not general transliteration, and shop-specific Devanagari aliases are not yet editable in the UI. No real-device acoustic, counter-noise, latency, recognition-error or accessibility study has been performed. CAT-048B/C remain open until actual device and noisy-counter results are recorded. CAT-049 now contains a native one-utterance adapter and communication-route selection prototype. Background audio, continuous Bluetooth capture, device behavior and headset microphone source remain unverified.

Trial instructions and a result validator are in [CAT-048B/C Device and Noise Trials](CAT-048-DEVICE-AND-NOISE-TRIALS.md). Android audio-session design is tracked separately in [CAT-049 Android Bluetooth Spike](CAT-049-ANDROID-BLUETOOTH-SPIKE.md).

## Android speech adapter update (28 October 2026)

A native Android adapter has since been added in CAT-049 using the platform `SpeechRecognizer`. It captures one operator-started utterance at a time and sends the recognized text to a reviewable command proposal; it does not directly execute a transaction. CAT-049A's clean debug build and 16 JVM model/contract tests pass. This does not qualify Android speech accuracy: no app launch, microphone capture, Bluetooth microphone source, or physical-device trial has been verified. Android and browser trials should be recorded as separate device/runtime combinations; do not enter simulated transcripts as microphone trials. The standard Android recognizer is not intended for continuous recognition, so this is not Mode B2. See [CAT-049 Android status](CAT-049-ANDROID-BLUETOOTH-SPIKE.md) and [CAT-049A build status](CAT-049A-ANDROID-BUILD-STATUS.md).


