# CT-06 Quick Sale Cart Quantity Controls

**Date:** 22 November 2026  
**Status:** Implemented; API 37 emulator-tested.

## Delivered

- Cart rows now expose direct decrement and increment buttons with accessible content descriptions.
- Increment is allowed only after physical stock is confirmed, below the configured cart cap of 99, and below the available stock quantity.
- Rejected increments leave the cart unchanged and show a reason. Decrement updates the current draft cart only.
- Cart quantity edits do not post an order, payment, or inventory movement. Checkout remains the posting boundary.

## Verification

- The API 37 test selects a product, adds it to the cart, increments to its recorded stock, verifies over-increment is blocked, decrements, and increments again.
- The test reopens encrypted persistence and verifies the stock balance is unchanged and no order was posted before checkout.
- Full current-source verification: Node 52/52; Kotlin JVM 63/63; API 37 instrumentation 101/101 (0 failures/errors/skips); debug and instrumentation APK tasks pass; lint 0 errors / 171 warnings.
- Backend suite: 19 passed; 4 PostgreSQL-dependent tests skipped in this run.

## Limits

This verifies the emulator workflow only. It does not qualify the full Quick Sale experience, physical devices, camera decoding, paired scanners, accessibility usability, Bluetooth, field operations, production storage, or release readiness.

Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`, Kotlin JVM XML under `android/app/build/test-results/testDebugUnitTest/`, and `android/app/build/reports/lint-results-debug.txt` (generated build evidence; ignored by Git).
