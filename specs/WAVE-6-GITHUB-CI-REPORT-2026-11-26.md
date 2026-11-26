# Wave 6 — GitHub CI baseline

**Date:** 26 November 2026  
**Repository:** `vaishnavsawant1994-stack/Chai-And-Tapri`  
**Status:** CI workflow implemented; local-equivalent checks passed; GitHub-hosted run pending push.

## Delivered

Added `.github/workflows/ci.yml`, triggered for pushes and pull requests targeting `main`, plus manual dispatch. It defines three independent jobs:

- Browser prototype Node tests.
- Backend Node and PostgreSQL integration tests against a disposable PostgreSQL 17 service. Migrations and repository runtime grants are applied, and application requests use a non-superuser runtime login.
- Android Kotlin/JVM tests, debug APK and instrumentation APK assembly, lint, and artifact upload. This job does not start an emulator or run instrumentation tests.

Permissions are read-only for repository contents. The workflow uploads only generated unsigned debug/test APKs and diagnostic reports. It does not deploy, publish, access production data, or contact an external sync service.

## Local verification

| Check | Result |
|---|---:|
| Prototype tests | 52 passed, 0 failed, 0 skipped |
| Backend tests, isolated PostgreSQL 17 | 44 passed, 0 failed, 0 skipped |
| Kotlin/JVM tests | 106 passed, 0 failed, 0 skipped |
| Android debug APK | PASS |
| Android instrumentation APK assembly | PASS |
| Android lint | 0 errors, 201 warnings |
| Workflow YAML parse | PASS |

The PostgreSQL run used a new `chai-tapri-ci-local-20261126` container on loopback port 54329, with migrations 001–004 and `runtime-grants.sql`. It used a restricted `tapri_ci_runtime` role. The container was stopped and removed after the run; the unrelated pre-existing `perspective-v11-route-repro-20261124` container was not touched.

Android task command: `gradlew.bat --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` from `android/`.

## Qualification limits and follow-up

The workflow has not yet run on GitHub; its first hosted result must be checked after this change is pushed. Local Android instrumentation on the API 37 emulator is not part of this CI workflow because emulator UI suites are long-running and have experienced host/emulator instability. The existing focused/full emulator evidence remains separately documented. No physical Bluetooth microphone, noisy-shop, independent security review, deployment, or release qualification is claimed.

Next: push to `vaishnavsawant1994-stack/Chai-And-Tapri`, inspect the hosted Actions run, and fix any runner-specific failures. Then decide whether to add a manually dispatched emulator workflow after measuring hosted emulator stability and runtime.
