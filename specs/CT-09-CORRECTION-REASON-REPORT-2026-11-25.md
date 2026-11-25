# CT-09 Correction Reason Capture — 25 November 2026

## Delivered

Order Details now requires an authenticated manager/owner to enter a correction reason before removing an unhanded quantity. The validated `remove` command rejects a missing, too-short, too-long, or control-character-containing reason before changing order state. The accepted correction event records the reason with the product and quantity in the existing audit/outbox path.

CT-07's reviewed voice-removal proposal uses the same requirement. Applying a removal opens a reason prompt; the pending command ID and proposal are checked again before the reason-bearing command is committed. If the proposal changed or disappeared, the correction is not applied. The operator can cancel the prompt and leave the order unchanged.

This reason records the operator's explanation. It does not prove that the explanation is true, and it does not authorize removing already handed-over goods or alter payment, refund, or stock history.

## Verification

- Kotlin JVM: **83/83** passed, 0 failures/errors.
- API 37 connected Android instrumentation: **140/140** passed, 0 failures/errors/skips; includes the Order Details correction-reason UI path and the reviewed CT-07 voice-removal path.
- Debug APK and Android-test APK build tasks: passed.
- `lintDebug`: 0 errors, 187 warnings.
- Full connected instrumentation result: [`ct09-correction-reason-full-instrumentation.xml`](../test-evidence/2026-11-25/ct09-correction-reason-full-instrumentation.xml).
- Node tests were not rerun for this Android-only change.

## Remaining scope

CT-09 remains partial. This slice does not implement price/discount edits, correction of handed-over quantities, a complete refund/cancellation interface, trusted server time, or end-to-end authorization review. Emulator results do not establish physical-device or shop-floor usability. CAT-051B independent security review, cross-device recovery, Bluetooth headset qualification, real-shop trials, deployment, and production readiness remain open.
