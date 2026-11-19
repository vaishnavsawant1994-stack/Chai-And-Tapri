# CAT-051 - Secure Offline Storage and Recovery Implementation Report

**Date:** 29 October 2026  
**Status:** CAT-051A foundation implemented for selected synthetic scenarios. CAT-051B extends recovery UI and safeguards but remains incomplete; see [CAT-051B report](CAT-051B-IMPLEMENTATION-REPORT.md).

## Foundation implemented

- SQLCipher for Android 4.20.0 encrypts the native database. A random 32-byte database key is wrapped by a non-exportable Android Keystore AES-GCM key. Missing or inaccessible key material fails closed instead of creating an empty store.
- Plaintext legacy migration snapshots the main database and WAL/SHM/journal family, verifies recognized schema, row counts and integrity, and preserves/restores originals on migration failure. Unknown tables fail closed.
- Portable snapshots derive a separate key from a recovery secret using PBKDF2-HMAC-SHA256 (310,000 rounds), encrypt database pages with SQLCipher, authenticate header metadata, stage and verify restore, require explicit replacement, and prevent same-install replay. Payload size is capped at 512 MiB.
- Android Auto Backup, cloud backup and device transfer are disabled or excluded for sensitive app data.
- CAT-051B adds SAF launchers, secret-entry controls, a process-wide write barrier, snapshot reconciliation, staged replacement and a persistent cutover recovery journal. Details and evidence are in the CAT-051B report.

## Latest verification

| Check | Result | Evidence |
|---|---|---|
| Node | PASS, 48/48 | `node --test prototype/*.test.cjs` |
| Kotlin JVM | PASS, 23/23 | `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml` |
| Android instrumentation | PASS, 9/9, zero failures | `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` |
| Debug APK and instrumentation APK | PASS | `android/app/build/outputs/apk/` |
| Android lint | PASS, zero errors / 46 warnings | `android/app/build/reports/lint-results-debug.txt` |
| Encryption check | PASS for tested emulator path | Instrumentation confirms platform SQLite cannot query encrypted data; SQLCipher `cipher_version` and `integrity_check` checked |

Full latest test run: `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest --console=plain` from `android/`.

## Not qualified

- No real shop data, production deployment or external upload was used.
- No complete user-driven SAF export and restore, clean-install restore, separate emulator profile/device, cross-device recovery, or second-person recovery drill has been completed. The isolated-store instrumentation case uses a separate database/key namespace in the same app sandbox.
- Actual storage exhaustion, hard process kill/power loss through all migration/cutover stages, and hardware-backed Keystore invalidation remain untested; selected failures use injection.
- A generated UUID is authenticated in backup format v3 and mismatch against an active shop with records is rejected. It is not yet a user-managed business identity or staff authorization system.
- Legacy migration may briefly create plaintext internal copies; deletion cannot guarantee forensic erasure on flash.
- Compromised/rooted devices, process memory, OS-level capture, production key lifecycle, independent security review and production readiness are outside current qualification.
- Backup format supports schema v6 and a 512 MiB payload limit; future schema versions need explicit compatibility work.

CAT-051 remains open. CAT-051B acceptance gaps, security self-review, reproduction steps and next executable tasks are recorded in [CAT-051B report](CAT-051B-IMPLEMENTATION-REPORT.md).
