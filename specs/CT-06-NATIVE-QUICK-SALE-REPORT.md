# CT-06 Native Quick Sale — Implementation Report

**Date:** 29 October 2026  
**Status:** Partial vertical slice; transaction behavior is emulator-tested. Full CT-06 acceptance is pending.

## Implemented

- Catalogue-backed native Quick Sale screen with multi-item cart, visible subtotal/total, stock count display, clear cart, checkout and an empty-catalogue route to product setup.
- One `CounterModel.quickSale` operation validates all configured product prices, active state, quantities, stock and supported tender before mutating the model.
- Cash records a staff-declared collection. UPI is recorded as pending and contributes no collected amount. Udhar requires an existing customer account ID and adds a receivable.
- A single atomic persistence commit records the completed/handover order, inventory movements, payment entry/credit, audit event, idempotency receipt and local sync-outbox entry. Duplicate command retries do not post a second sale or movement.
- Emulator instrumentation exercises cash, UPI pending and Udhar quick sales, verifies order/stock/payment/credit values, reopens the encrypted database and verifies persistence and duplicate protection.
- The screen was opened and operated on the API 37 emulator using synthetic data. A synthetic Cutting Chai price of ₹10 was explicitly confirmed in the catalogue, two items were added then decremented to one, and a cash checkout was committed. The screen reported order #12 · ₹10 · cash; stock moved from 100 to 99 and the cart cleared. Live Orders then displayed the same order as GIVEN with `STAFF_DECLARED` payment and ₹0 due.
- MainActivity now reloads the encrypted local model on return from child screens so newly committed orders appear in its order list. A manual checkout exposed the stale in-memory list before this refresh was added.
- Product rows now include a decrement control that removes the row when its quantity reaches zero.
- Returning from product setup refreshes the catalogue on Quick Sale resume.

## Verification

- Node: 48/48 passed (`node --test prototype/*.test.cjs`).
- Kotlin JVM: 23/23 passed.
- Android instrumentation: 12/12 passed on `Medium_Phone_API_37.0` (includes recovery, migration, write barrier and transaction persistence tests; not 12 UI tests).
- Debug and instrumentation APK builds: PASS.
- Android lint: 0 errors, 54 warnings.
- Manual checkout evidence: `test-evidence/2026-10-29/ui-manual-catalog-saved.png`, `ui-manual-quick-configured.png`, `ui-manual-quick-cart-two.xml`, `ui-manual-quick-cart-one.xml`, `ui-manual-quick-sale-cash-complete.png` and matching XML, plus `ui-manual-live-order-cash.png` and matching XML.
- Empty/unconfirmed catalogue evidence: `test-evidence/2026-10-29/cat06-quick-sale-no-confirmed-products.png` and matching XML. Full suite report: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`.

## Gaps

This manual UI exercise used one synthetic configured product and one cash sale. UPI pending and Udhar UI were not manually exercised in this run (engine/instrumentation coverage remains separate). This slice does not support split tenders, customer search/selection (Udhar accepts a typed account ID), refunds, discounts, tax, receipt printing, barcode scanning, order history, cash drawer reconciliation, or staff permissions. The starting stock of 100 was synthetic; a physical stock count/purchase intake workflow is needed before using it as a real shop's inventory authority. No production or shop qualification is implied. CT-07's proposed voice command was not committed as part of this manual checkout run.

## CT-06 search and filter continuation — 30 October 2026

Quick Sale now searches case-insensitively across configured product name, stable ID, category and voice/search aliases. A category selector filters all confirmed-price products, favorites or a specific product category. No-match feedback distinguishes an empty result from an empty catalogue. The UI still uses the same confirmed catalogue prices and does not alter the transaction engine. API 37 instrumentation seeded a synthetic favorite product, confirmed its opening quantity, launched Quick Sale, verified mixed-case alias search, category filtering, favorite filtering and the no-match state. Current full verification: Node 49/49, Kotlin JVM 33/33, Android instrumentation 45/45, APK build tasks PASS, lint 0 errors / 114 warnings. Evidence is in `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` under `quickSaleSearchAndCategoryFiltersUseConfiguredProductAliases`. CT-06 remains partial: no UI-level UPI/Udhar exercise, customer lookup, split tender, receipt, discount policy, or accessibility/manual usability qualification.
