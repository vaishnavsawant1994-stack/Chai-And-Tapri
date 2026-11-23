# CT-13/14 Product Catalogue to CT-06 Quick Sale — Verification Report

**Date:** 23 November 2026  
**Status:** One connected synthetic Android UI workflow passed on API 37; catalogue and Quick Sale remain partial modules.

## Verified workflow

Instrumentation test `catalogueUiCreatesConfirmedProductAndQuickSalePostsItsConfiguredPrice` exercises the native UI and encrypted local store:

1. Open the owner-protected Products & Prices screen.
2. Create synthetic product `e2e_lemon_chai` (`E2E Lemon Chai`, category `Chai`) at ₹12.50 and explicitly confirm that price.
3. Record an audited physical opening count of five units with a reason.
4. Edit the product in the catalogue UI, save the aliases `lemon` and `nimbu chai`, and mark it as a favourite.
5. Open native Quick Sale, find the product using the `nimbu` alias, and record a cash checkout.
6. Reopen the persisted snapshot and verify the ₹12.50 order, four units remaining, a single `quick_sale` movement of −1 linked to that order, and a `staff_declared` cash collection. The audit history includes the product changes.

The test uses an isolated synthetic database and deletes it after assertions. It does not use real shop/customer data.

## Verification evidence

- Focused API 37 instrumentation: **1/1 passed**, 0 failures/errors/skips.
- Kotlin JVM: **68/68 passed**.
- Debug APK and instrumentation APK tasks: **passed**.
- Android lint: **0 errors, 175 warnings**.
- `git diff --check`: passed.
- JUnit XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (the file is overwritten by later Gradle runs).

## Qualification limits

This verifies one owner-created product, editor alias/favourite update, alias search and cash sale path on the API 37 emulator. It does not qualify every editor validation/error path, staff role policy, product price history/approval, other payment choices, scanner decoding, accessibility/manual usability, the full CT-06/CT-13/CT-14 screens, real hardware, or shop use. Overall six-wave and production status remain incomplete.
