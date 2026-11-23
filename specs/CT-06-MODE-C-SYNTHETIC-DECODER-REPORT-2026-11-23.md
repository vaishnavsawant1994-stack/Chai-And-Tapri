# CT-06 Mode C — Synthetic Image Decoder Report

**Date:** 23 November 2026  
**Status:** Synthetic QR image decoding passed on Android API 37 emulator; camera-device qualification remains open.

## Change

`CameraBarcodeScannerActivity` now obtains its ML Kit scanner through `newProductBarcodeScanner()`. The supported symbology list is reusable by an instrumentation test without opening the camera. ZXing Core 3.5.4 is added only to the Android instrumentation-test configuration to construct a deterministic QR bitmap; it is not a production dependency.

## Evidence

- Test: `mlKitCameraDecoderReadsSyntheticProductQrImage`
- Device: `Tapri_Recovery_Isolated(AVD) - 17` (Android API 37, emulator)
- Command: `gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.chaiandtapri.counter.SqliteCounterPersistenceTest#mlKitCameraDecoderReadsSyntheticProductQrImage"`
- Result: 1 test, 0 failures, 0 errors, 0 skipped.
- JUnit XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (generated, ignored build output).

The test generates a unique synthetic product code, encodes a QR bitmap, runs the production scanner configuration against `InputImage.fromBitmap`, and checks the decoded raw value and QR format. The ordinary camera-result/cart test remains separate and covers catalogue resolution without committing a sale.

## Limits

This does not exercise camera capture, CameraX frame delivery, focus, exposure, motion blur, glare, damaged labels, real products, or a physical device. It is not field qualification of Mode C. The scanner still returns only a raw code; Quick Sale performs catalogue validation and checkout remains the transaction commit point.

## Regression status

The focused decoder test passed. A full local regression completed with:

- Prototype Node: 52 passed, 0 failed.
- Backend Node: 26 passed, 0 failed, 4 PostgreSQL-dependent tests skipped because no isolated database endpoint was configured.
- Kotlin JVM: 68 passed, 0 failed.
- Android instrumentation: 118 tests scheduled; 116 passed and 2 failed at explicit `Assume.assumeTrue` guards because their isolated HTTPS/PostgreSQL endpoint and opt-in phase were not configured. The failures were `scheduledWorkerResumesAfterAppProcessRestart` and `workerRetriesCommittedEventsAfterAckLossWithoutDuplicates`; these are blocked integration scenarios, not decoder failures. Full report: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (generated, ignored build output).
- Debug and instrumentation APK build tasks: passed.
- Android lint: 0 errors, 175 warnings.

The broad Android suite is therefore not green; only the 116 non-endpoint-gated instrumentation cases passed. No live backend integration test or physical camera scan was qualified.
