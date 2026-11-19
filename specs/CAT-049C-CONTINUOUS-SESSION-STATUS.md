# CAT-049C — Continuous Voice Counter Status

**Status: Segmented single-utterance prototype implemented; continuous Mode B2 not implemented or qualified.**

## Implemented flow

1. The operator explicitly starts a listening session while the app is visible.
2. Android `SpeechRecognizer` handles one short utterance.
3. The transcript is shown in an editable field and parsed into a proposed command.
4. The operator applies or explicitly rejects the proposal. Rejected/ambiguous/unsupported text does not change the model.
5. The operator manually selects “Listen for next command.”
6. Stop, pause, lifecycle backgrounding, or route disconnection stops recognition; reconnection does not silently restart it.

The limited native model supports numbered-order create, add/remove, preparation, partial/full handover, staff-declared cash, UPI pending, customer credit/repayments, and reasoned cancellation. SQLite persists current projections, command receipts, stock movement audit, customer credit balances/repayment history, append-only payment entries, and a pending sync outbox. On 29 October 2026, the SQLite restart/idempotency/v1-to-v2 migration scenario passed 1/1 on the API 37 emulator, and an emulator UI order/payment remained after app force-stop and relaunch. This is prototype runtime evidence; the model remains a Kotlin subset and is not the authoritative browser transaction engine. Continuous Mode B2, audio session streaming, physical headset operation, and long-running lifecycle behavior remain unimplemented or unqualified.

## Not yet delivered

- No continuously streaming recognizer, automatic utterance boundary detection, wake phrase, or hands-free re-arm.
- No background/lock-screen session, foreground service, earbud feedback, headset button controls, or measured battery/latency behavior.
- No sync worker, encryption, crash-recovery/device-replacement qualification, or migration beyond SQLite schema v2.
- No Android parity fixtures against all JS engine invariants; split tenders, refunds/returns, robust correction revisions, supplier/inventory purchasing and full accounting remain absent from the native subset.
- No runtime, device, headset, noisy-counter, multi-order physical trial, or field evidence.

Android's standard `SpeechRecognizer` is not intended for continuous recognition; evaluate a dedicated streaming or on-device engine before pursuing B2. See [official Android API guidance](https://developer.android.com/reference/android/speech/SpeechRecognizer).

## Current verification

The debug app and Android instrumentation APK build; the local Kotlin model/contract suite passes 17/17 and the browser Node suite passes 43/43. Neither result proves real speech operation or continuous behavior. CAT-049C remains not hardware-qualified and not field-qualified.



