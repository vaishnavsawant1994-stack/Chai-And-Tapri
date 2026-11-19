# CT-05 Shop Home â€” Implementation Report

**Date:** 29 October 2026  
**Status:** Partial local dashboard; emulator UI and summary calculations tested.

## Implemented

- Native Shop Home activity reads the existing encrypted local business model and provides links to Quick Sale, Live Orders, Products and the Voice Counter.
- It reports lifetime recorded sales net of recorded refunds, net staff-declared cash events, outstanding UPI-pending amounts, open customer-credit balances, open-order count and active sellable product count (items with owner-confirmed prices). It explicitly does not show a stock total because the prototype opening stock is unqualified.
- The screen states that values are local lifetime totals and synchronization is not configured. It does not report daily business or profit. Profit is withheld because product costs and expense records are not configured.
- The summary is calculated in the domain model from the existing persisted orders, payment events and customer credit state. Android instrumentation asserts expected totals for synthetic cash, UPI-pending and Udhar sales.

## Verification

- Kotlin JVM: 23/23 passed.
- Android instrumentation: 12/12 passed on `Medium_Phone_API_37.0`, including assertions for Shop Home summary arithmetic.
- Debug APK and instrumentation APK builds: PASS.
- Android lint: 0 errors, 54 warnings.
- Manual API 37 screen open/screenshot: `test-evidence/2026-10-29/ct05-shop-home.png` and `ct05-shop-home.xml`.

## Limitations

This is not a daily dashboard, verified bank/UPI report, cash drawer reconciliation or profit report. Stock counts require a validated opening-stock/count and purchase-receipt workflow before they can be treated as real shop inventory. The screen has no configurable thresholds, date filters, charts, role restrictions, staff authentication, sync freshness, or owner-level branch reporting.


