# Wave 6 — GitHub CI baseline

**Date:** 26 November 2026  
**Repository:** `vaishnavsawant1994-stack/Chai-And-Tapri`  
**Status:** CI workflow implemented and GitHub-hosted run passed.

## Delivered

Added `.github/workflows/ci.yml`, triggered for pushes and pull requests targeting `main`, plus manual dispatch. It defines three independent jobs:

- Browser prototype Node tests.
- Backend Node and PostgreSQL integration tests against a disposable PostgreSQL 17 service. Migrations and repository runtime grants are applied, and application requests use a non-superuser runtime login.
- Android Kotlin/JVM tests, debug APK and instrumentation APK assembly, lint, and artifact upload. The CI build uses compile SDK 36 because GitHub's SDK index did not provide the project's API 37 package during initial runs; local/default builds remain on API 37. This job does not start an emulator or run instrumentation tests.

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

Hosted run **45743870636** passed all three jobs for commit `c7a583b9110745682ffc9c9da88e7b3433efdb74`: prototype, backend/PostgreSQL, and Android JVM/build/lint. Run: [GitHub Actions](https://github.com/vaishnavsawant1994-stack/Chai-And-Tapri/actions/runs/45743870636). The backend job reports 44/44 and the prototype job 52/52. The Android job completed `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` successfully against compile SDK 36. The exact hosted Kotlin test count was not included in the console summary; the local API 37 report records 106 JVM tests.

GitHub hosted Android setup required iterative fixes: an incorrect Gradle action path, the retired SDK `tools` package, API 37 being absent from the runner package index, and the wrapper's missing executable bit. These were corrected. The workflow now uses the supported Gradle action, requests only `platform-tools`, compiles against API 36, and the wrapper is executable. The local SDK installation has API 35 and API 37 but no standalone `sdkmanager`, so the CI-only API 36 variant could not be run locally. Default API 37 local builds have passed. Local Android instrumentation on the API 37 emulator is not part of this CI workflow because emulator UI suites are long-running and have experienced host/emulator instability. The existing focused/full emulator evidence remains separately documented. No physical Bluetooth microphone, noisy-shop, independent security review, deployment, or release qualification is claimed.

Next: add a manually dispatched emulator workflow only after measuring hosted emulator stability and runtime. Keep physical microphone, field, independent-review, deployment and release gates separate.
