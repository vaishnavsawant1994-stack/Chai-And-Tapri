# CT-28 — Daily Closing Follow-up Navigation

**Date:** 26 November 2026  
**Status:** Implemented and focused emulator-tested; CT-28 remains a partial workflow.

## Change

The closing review now links recorded exceptions directly to their existing operator workflows:

- Open orders → CT-08 Live Orders.
- Pending UPI attempts → CT-10 Payments.
- Recorded customer receivable → CT-23 Customer Credit.

Each link appears only when the corresponding local recorded balance/count is nonzero. Pending UPI remains labeled as not verified. Navigation does not settle payments, modify orders, or change receivables. Returning to closing reopens the encrypted local model and preserves the review state.

## Verification

- Synthetic API 37 instrumentation case `dailyClosingReviewShowsSessionTotalsAndKeepsCurrentExceptionsSeparate`: **1/1 passed**. It verified the three exception values, opened each destination through its actual screen button, returned to closing, recorded the existing review note and estimate flow, and confirmed closing acknowledgements did not mutate sales/payment/stock records.
- Kotlin/JVM unit suite: **96/96 passed**.
- `assembleDebug assembleDebugAndroidTest lintDebug`: **PASS**.
- Android lint: **0 errors, 191 warnings**.

## Limits

This remains a closed-drawer session review, not a calendar-day accounting report. It does not add close-period sales totals, verified bank settlement, stock count, supervisor approval, or a complete daily report. Full Android regression was not rerun for this UI-only slice; the current connected instrumentation qualification is the one focused test above.
