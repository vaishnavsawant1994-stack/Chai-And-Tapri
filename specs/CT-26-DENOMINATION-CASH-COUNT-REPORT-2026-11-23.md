# CT-26 Denomination Cash Count — Verification Report

**Date:** 23 November 2026  
**Status:** Optional physical note/coin count and drawer close path implemented; one synthetic API 37 UI journey passed. Mode D remains partial.

## Implemented

- CT-26 now offers an optional denomination count for ₹2,000, ₹500, ₹200, ₹100, ₹50, ₹20, ₹10, ₹5, ₹2 and ₹1 notes/coins.
- Counts are whole non-negative quantities. The total is calculated in paise with a ₹10 lakh drawer limit and overflow-safe accumulation.
- The count screen compares the physical total to the session's expected cash and displays the difference before confirmation.
- The confirmation explains that the operation stores a physical count/variance only. It does not create or adjust sales, payments, stock or UPI state.
- The final confirmation uses the existing audited `closeCashDrawer` transaction. The original manually entered total remains available as a fallback.

## Verification

Instrumentation test `denominationCashCountClosesDrawerWithoutChangingSalesOrStock` seeded a synthetic ₹1,000 opening float and one ₹15 staff-declared cash sale. It entered two ₹500 notes, one ₹10 note and one ₹5 note through the UI, reviewed the displayed ₹1,015 total against ₹1,015 expected, confirmed drawer closure, and reopened the encrypted snapshot. It verified the ₹1,015 count, the closing audit event, and unchanged orders, payment entries, stock balances and stock movements.

- Focused API 37 instrumentation: **1/1 passed**, no failures/errors/skips.
- Kotlin JVM: **68/68 passed**.
- Debug and instrumentation APK build tasks: **passed**.
- Android lint: **0 errors, 175 warnings**.
- Full Android instrumentation suite was not run for this slice.
- XML: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` (subsequent Gradle connected-test runs overwrite this artifact).

## CT-26 to CT-28 connected review

The focused test now continues from drawer closure into CT-28 Daily Closing for the same session. It confirms the saved physical count and zero cash difference render in the review, and compares the persisted session, order, payment and stock movement records before and after opening CT-28. The refreshed focused API 37 run passed 1/1 after this addition.

## Limits

This improves cash counting for one drawer session; it does not implement a full Mode D reconciliation system. Cash differences remain unresolved evidence, not inferred sales. UPI soundbox/provider totals, missed-item reconstruction, stock reconciliation in the same workflow, staff authentication, multi-register close and field usability are not included. The overall six-wave product and production qualification remain incomplete.
