# CT-09 Pre-fulfillment Order Price Correction — 25 November 2026

## Delivered

Order Details now lets an authenticated manager/owner adjust a product's unit price on an entirely unhanded order. The action is available only before fulfillment and before any order-linked payment, credit, or refund record exists. The operator enters a rupee amount with at most two decimal places and a 3–120 character reason, reviews the recalculated total, and explicitly confirms the change.

The transaction validates the current order revision, supported price range, unchanged financial/fulfillment state, line identity, and total overflow before mutation. It updates only the order-line price snapshot and order revision. Catalogue pricing, stock and movement history, payments, and customer credit are unchanged. The local audit event stores old/new paise amounts and the reason; the allow-listed sync projection sends the validated order snapshot and unit price but excludes the free-text reason.

Price changes are rejected after any quantity has been handed over, or if any order-linked payment/credit/refund event exists. This deliberately leaves partial-fulfillment repricing for a separate design because delivered and undelivered quantities need distinct price history.

## Verification

- Kotlin JVM: **86/86** passed, 0 failures/errors. Coverage includes bounds, reason validation, duplicate command, stale revision, audit, unchanged stock/payment, post-handover rejection, and sync payload privacy.
- API 37 emulator UI: **1/1** focused test passed. It executes the actual price-entry, reason, review, and confirmation dialogs and checks persisted order total/revision, unchanged catalogue price/stock/payments, and owner-attributed audit.
- Debug APK and Android-test APK build tasks: passed.
- `lintDebug`: **0 errors / 187 warnings**.
- [Focused instrumentation XML](../test-evidence/2026-11-25/ct09-price-correction-focused-instrumentation.xml).
- Full Android instrumentation suite and Node tests were not rerun for this slice.

## Remaining scope

CT-09 remains partial. This does not support discounts, price changes after fulfillment begins, historical delivered-price allocation, or direct changes to catalogue prices. Browser visual review, physical-device/shop usability, independent security review, cross-device recovery, deployment, and production qualification remain open.
