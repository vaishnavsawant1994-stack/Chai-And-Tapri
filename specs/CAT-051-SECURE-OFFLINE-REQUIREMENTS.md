# CAT-051 — Secure Offline Storage and Recovery

**Status:** Partial implementation. CAT-051 and CAT-051B are not complete or production-qualified. See [CAT-051B implementation report](CAT-051B-IMPLEMENTATION-REPORT.md) for the current run and acceptance gaps.

## Implemented foundation

- SQLCipher for Android 4.20.0 encrypts native database files using a random 256-bit passphrase. Database open/load/commit paths check `PRAGMA cipher_version`; emulator instrumentation confirms platform SQLite cannot query the SQLCipher database.
- Android Keystore holds a non-exportable AES-GCM wrapping key. A wrapped database passphrase envelope is stored in app-private preferences. Missing/inaccessible key material fails closed and does not create an empty replacement database.
- Legacy plaintext v1-v6 database migration copies intersecting recognized tables into an encrypted schema-v6 database, verifies table row counts and integrity, and preserves/restores the source database family if migration fails. Unknown tables stop migration.
- Portable snapshots use a random salt and backup ID, PBKDF2-HMAC-SHA256 (310,000 iterations) to derive a separate key, SQLCipher authenticated database pages, and HMAC-SHA256 authentication of the container header. Format v3 includes the generated schema-v6 shop UUID in the authenticated header and checks it against the encrypted payload and an active shop with records; format v2 remains a legacy unbound format. Restore validates before staging, requires explicit replacement confirmation, rejects a repeated snapshot ID on the installation, and retains the previous encrypted database until cutover verification.
- Android Auto Backup, cloud backup and device transfer are disabled/excluded for databases, shared preferences, files and external files.
- CAT-051B adds SAF `CreateDocument`/`OpenDocument` launchers, recovery-secret confirmation and clearing, metadata preflight, explicit replacement confirmation, restore progress/result states, write maintenance UI, an exclusive process-wide barrier, staged recovery with a durable cutover journal, and read-only business snapshot reconciliation.

## Security and recovery boundaries

- Device-local Keystore keys are device/app-install bound and are not portable. The separately held recovery secret is required to open a portable export on a replacement installation; it must not be stored alongside the snapshot.
- Legacy plaintext migration necessarily creates a temporary internal plaintext copy. Cleanup removes the file family after verification, but file deletion is not a guaranteed forensic erase on flash storage.
- The process-wide barrier covers current app SQLite reads/commits and the UI blocks current counter interactions during maintenance. There are no sync workers in this prototype. Future background mutation paths must acquire the same barrier before they are added.
- Snapshot format v3 authenticates the encrypted local database schema version (currently 17) and is limited to 512 MiB; format v2 remains accepted as legacy only where restore target policy allows. The same-emulator, SAF-driven uninstall/reinstall recovery drill with synthetic records passed on 30 October 2026; this does not qualify a second profile/device.
- Keystore invalidation, low-storage behavior, process-kill at each migration/cutover point, and restoration after app reinstall require additional tests.

## Required remaining acceptance work

- Test interruption at each migration/cutover step, insufficient storage, and each supported historical schema with representative records.
- Test wrong/missing/invalidated Keystore keys, backup tampering/header changes, wrong recovery secret, truncated/oversized backups, duplicate imports, and failed replacement while proving active records remain unchanged.
- Run a clean-install restore on a separate emulator profile/device; have a second person follow the recovery instructions.
- User-facing SAF export and clean-install restore passed with synthetic records on the same emulator. Cross-profile/device recovery and staff/owner role authorization remain unimplemented/unqualified.
- Perform independent security review, key-loss/reinstall review, retention/logging review, and production threat assessment before real shop data.

## Acceptance status

Synthetic emulator checks now cover encrypted creation, missing Keystore-key fail-closed behavior, corrupted legacy-source preservation, v1-to-v6 migration, restart/idempotency, atomic rollback, financial ledger persistence, portable snapshot export/restore, wrong recovery secret, ciphertext tampering, header tampering, explicit replacement confirmation, and duplicate-import rejection. See `CAT-051-IMPLEMENTATION-REPORT.md` for exact run results and evidence.

This is a security foundation, not a claim of production-ready protection or recovery.

### Superseding key-loss recovery check - 29 October 2026

The export path now stores a non-secret shop UUID binding in app-private SharedPreferences excluded from Android backup. The binding is refreshed only after the encrypted active database is readable and its identity has been obtained during export. Recovery over an unreadable database requires a v3 authenticated container identity, matching decrypted payload identity and matching local binding. It journals the old/new wrapped key envelopes, stages under a fresh Android Keystore key, preserves prior ciphertext as an archive after a verified commit, and rolls back to the old key envelope on uncommitted startup recovery. This UUID binding is a same-install recovery guard, not proof of business ownership or user authorization.

