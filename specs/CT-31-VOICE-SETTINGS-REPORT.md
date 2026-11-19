# CT-31 Voice Settings — implementation note

**Date:** 30 October 2026  
**Qualification:** Emulator-tested partial workflow; not a continuous voice qualification.

## Implemented

- Native Voice Settings screen linked from the counter and Shop Home.
- Persisted language preference for Hindi/Hinglish (`hi-IN`), Marathi (`mr-IN`) and English India (`en-IN`) in encrypted local metadata.
- Shop-specific product alias editing through the existing product catalogue and its audited persistence command.
- Voice safety/capability disclosure: recognized commands still require review; this build uses one-utterance recognition, and continuous Bluetooth listening is not implemented or qualified.
- Instrumentation covers loading a configured product, saving aliases, changing language, reopening the model to verify persisted values and audit history, and confirming the settings flow did not create a sale.

## Evidence

The focused API 37 emulator test `voiceSettingsPersistsLanguageAndAliasesWithoutExecutingBusinessCommands` passed. The complete verification after this screen and test were added passed Node 48/48, Kotlin JVM 28/28, Android instrumentation 26/26, debug and instrumentation APK builds, and Android lint with 0 errors / 92 warnings. Evidence:

- `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`
- `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`
- `android/app/build/reports/lint-results-debug.txt`
- `test-evidence/2026-10-30/verification.txt`

## Not included or qualified

This is settings and alias management only. It does not establish multilingual speech recognition accuracy, continuous listening, Bluetooth microphone routing, background operation, headset controls, noisy-shop performance, voice command execution, or hardware qualification. These remain CAT-048B/C and CAT-049/053 work. The whole Android screen inventory and product remain incomplete.
