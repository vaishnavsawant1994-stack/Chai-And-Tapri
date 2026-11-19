# CAT-049B — Android Microphone and Bluetooth Status

**Status: Implemented prototype; not device-qualified.**

## Emulator update — 29 October 2026

The app ran on Android 17/API 37. The microphone and Bluetooth permission prompts were shown and denied; the app displayed `PermissionDenied · typed mode remains available`. This qualifies the permission-denial fallback only. No audio was captured, no headset was connected, and Bluetooth microphone routing remains **not hardware-qualified**. See [Phase 0 qualification report](PHASE-0-QUALIFICATION-REPORT.md) and `../test-evidence/2026-10-29/`.

## Implemented

- `AndroidSpeechInput` wraps Android's `SpeechRecognizer` for a single user-started utterance and maps listener callbacks into ready, speech-started, transcript, and failure events.
- `MainActivity` requests `RECORD_AUDIO` at the moment the operator starts listening, shows permission state, exposes typed correction, and stops/releases recognition on stop, pause, backgrounding, or selected headset removal.
- On API 31+, the UI can inspect Android communication devices and request a Bluetooth SCO/BLE headset communication route through `AudioManager.setCommunicationDevice()`. It checks the API return and displays that route acceptance does not establish the recognition service's actual input source.
- Failed/unsupported recognition leaves the editable typed-command route available. The app does not persist raw audio.

## Evidence and limits

- `assembleDebug` compiles the adapter; 16 Kotlin JVM model/contract tests pass. These tests do not exercise Android audio hardware or microphone service callbacks.
- No physical device was connected. The API 37 x86_64 emulator now boots with WHPX; it supports app/runtime and permission-flow checks, not physical microphone/headset qualification. No captured audio, real Bluetooth route, headset input, disconnect handling, or live recognition transcript has been verified.
- The adapter uses system `SpeechRecognizer`; provider-side processing, network requirements, offline availability, and retention depend on device/service configuration. The app itself does not store the audio, but cannot promise that recognition occurs locally.
- No foreground microphone service is implemented. Capture only occurs in a visible, user-initiated utterance. Background/locked-screen capture is unsupported.

## Acceptance still required

Install and launch on Android 12+ hardware; exercise allow/deny/revoke permissions; confirm the selected headset route and verify actual captured input using a controlled phrase; disconnect/reconnect during listening; test handset fallback and interruption. Record device, Android version, headset, recognition provider, transcript, and outcome in CAT-048 trial records. Until then CAT-049B remains not device-qualified.




