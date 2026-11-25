# Android instrumentation attempt — 26 November 2026

## Result

**Incomplete / failed.** The serial-pinned current-source run started on API 37 emulator `emulator-5554` and terminated after 17m02s when ADB lost the device during test cleanup. The generated JUnit artifact reports 32 tests, 18 passed, 14 failed, 0 errors, and 0 skipped. It is a partial run, not a complete suite result.

The failures are not treated as application qualification failures without further diagnosis. Multiple UI assertions found the Android system dialog `Pixel Launcher isn't responding`; other failures timed out while launching activities (including MainActivity and CloudSyncActivity) after the same emulator stalls. The emulator was subsequently absent from ADB. The exact UI/test failures are retained in the JUnit report.

## Evidence

- `test-evidence/2026-11-26/full-regression-emulator-failure/instrumentation-partial.xml`
- `test-evidence/2026-11-26/full-regression-emulator-failure/index.html`
- Gradle command: `android/scripts/run-isolated-connected-tests.cmd emulator-5554`

The run used only the isolated API 37 AVD. No production or real-shop data was used. No app, device, security, browser-authorization, or production setting was changed to mask the emulator failure.

## Next action

Re-establish a stable emulator session, check AVD/host health and free resources, then run focused tests for changed workflows before another full suite. A full Android regression must finish and produce a current complete JUnit result before it can be reported as passed. Existing separately focused CAT-051B recovery evidence remains valid for its two tested crash checkpoints; this attempt does not supersede it.
