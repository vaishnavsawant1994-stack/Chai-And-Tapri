# CAT-052 Android release-variant build check

**Date:** 24 November 2026  
**Result:** Release variant packaged; artifact is unsigned and not deployment-qualified.

## Evidence

- `android/gradlew.bat assembleRelease --console=plain` completed with `BUILD SUCCESSFUL`.
- Output: `android/app/build/outputs/apk/release/app-release-unsigned.apk` (47,604,317 bytes in this workspace).
- Android manifest package: `com.chaiandtapri.counter`.
- The packaged application manifest has no `android:debuggable` attribute; Android defaults this to false. The debuggable-only synthetic database override used by instrumentation is therefore not enabled in this variant.
- The release APK is unsigned. No signing key was created or used, and the artifact was not installed, distributed, or deployed.

## Qualification limits

This verifies release-variant compilation and packaging only. It does not qualify signing, Play policy, production secrets or endpoints, device behavior, security review, store submission, or deployment. The debug and emulator test evidence remains separately reported in the CAT-052 staff invitation E2E report.

## Next executable work

Continue the six-wave product plan from the current tracker. Do not treat the unsigned package as a release candidate. For a clean-install restore drill, use a dedicated disposable emulator profile or an isolated synthetic database; the existing default-database UI test deletes and replaces its database and must not be run against a workspace containing user business records.
