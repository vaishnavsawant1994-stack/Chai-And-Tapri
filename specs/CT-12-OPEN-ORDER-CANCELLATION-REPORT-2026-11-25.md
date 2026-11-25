# CT-12 Open-Order Cancellation UI Qualification

**Date:** 25 November 2026  
**Scope:** One synthetic cancellation path on Android API 37 emulator  
**Result:** Focused UI test passed; CT-12 remains partial.

## What was verified

The API 37 test `refundCorrectionScreenRequiresReasonAndConfirmsOpenOrderCancellation` creates a synthetic, unfulfilled order in encrypted local storage and launches the actual CT-12 Refund & Correction screen for that order.

The test exercises this sequence:

1. Open the order's cancellation dialog.
2. Attempt to review with the reason blank; the reason form remains open.
3. Enter `Customer cancelled before preparation`.
4. Review the cancellation and accept the second confirmation.
5. Reopen the persisted snapshot and audit records.

Assertions verify the order is `CANCELLED`, the reason is retained, delivered quantity is zero, and there is no payment/refund event or stock movement for this unfulfilled order. The append-only audit record contains the cancellation event and reason.

## Verification results

- Focused API 37 instrumentation: **1/1 passed**.
- Kotlin JVM tests: **96/96 passed**, freshly run with `testDebugUnitTest --rerun-tasks`.
- Debug APK and instrumentation APK assembly: **passed**.
- `lintDebug`: **0 errors, 189 warnings**.
- JUnit XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (generated build output; it can be overwritten by later connected runs).

The first test attempt failed at an assertion that assumed a command-ID prefix not used by this UI path. The test was corrected to inspect the persisted domain audit event and passed on rerun; no application transaction logic was changed.

## Limits and remaining qualification

This test covers one reasoned open-order cancellation on a synthetic emulator account. It does not establish the staff role/permission matrix, partial-handover cancellation, cancellation with payment states, interrupted database writes, physical-device behavior, manual usability, or full Android suite status. CT-12, CAT-051 recovery/security review, physical Bluetooth, shop trials, authenticated production synchronization, deployment, and the overall product remain incomplete.
