# CT-15 / CT-16 — Opening Stock Confirmation Report

**Date:** 29 October 2026  
**Status:** Limited implementation; Android API 37 emulator exercised.

## Delivered

- Fresh local databases start with zero stock for illustrative catalogue products.
- Legacy stock quantities are preserved by the v7→v8 additive migration, but are not treated as verified sellable stock.
- A new `stock_baseline_confirmations` table records which product balances have an owner-confirmed physical count.
- The catalogue hides unconfirmed legacy quantities and offers an audited physical count action.
- A count requires an absolute quantity, a reason, an idempotency command ID, and an active product. It writes the stock movement, audit event, command receipt and outbox entry atomically.
- Strict configured sales and handovers reject products without a confirmed stock baseline. Confirmed quantities are decremented through the existing stock movement rules.
- Added a dedicated CT-15 inventory overview reachable from Shop Home and the counter home. It shows confirmed balances only, labels unconfirmed stock, and lists the latest 30 movements with product, delta, reason, and order reference where present. Its count action routes to the audited catalogue workflow.
- A failed persistent write restores the in-memory state; duplicate count commands do not apply twice.

## Evidence and verification

- Latest complete local verification after CT-10 partial tender UI: Node 48/48, Kotlin JVM 23/23, Android instrumentation 14/14; debug APK and instrumentation APK builds passed; lint 0 errors / 62 warnings. Total: 85 passing checks across the three suites.
- API 37 emulator UI flow: opened the catalogue, opened the physical-count dialog, entered `5` and `physical count`, saved, and observed `Opening stock confirmed for Coffee: 5 units` plus `Opening count confirmed · 5 units`.
- Evidence: `test-evidence/2026-10-29/ct13-count-saved.png` and `ct13-count-saved.xml`.
- Opened CT-15 through the app navigation; screenshot and hierarchy: `test-evidence/2026-10-29/ct15-inventory.png` and `ct15-inventory.xml`. It displayed all four products as requiring physical counts after the instrumentation suite reset its synthetic database.
- Automated instrumentation covers strict sale rejection before count, injected persistence failure, retry, sale after confirmation, restart persistence, and duplicate command protection.

## Scope limits

This is not full CT-15 inventory management or CT-16 stock counting. There is no purchase intake, receiving, count-session workflow, variance report, multi-unit conversion, recipe consumption, low-stock/reorder screen, or operational policy configuration. The demonstration count changed synthetic Coffee stock only. Starter prices remain unconfirmed, so this does not authorize the product for sale. Stock counts must be based on a real physical count in any actual shop.

Legacy quantities remain preserved for recovery/history but must be physically confirmed before strict sale paths treat them as usable. Outbox events are local and are not synchronized. Encryption and recovery remain subject to the separately documented CAT-051 qualification limits.

## Superseding CT-16 multi-product stock-count workflow — 30 October 2026

CT-16 now has a dedicated screen reachable from Inventory Overview. Operators select only products they physically counted, enter a whole-number count including zero, and provide a reason. The model validates every selected product before mutation, then persists the whole selection in a single encrypted SQLite commit with per-product stock movements, one audit event, one idempotency receipt, and one outbox row. A failed commit restores the prior in-memory state. No sale, purchase, or supplier payable is created. API 37 UI test verifies navigation, two-product count, zero stock, movements and outbox; Kotlin JVM test covers invalid counts, duplicate commands and rollback. Full run: Node 49/49, Kotlin JVM 32/32, Android API 37 instrumentation 44/44 (125 total), APK tasks PASS, lint 0 errors / 110 warnings. Variance approval, count-session history and low-stock thresholds remain absent.

## Final CT-16 form-validation rerun — 30 October 2026

The count form now reports invalid input in place without clearing other selected products or their typed values. The API 37 test enters one valid and one invalid value, confirms no mutation and preserved inputs, corrects the invalid value, and verifies the durable two-product count. Fresh full verification after this UI change: Node 49/49, Kotlin JVM 32/32, Android instrumentation 44/44 (125 total), APK tasks PASS, lint 0 errors / 113 warnings. The test XML is `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`; lint output is `android/app/build/reports/lint-results-debug.txt`.
