# CT-12 Refund & Correction — Implementation Report

**Date:** 30 October 2026  
**Status:** Partial native Android screen; uses the existing audited transaction engine. Screen rendering and one complete synthetic cash item-refund flow were qualified on the API 37 emulator. Other dialog and allocation paths remain pending.

## Implemented

- Added CT-12 `RefundCorrectionActivity`, reachable from Counter and each Sales History order.
- Lists the selected order (when launched from history) or local orders with fulfillment/payment state, revision, delivered sale, collected amount, Udhar, refunds and due.
- Offers reason-required, two-step cancellation for open, not-yet-handed-over orders. Handed-over orders are directed to the refund path and engine revision checks are supplied.
- Offers item returns only for delivered, unrefunded line quantities, and amount corrections only for eligible delivered orders. Quantities and INR decimal input are validated before review; money is converted exactly to integer paise.
- A separate confirmation dialog states the amount basis, allocation behavior, pending-UPI limitation, stock treatment and cash-declaration status before committing.
- Item returns default to **not restocked** because the prototype has no persisted per-product returnability policy. No UI option falsely promises restocking. Amount-only corrections do not change inventory.
- Staff cash refund confirmation is explicit and unchecked by default. All successful changes route through `CounterModel` with the displayed order revision and a fresh idempotency command ID; payment/credit/stock/audit reversals remain engine-owned.
- Database reads and writes use the serial recovery executor; failures display that no change was made and the screen reloads from persisted state.

## Verification

- Android API 37 instrumentation screen test: `refundCorrectionScreenShowsAuditedItemAndAmountPathsForEligibleOrder` passed. It creates an explicitly priced synthetic product and confirmed opening stock, launches CT-12, and checks eligible order data plus the item-return, amount-correction and staff-declared disclosures.
- Manually exercised the item-return dialog on API 37 against synthetic order #14. Entered a reason, explicitly checked the staff-declared cash payout, reviewed the ₹15 refund, and committed. Sales History showed paid ₹0, refunded ₹15, a `CASH -₹15.00 · staff_declared_refund · cash_refund` event, and revision 2. Inventory remained at 98 Regular Chai; no restock movement was posted. Screenshots and UI hierarchies: `test-evidence/2026-10-30/ct12-refund-committed.png`, `ct12-postrefund-history.png`, `ct12-postrefund-inventory.png` and corresponding XML files.
- Final full run after the returnability-policy correction: Node 48/48; Kotlin JVM 28/28; Android instrumentation 29/29; debug/test APK builds PASS; lint 0 errors / 96 warnings (105 automated tests). The targeted CT-12 test and the complete final suite both passed after its persisted fixture setup was corrected.
- Existing native/browser engine tests cover partial refund allocation, cash confirmation, credit reversal, UPI-pending cancellation, stock return dispositions, idempotency, stale revisions and rollback. CT-12 does not reimplement those rules.

## Limitations

- No staff identity, role/manager approval, configurable returnability policy, returned-stock inspection, provider-verified payment, or server synchronization exists. Restocking is intentionally unavailable in this UI until policy is persisted and authorized.
- The manual drill covers one full cash-refund item return only. Partial item quantities, Udhar-first allocation, UPI-pending cancellation, amount-only corrections, cancellation, duplicate taps, interruption and insufficient-storage behavior still rely on engine tests or remain unqualified at screen level.
- Partial refund results depend on transaction engine allocations; the screen shows a review but does not promise external payment settlement.

## Evidence

Android instrumentation XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`; method name above. Source: `android/app/src/main/java/com/chaiandtapri/counter/RefundCorrectionActivity.kt`.

## Superseding Sales History to refund commit verification — 31 October 2026

The new `salesHistoryLaunchesAndCommitsAuditedItemRefundWithoutRestocking` instrumentation test drives the complete UI route from a synthetic sale in CT-11 to the item-return dialog in CT-12, enters a reason, explicitly confirms the staff-declared cash payout, reviews and records the refund, and waits until the refunded balance is rendered. It then verifies encrypted persistence: one of two units was refunded, collected cash remains recorded, due remains zero, stock stays at the pre-refund count, no `refund_restock` event exists, and payment/audit history contains the refund. The returned item is intentionally not restocked because the UI has no configured returnability policy. The test passed alone and in the full API 37 instrumentation suite.

Full verification on 31 October 2026: Node 49/49; Kotlin JVM 40/40; API 37 instrumentation 70/70; debug and instrumentation APK tasks PASS; lint 0 errors / 139 warnings; backend tests 5 pass / 4 PostgreSQL-dependent tests skipped. Preserved evidence: `test-evidence/2026-10-31/ct11-ct12-full-instrumentation.xml` and `test-evidence/2026-10-31/ct11-ct12-regression.md`. This remains prototype/emulator evidence. Manual usability review, role approval, returnability policy, other refund allocations/cancellations at screen level, real payment settlement and production qualification remain open.

