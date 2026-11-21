# CT-08/09/10 Live Orders and Payment Actions — Implementation Report

**Date:** 29 October 2026; current continuation 22 November 2026
**Status:** Partial connected screen slice; the CT-08 partial item handover action now has API 37 UI automation coverage.

## Delivered

- Added a native Live Orders activity reading persisted orders from the encrypted transaction model.
- Each order card shows number, items, sale amount, fulfillment, payment state, and remaining due.
- Cards expose valid actions according to order state: start preparation, mark ready, hand over remaining items, record staff-declared cash, mark UPI pending, or record Udhar against a typed existing account ID.
- Every mutation calls the shared model with the order's expected revision and a unique command ID. Orders and payment states remain distinct. UPI is never shown as verified.
- CT-08 now provides local order-number/item/status search and state filters (all, needs preparation/handover, ready to hand over, payment due, UPI pending); these only change visible cards. It also provides a selected-item handover dialog for ready orders. It bounds the entered quantity to that line's remaining quantity and submits the existing revision-checked `partial_given` command. Confirmed stock is decremented only for the handed-over quantity. The order remains `PARTIALLY_GIVEN` and `salePosted` remains false until all lines are delivered.
- Added Home navigation and an explicit empty-orders state. The API 37 emulator opened and rendered that state.

## Verification

- Focused API 37 UI tests `liveOrdersCanRecordAValidatedPartialItemHandoverFromTheScreen` and `liveOrdersSearchAndStatusFilterOnlyChangeVisibleCards`: 1/1 each passed; verifies the screen action, delivered quantity, partial fulfillment state, no completed-sale posting, one stock deduction, and one movement record.
- Current full run: Node 52/52, Kotlin JVM 63/63, API 37 instrumentation 103/103 (0 failures, errors, or skips). The optional live-backend-only Android test class was excluded; its current CAT-052 scope is documented separately.
- `testDebugUnitTest`, debug and instrumentation APK builds, and `lintDebug`: PASS. Lint: 0 errors, 172 warnings.
- Android JUnit XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (local generated output).
- Screenshot and UI hierarchy: `test-evidence/2026-10-29/ct08-live-orders-empty.png` and `.xml`.

## Gaps

The order cards are not yet a separate detail timeline. Multi-line partial handover in one action, manual usability review, and remaining staff/payment-provider qualifications remain. The partial-handover test covers one synthetic item/order interaction; this is not complete CT-08, CT-09 or CT-10 qualification.
