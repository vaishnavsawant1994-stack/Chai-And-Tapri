# CAT-052 WorkManager recovery after Android app-process restart

**Date:** 25 November 2026  
**Result:** Two-phase synthetic API 37 emulator check passed using the installed-app instrumentation runner.

## Procedure and result

The test `LiveBackgroundSyncProcessRestartE2ETest.scheduledWorkerResumesAfterAppProcessRestart` was run in two separate instrumentation invocations against an isolated PostgreSQL 17 service over loopback HTTPS. Phase `prepare` registered a synthetic owner, wrote opening stock and a ₹30.00 staff-declared cash sale to the encrypted local store/outbox, and scheduled a 60-second WorkManager request. The checkpoint file and work ID were confirmed in the installed app's private `no_backup` directory. The app process was force-stopped. Phase `resume` ran in a different process and verified the same checkpoint/work ID, session, queued request and server endpoint; WorkManager then resumed the exact request.

Assertions in the resume phase verified successful work, two acknowledged events at revision 2, one ₹30.00 order, stock 8→7, empty outbox, local cursor 2, and the owner overview's two accepted events / one order / ₹30.00 declared cash. The test logged prepared PID `12112`, resumed PID `12179`, and the same work ID `9d6a9d63-c728-4ea0-9655-7b6e6ae8f5`; the worker completed successfully after restart.

Both phase invocations returned `OK (1 test)`. Current-source logcat evidence is `test-evidence/2026-11-25/cat052-worker-process-restart-logcat.txt`.

## Harness note

An initial `connectedDebugAndroidTest` prepare phase passed but the Gradle connected-test task removed the app packages after completion, so its private checkpoint could not be used in a second Gradle invocation. That attempt is not counted as a process-restart qualification. For the passing drill, Gradle installed the debug and instrumentation APKs once; `adb shell am instrument` ran the two phases directly while the packages remained installed, with an explicit `adb shell am force-stop` between phases. This preserved app-private WorkManager state and provided a real process-ID change.

The PostgreSQL cluster was disposable, freshly created for synthetic tests and stopped after verification; the test HTTPS server was stopped and the temporary ADB reverse mapping removed. No production endpoint or real business data was used.

## Limits

This verifies one delayed WorkManager request resuming after an app-process force-stop on an API 37 emulator. It does not establish survival across device reboot, power loss, app upgrade, OS job cancellation, OEM battery management, production backend outage, or multi-device conflict/recovery. It is not physical-device or production qualification. The normal Gradle task cleanup behavior means this two-phase case must continue to use the documented direct instrumentation procedure unless that harness is redesigned.
