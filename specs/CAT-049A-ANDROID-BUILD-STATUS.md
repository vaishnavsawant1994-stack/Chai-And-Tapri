# CAT-049A — Android Build Qualification Status

**Status: Build- and emulator-qualified for the tested prototype flows. Physical microphone/headset and field qualification remain pending.**

## Environment

- Android Studio: AndroidStudio2026.2.2, build `AI-262.10968.63.2622.16730706`.
- Android SDK: `C:\Users\omgaj\AppData\Local\Android\Sdk`.
- Installed platform: Android API 37; Build Tools 36.0.0; platform-tools/ADB and emulator.
- Java: 21.0.12.1.
- Gradle wrapper: 9.7.1; Android Gradle Plugin: 9.4.1; built-in Kotlin support.
- The local SDK path is recorded in ignored `android/local.properties`; it is machine-specific.

## Verified locally

Command from `android/`:

```powershell
.\gradlew.bat clean assembleDebug assembleDebugAndroidTest testDebugUnitTest --console=plain
```

The initial build record below was 16 Kotlin tests. After payment-event ledger and schema migration work, the latest result is **17 Kotlin tests passed**, as reported in the final section. `assembleDebugAndroidTest` builds the SQLite persistence/migration test APK. The wrapper was adjusted to launch correctly from the workspace path containing spaces and `&`. A compile error from the reserved Kotlin package component `in` was fixed by moving the app package to `com.chaiandtapri.counter`.

APK: `android/app/build/outputs/apk/debug/app-debug.apk` (7,133,385 bytes as of 29 Oct 2026), app ID `com.chaiandtapri.counter`, version `0.1.0`, min SDK 31, target SDK 35, compile SDK 37. It is a debug build, not a release artifact.

Android instrumentation APK: `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` (877,412 bytes as of 29 Oct 2026). The instrumentation scenario exercises an additive v1-to-v2 database migration, orders, stock, audit, cash, UPI-pending, customer-credit repayment, payment-event persistence and duplicate-command recovery after SQLite store recreation. Partial handover is covered by JVM model tests.

## Installation blocker — historical pre-WHPX state

`adb devices` returned no connected devices. The installed x86_64 API 37 AVD could not boot: the emulator reported that x86_64 emulation requires hardware acceleration and that the Android Emulator hypervisor driver is not installed. Starting with software acceleration did not produce an online ADB device. No APK installation, app launch, screenshots, Bluetooth hardware routing, or live microphone test has been completed.

This was the pre-restart status. It was superseded by the post-restart emulator results below.

## Scope limits

Emulator tests do not establish full Android UI or production SQLite qualification. The SQLite database is unencrypted prototype storage; v1→v2 migration is tested, but later migrations, outbox draining, sync worker, corruption, and backup recovery are not. CAT-049B headset microphone capture and CAT-049C continuous/background sessions remain unqualified. Speech is currently one user-started Android `SpeechRecognizer` utterance at a time; route selection is not proof that the recognizer used the Bluetooth microphone. See [Android README](../android/README.md) and [CAT-049 spike status](CAT-049-ANDROID-BLUETOOTH-SPIKE.md).

## Prototype release checklist

- [x] Configure Android SDK and Gradle wrapper for this workstation.
- [x] Build debug APK after clean and run Kotlin JVM tests.
- [x] Install and launch on the API 37 emulator.
- [ ] Verify microphone permission and actual speech recognition on device.
- [ ] Verify Bluetooth headset is the captured microphone source.
- [ ] Complete noisy-counter and physical trial protocol.
- [ ] Qualify continuous Mode B2 (separate from the current one-utterance adapter).
- [x] Implement local SQLite state, audit/movement tables and transactional sync outbox.
- [x] Run SQLite restart/idempotency test and additive v1→v2 migration test on emulator.
- [ ] Add encryption, sync worker, corruption/backup recovery, later migrations, and production recovery before pilot use.
- [ ] Complete authorized CT-06/CT-07 visual verification.

## Lint result

`.\gradlew.bat lintDebug --rerun-tasks --console=plain` completed successfully after correcting the ignored local SDK property format. Android lint reported 30 warnings (including target SDK 35 and newer dependency/version suggestions); there were no remaining lint errors. Review these warnings before broader release qualification. Lint does not substitute for installing or running the app.


See [emulator diagnosis](CAT-049-EMULATOR-DIAGNOSTIC.md) for host virtualization findings and the required WHPX next step.

## WHPX update before restart — historical, 29 October 2026

The user authorized enabling WHPX. An elevated query found the feature disabled; only `HypervisorPlatform` was enabled with `/NoRestart`. DISM returned 3010 (restart required), and the follow-up query reported the feature enabled. At that point the host had not restarted, the acceleration check returned 6 and no ADB device was present. The restart was later authorized and completed; see the post-restart result below.

## Post-restart emulator results — 29 October 2026

WHPX reports installed and usable; `Medium_Phone_API_37.0` booted to Android 17/API 37 and ADB reported `device`. `installDebug` succeeded. The latest `connectedDebugAndroidTest` succeeded with **1 test, 0 failures**; it exercises v1-to-v2 migration, SQLite persistence, payment entries and idempotency. Installing the updated app over the existing v1 database also retained synthetic order #12. `MainActivity` launched and was foregrounded. A demo order was created, marked ready, handed over, settled with staff-declared cash, and remained visible after force-stop/relaunch. Rejecting a proposal left business data unchanged; microphone/Bluetooth permission denial left typed mode available. See [Phase 0 report](PHASE-0-QUALIFICATION-REPORT.md) and [evidence directory](../test-evidence/2026-10-29/).

CAT-049A is now **emulator-qualified for this build and tested flows**. Physical microphone, Bluetooth capture source, continuous listening, full UI/business coverage, and production readiness are not qualified.








## Fresh recheck — 29 October 2026

The clean Gradle command `clean assembleDebug assembleDebugAndroidTest testDebugUnitTest --console=plain` completed with `BUILD SUCCESSFUL`; Kotlin JVM tests: 16 passed, 0 failed. Both APKs were rebuilt at the paths above. Node tests: 43 passed, 0 failed. Current APK sizes are 7,009,953 bytes (debug) and 862,319 bytes (androidTest).

Historical pre-restart status: the emulator was unavailable, `-accel-check` returned 6, and ADB was empty. This status was superseded after WHPX activation; current emulator results are reported above.

## CAT-050 follow-up — 29 October 2026

The superseding current counts are Node 45/45, Kotlin JVM 19/19, and Android emulator instrumentation 2/2 methods. `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`, and `connectedDebugAndroidTest` pass. Lint remains at 0 errors and 30 warnings. The expanded SQLite instrumentation covers v1-to-v3 migration, durable refund/payment records, and rollback on an induced partial commit failure. See [CAT-050 parity report](CAT-050-NATIVE-TRANSACTION-PARITY.md) and [Phase 0 qualification report](PHASE-0-QUALIFICATION-REPORT.md).