API 37 instrumentation now passes a synthetic key-loss recovery and mismatched-identity preservation scenario. It also injects a partial staging write that throws an ENOSPC-like exception. Latest run: Node 48/48, Kotlin JVM 28/28, Android instrumentation 23/23, APK builds pass, lint 0 errors / 77 warnings. See [CAT-051B implementation report](CAT-051B-IMPLEMENTATION-REPORT.md) for logs and limitations.

CAT-051B remains open: no real storage exhaustion, hard process kill/power loss, hardware-backed key invalidation, distinct-device recovery, second-person drill, or independent security audit was established by this run.


## CAT-051B qualification status (29 October 2026)

The recovery UI and maintenance/recovery implementation are present. Current tests: Node 48/48, Kotlin JVM 23/23, API 37 emulator instrumentation 9/9; APK builds PASS; Android lint 0 errors/45 warnings. Instrumentation covers barrier rejection/release, snapshot reconciliation, export output failure cleanup, wrong recovery secret, payload/header tampering, explicit confirmation, duplicate import, recovery rollback, fault-injected interrupted cutover recovery, and shop-identity mismatch rejection. Actual SAF export and same-emulator clean-install restore of synthetic records also passed through the Android UI.

The acceptance gate remains **open**. The actual SAF picker has not been used to create a user-selected external file and restore it through the app UI. Schema v6 stores a generated shop UUID; backup container format v3 authenticates it, verifies it against the encrypted payload, and rejects a mismatch with an active shop that has business activity. The separate-store test uses a different database filename and Keystore namespace in the same app sandbox, not a clean app installation or another device. There is no second-person drill. Actual low-storage exhaustion, hard process termination, Keystore invalidation, and all cutover interruption points are not fully qualified; injected output failure is not equivalent to exhausted device storage. No sync worker exists yet.

Do not claim cross-device recovery, independent security review, or production readiness. The clean-install drill was performed by uninstalling/reinstalling on the same emulator; a second profile/device remains untested. Restrict testing to synthetic records until remaining gates and an independent review are complete. Full results and evidence paths are in [CAT-051B report](CAT-051B-IMPLEMENTATION-REPORT.md).


## Final CAT-051B continuation verification — 29 October 2026

The restore completion path also reopens SQLCipher off the UI thread and closes any newly opened store if the activity is destroyed. After this final lifecycle change, the complete verification command `.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest lintDebug --console=plain` completed successfully: Kotlin JVM 28/28, Android API 37 instrumentation 23/23, APK builds PASS, lint 0 errors / 77 warnings. `node --test prototype/*.test.cjs` passed 48/48. The API 37 suite ran after the final Android source changes. Evidence: `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`, `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, `android/app/build/reports/lint-results-debug.txt`, and `test-evidence/2026-10-29/cat051b-key-loss-recovery-run.txt`.

CAT-051B remains open; this run did not perform a new SAF export/restore UI drill. Actual storage exhaustion, abrupt process/power loss across journal transitions, hardware Keystore invalidation, second-device and second-person recovery, and independent security review remain pending. Do not treat the prototype as production-ready.

## Current CT-10/CT-22 verification — 30 October 2026

Node 49/49; Kotlin JVM 31/31; API 37 emulator instrumentation 38/38; debug and instrumentation APK tasks PASS; lint 0 errors / 107 warnings. CT-10 partial UPI staff-declaration confirmation UI and CT-22 Customer Directory → Customer Details are included. The synthetic SAF export and same-emulator clean-install restore functional gate is supported by retained artifacts and hash verification. Remaining security and product scope is substantial: cross-device/key-loss scenarios, independent security review, most Android workflows, authenticated sync, owner dashboard, continuous Bluetooth, real device/shop trials and production release qualification.

## Superseding local customer profile storage — schema v17

Schema v17 adds an encrypted `customer_profiles` table with an additive v16→v17 upgrade. The profile directory supports local name/account-ID search. Optional phone storage requires an explicit consent checkbox, is masked in the directory and details view, and is excluded from audit event text. Profile writes participate in the maintenance write barrier and atomically append a command receipt, audit record, and local outbox entry. Backup/restore tests verify profile persistence without changing order, credit or payment records. Consent tracking is an operator-recorded assertion; profile edit/delete, consent revocation/retention, external contact use and second-device recovery are not qualified. Latest complete run: Node 49/49, Kotlin JVM 31/31, API 37 instrumentation 42/42, APK tasks PASS, lint 0 errors / 108 warnings.
