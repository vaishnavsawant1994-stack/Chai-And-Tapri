# CAT-052 WorkManager process-restart and sync acknowledgement qualification

**Date:** 26 November 2026  
**Result:** Two-phase synthetic API 37 process-restart E2E passed; backend PostgreSQL suite passed.

## What was exercised

A loopback-only PostgreSQL 17 database and HTTPS fixture were created from the existing repository test server and test certificate. The target AVD was `emulator-5554` / `Tapri_Recovery_Isolated`. An explicit opt-in `LiveBackgroundSyncProcessRestartE2ETest.scheduledWorkerResumesAfterAppProcessRestart` prepare phase registered a synthetic owner, persisted a Keystore-protected session, created opening stock and one cash sale, queued two outbox events, and enqueued a delayed connected-network WorkManager request. Its checkpoint recorded app PID 27231 and WorkManager ID `5ecc0c72-0ba6-434b-95d1-2bffdff272ce`.

After the preparation test returned, the host explicitly ran `adb shell am force-stop com.chaiandtapri.counter`. The checkpoint remained present. The resume phase then asserted the new PID differs from the prepared PID, loaded the encrypted session, found the same WorkManager request, and waited for it to complete. It passed and verified: two acknowledgements at server revision 2, empty local pending outbox, persisted local cursor 2, one 3,000-paise cash order, stock 7 from 8, and matching authenticated owner-summary totals.

## Results

- PostgreSQL-backed backend tests: **39/39 passed**, 0 failed, 0 skipped, against migrations 001–004 with a separate administrator test connection and restricted runtime login.
- Direct Android prepare phase: **OK (1 test)**.
- Explicit process force-stop: completed; local checkpoint survived.
- Direct Android resume phase: **OK (1 test)**, 32.626 seconds.
- Prior default Android instrumentation full regression: **151/151 passed** (separate broad suite, current source).
- Kotlin JVM: **98/98**; prototype Node: **52/52**; Android APK build and lint previously passed for current source (0 lint errors / 191 warnings).

The direct `am instrument` phases do not generate Gradle JUnit XML. Exact commands, observed outputs, checkpoint ID/PID, and cleanup are recorded in [`test-evidence/2026-11-26/cat052-process-restart/qualification.txt`](../test-evidence/2026-11-26/cat052-process-restart/qualification.txt). The HTTPS server log and PostgreSQL startup log are in the same ignored evidence directory.

## Cleanup and limits

The test server ran only on loopback with a temporary ADB reverse mapping. The dedicated database and runtime login were dropped, the mapping removed, the HTTPS process stopped, and the PostgreSQL cluster stopped. Synthetic test values only.

This materially qualifies the selected offline-to-online scenario across a real app process stop/restart on the emulator. It is not production synchronization, multi-device conflict resolution, physical device/field qualification, independent security review, or deployment qualification. The backend continues to store validated event journals and derive the documented projections; broader production accounting and operational controls remain open.
