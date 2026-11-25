# Chai And Tapri OS — Progress Addendum

**Updated:** 26 November 2026

This addendum records current verification evidence for the six-wave execution plan. It does not replace the complete module/screen inventory in `MASTER-IMPLEMENTATION-TRACKER.md` or imply release readiness.

## CAT-051B — process-death recovery

Two current-source API 37 instrumentation scenarios now kill a separate Android app process during restore cutover and verify a fresh process recovers the encrypted store. Death before replacement activation retains the original; death after activation but before the commit marker rolls back to the original and retains the interrupted replacement. Both focused tests passed 1/1. A test-only Messenger completion handshake was added after the first attempt exposed an unbind race. Kotlin JVM 98/98, debug/test APK build tasks passed, lint 0 errors / 191 warnings. See [CAT-051B crash-recovery report](CAT-051B-REAL-PROCESS-CRASH-RECOVERY-REPORT-2026-11-26.md).

The most recent broad connected Android attempt did not finish: it was interrupted during a long customer-credit UI scenario after the emulator became unresponsive, then ADB reported a broken Package Manager pipe. A subsequent focused recovery run initially failed because the test harness unbound before the recovery callback; this was corrected, and both focused recovery checks then passed. The previously documented 151/151 suite remains a historical result for its earlier source and is not presented as current-source full qualification.

## Follow-up exact-source Android regression attempt — 26 November 2026

Reran the serial-pinned API 37 suite using the booted isolated AVD. The run ended after 17m02s when ADB lost the emulator during cleanup. Its captured partial JUnit result is **32 tests: 18 passed, 14 failed, 0 errors, 0 skipped**. Multiple failures show `Pixel Launcher isn't responding` and activity launch timeouts; the remainder were UI assertions after the emulator had become unresponsive. This is a failed/incomplete suite attempt, not a product qualification. XML/HTML evidence and follow-up are in [the emulator-loss report](ANDROID-REGRESSION-ATTEMPT-2026-11-26-EMULATOR-LOSS.md) and ignored evidence at `../test-evidence/2026-11-26/full-regression-emulator-failure/`. The last full green 151/151 remains historical and is not current-source evidence.

## CAT-052 backend integration follow-up — 26 November 2026

The current backend suite was run against a new disposable PostgreSQL 17 instance with migrations 001–004, runtime grants, and the restricted application role. All **39/39** tests passed with **0 skipped**. Test output: `../test-evidence/2026-11-26/backend-postgres-39-of-39.log`; setup and limits: [CAT-052 report](CAT-052-POSTGRES-CURRENT-SUITE-REPORT-2026-11-26.md). The disposable container is stopped. This does not close CAT-052 production, full financial-ledger, multi-device conflict, or operational-security gaps.

## CT-28 closed cash-session CSV export — 26 November 2026

The Android Daily Closing screen now offers a privacy-minimized SAF CSV for a selected closed drawer session. The report includes integer-paise entries, device-clock times, expected and physically counted cash, and unresolved variance. It excludes names, actor IDs, handover notes, customer data and free-text reasons. Kotlin/JVM 100/100 and APK/lint tasks passed (0 lint errors / 196 warnings). The instrumented visibility assertion was compiled but not run; actual picker save/cancel/read-back remains pending because the API 37 AVD was lost during the full-suite attempt. Details: [CT-28 export report](CT-28-CASH-SESSION-CSV-EXPORT-REPORT-2026-11-26.md).

## Six-wave status

| Wave | Current state | Remaining qualification |
| --- | --- | --- |
| 1 — CAT-051B recovery/security | Encrypted SAF recovery and two real app-process cutover recovery cases have emulator evidence. | Physical power/storage/key-loss, second device/person, OEM transfer and independent security review. |
| 2 — Android CT-01–CT-32 | Connected synthetic workflows exist across counter, stock, procurement, customer credit, expenses, shifts, closing, reports and settings. CT-05 first-sale routing was just simplified. | Screens and domains remain partial; full current-source suite, daily accounting, broad permissions and real shop qualification remain. |
| 3 — CAT-052 backend/sync | Authenticated event API, encrypted outbox, WorkManager retry and selected PostgreSQL-backed E2Es, including app-process restart, are implemented. | Production service/deployment, complete domain contracts, multi-device financial conflict resolution and operational security. |
| 4 — Owner dashboard | Partial owner views, conflict review, deterministic insights and current-view CSV export use accepted server projections. | OW-11/remaining administration and configuration work, visual review, complete accounting and production operations. |
| 5 — selling modes | Quick Sale, reviewed single-utterance voice, barcode/QR validation and explicit reconciliation estimates have connected prototype paths. | Genuine continuous Bluetooth, physical microphone/camera qualification, noisy-counter trials and full operational close. |
| 6 — release qualification | Not complete. | Independent security review, performance, field, deployment, support and release process. |

Current feature evidence is summarized in the [master implementation tracker](MASTER-IMPLEMENTATION-TRACKER.md). Browser visual authorization, physical hardware and production deployment have not been bypassed or claimed.
