# CT-04 Device Permissions — Implementation Report

**Date:** 30 October 2026  
**Status:** Partial native permission workflow. API 37 manual denial, permanent denial, app Settings grant and return were exercised. Current focused instrumentation verifies the initial optional/typed-fallback state; a direct runtime-prompt Allow selection remains unqualified.

## Implemented

- Added `DevicePermissionsActivity`, linked from Welcome and the Counter.
- Displays current microphone and Bluetooth-connect permission state, why each is optional, and the typed-mode fallback.
- Requests only `RECORD_AUDIO` and (Android 12+) `BLUETOOTH_CONNECT`, and only after the operator taps the request button.
- Opens Android app settings if a permission must be changed there.
- Remembers whether the app previously requested microphone/Bluetooth access and distinguishes not-yet-requested, retryable denial, and a state that should be reviewed in Android Settings.
- Keeps the typed fallback visible for each denied state.
- States that camera access is not requested until scanning exists and no broad file permission is needed because backup/restore uses the Android document picker.
- Makes no claim that Bluetooth connection proves which microphone is providing audio or that speech is continuous/background.

## Verification

- API 37 instrumentation test `devicePermissionsScreenDisclosesOptionalAccessAndTypedFallback` checks explanatory content, revoked-permission fallback and request action.
- Current-source focused API 37 rerun passed 1/1 after the CT-03 test updates; JUnit evidence: `../test-evidence/2026-11-02/ct04-permission-screen-current-focused.xml`.
- Manual API 37 UI: requested microphone and Bluetooth access, denied both, denied again to reach Android's no-more-prompt state, opened app settings, granted both, returned to the app and verified the display refreshed to Granted.
- Latest full run: Node 48/48; Kotlin JVM 28/28; Android instrumentation 31/31; debug/test APK builds pass; lint 0 errors/104 warnings (107 total tests).

## Limitations

- The initial live runtime prompt was manually denied; permanent-denial and Settings return were manually verified. Allow was granted through app Settings rather than directly from the runtime prompt.
- An instrumentation-only attempt to grant RECORD_AUDIO directly was rejected by Android with `SecurityException: Calling from not trusted UID`; that attempted extension was reverted. It does not affect the passing permission screen or the earlier manual Settings grant evidence.
- There is no camera/scanning, notification, location, contact or background-microphone permission flow.
- Permission availability does not qualify actual microphone capture, Bluetooth routing, background operation, or field use.
- Device permission state is not authentication or authorization. CT-03 is a separate local owner gate; staff accounts/roles remain unimplemented.

## Manual evidence

- Runtime prompts: `../test-evidence/2026-10-30/ct04-permission-dialog.xml`, `ct04-bluetooth-dialog.xml`, `ct04-permanent-denial-dialog.xml`.
- Denial and typed fallback: `../test-evidence/2026-10-30/ct04-denied.png`, `ct04-denied-state.xml`, `ct04-permanent-denial.xml`.
- Settings return and grant: `ct04-settings.xml`, `ct04-settings-permissions.xml`, `ct04-settings-return.xml`, `ct04-granted.png`, `ct04-granted-return.xml`.
