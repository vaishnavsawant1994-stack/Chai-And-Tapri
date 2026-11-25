# CT-09 Cancel Unhanded Remainder — 25 November 2026

## Delivered

Order Details now exposes **Cancel unhanded remainder** to managers/owners for TAKEN, PREPARING, READY, and PARTIALLY_GIVEN orders. The dialog explains that already handed-over goods remain sold, their payment/credit and stock history is retained, and this action issues no refund. It collects a 3–120 character reason, requires a second confirmation, and submits the current order revision through the existing cancellation transaction. A role check is repeated at confirmation; the persistence mutation policy remains the authorization boundary.

The model rejects blank, short, overlong, or control-character reasons before mutation. Revision conflicts are surfaced without applying the cancellation. For partial handovers, cancellation changes only the remaining fulfillment state; it does not reverse the previously delivered sale, payment entries, or inventory movements. Refunds remain a separate CT-12 action.

## Verification

- Kotlin JVM: **84/84** passed, 0 failures/errors.
- API 37 emulator: focused UI instrumentation **1/1** passed, 0 failures/errors/skips. It starts an order, partially hands over one unit, cancels the remainder through the actual Order Details dialogs, and checks persisted fulfillment, revision, reason, sale value, stock movements, payment entries, and actor-attributed audit.
- Debug APK and Android-test APK build tasks: passed.
- `lintDebug`: 0 errors, 187 warnings.
- [Focused instrumentation XML](../test-evidence/2026-11-25/ct09-cancel-remainder-focused-instrumentation.xml).
- The full 140-case API 37 suite was not rerun after this slice. Node tests were not rerun; this change affects Android code only.

## Remaining scope

CT-09 remains partial. This does not implement price/discount edits, cancellation of a fully handed-over order (use refund workflow), or automatic refunds/stock reversals. Physical-device, shop-floor, independent security, cross-device recovery, deployment, and production qualification remain open.
