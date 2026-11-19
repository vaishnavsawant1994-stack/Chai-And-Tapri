# CT-21 / CT-23 / CT-24 — Customer Credit Ledger Slice

**Date:** 29 October 2026  
**Status:** Partial native local ledger; persisted balance/history rendering is emulator-tested.

## Delivered

- Added a Customers & Udhar screen reachable from Shop Home and the counter.
- Until customer profiles exist, accounts are grouped by their existing customer IDs; no names or contact data are fabricated.
- Shows each account's outstanding receivable, credit-sale order, original credit, repaid and reversed amounts, plus payment/repayment events from the durable ledger.
- Adds a cash repayment dialog accepting an exact rupee amount and calls the existing repayment transaction engine. The UI labels repayments staff-declared and does not claim external verification.
- Existing engine rules reject invalid/non-positive or over-balance repayment and allocate accepted repayment across oldest outstanding credit entries in model order.

## Verification

- New API 37 instrumentation fixture creates a synthetic configured product, count, Udhar sale and partial cash repayment; it launches the actual screen and verifies customer ID, remaining balance and repayment event are rendered.
- Latest full run: Node 48/48; Kotlin JVM 23/23; Android instrumentation 15/15; debug and instrumentation APK builds passed; lint 0 errors / 63 warnings (86 automated checks total).
- The repayment-entry dialog itself has not been manually operated in the emulator. Credit balances here are local-only and no customer profile/search, due dates, messaging, receipts or backend sync exist.

## Remaining scope

This does not complete CT-21 through CT-24. Customer profile creation and search, customer detail screen, credit limits, aging, authorized adjustments, repayment allocation UI, export/receipt, permissions, and broader collection methods remain to be implemented and qualified.

## Superseding CT-24 UI qualification — 30 October 2026

The Customer Directory's partial cash repayment dialog is now exercised in Android UI automation. With a synthetic customer owing ₹20.00 after a prior ₹10.00 repayment on a ₹30.00 credit sale, the test records ₹5.00 through the UI. A fresh encrypted-store read confirms the outstanding balance is ₹15.00 and that the new event is a separate cash `staff_declared` customer repayment. It does not claim bank/provider verification. The full suite passed: Node 49/49, Kotlin JVM 31/31, API 37 instrumentation 43/43 (123 total), APK tasks PASS, lint 0 errors / 109 warnings. This qualifies one UI path only; repayment receipts, other methods, aging and the complete CT-24 module remain unfinished.
