# CT-28 busy-counter estimate capture — 23 November 2026

## Implemented

Daily Closing now lets an owner/manager save an unitemized busy-counter amount with a reason and explicit second-step confirmation. The value is stored as an `estimated` audit event in encrypted local storage, can be listed separately, and is idempotent by command ID. Validation requires ₹0.01–₹1,000,000 and a 4–120 character reason. The feature does not create an order, payment, stock movement, expense, cash adjustment, or profit value. It is not included in recorded sales or collections and is not synchronized to the owner dashboard.

## Verification

- JVM business test `busyCounterEstimateIsAuditedAndNeverChangesRecordedBusinessTotals`: passed; verifies persistence through model reopen, idempotency, invalid-amount rejection, and unchanged summary/orders/stock/payment history.
- Focused API 37 UI instrumentation `dailyClosingReviewShowsSessionTotalsAndKeepsCurrentExceptionsSeparate`: passed 1/1 in 43.323 seconds. It enters ₹8.75 through the Daily Closing form, verifies the estimate classification renders and persists, and confirms orders, stock, stock movements, payment entries, expenses, drawer session, expected cash and recorded business summary remain unchanged.
- Kotlin JVM suite: 69/69. Debug APK, instrumentation APK and lint tasks passed; lint 0 errors / 176 warnings.
- Android instrumentation evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml`.

## Qualification boundary

This is a synthetic, focused emulator path. Estimates are local and not part of a full estimate ledger, cloud report, reconciliation, or approval workflow. The UI does not infer product quantities from an amount. Mode D and CT-28 remain partial; broad Android regression, physical device and shop qualification were not run for this slice.
