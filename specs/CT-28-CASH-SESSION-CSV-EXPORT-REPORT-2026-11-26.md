# CT-28 closed cash-session CSV export — 26 November 2026

## Delivered

Daily Closing now offers a user-controlled Android Storage Access Framework export for the selected closed drawer session. The CSV contains integer-paise amounts, local session timestamps, expected cash, operator-counted cash, and the unresolved variance. It includes clear evidence descriptions for staff-declared cash entries and device-clock timestamps.

The export omits staff names, actor identifiers, handover notes, customer identities, and free-text reasons. CSV cells are quoted and formula-leading values are escaped. Export and picker cancellation do not post, clear, or adjust business records. Export data is snapshotted before opening the picker, so activity reload while DocumentsUI is foregrounded cannot change the exported values.

## Verification

- Kotlin/JVM: **100/100 passed**, including two new CSV tests for paise values, evidence labels, negative variance handling, privacy omissions, and formula escaping.
- `assembleDebug`: PASS.
- `assembleDebugAndroidTest`: PASS; the updated CT-28 instrumentation test compiles and checks that the export control is exposed.
- `lintDebug`: PASS, 0 errors / 196 warnings.
- API 37 instrumentation: focused `dailyClosingReviewShowsSessionTotalsAndKeepsCurrentExceptionsSeparate` passed **1/1** on `Tapri_Recovery_Isolated` after a fresh emulator boot. The test opened Android's CreateDocument picker, saved the CSV, received the app's successful-write status, and verified that order, payment, and stock ledgers remained unchanged. Captured output: `../test-evidence/2026-11-26/ct28-csv-export-focused-instrumentation.txt` (local ignored evidence).
- Independent emulator file read-back: PASS. The saved CSV was pulled from `/sdcard/Download` and inspected. It contained the expected 5,000-paise opening float, 1,500-paise staff-declared order collections, 200-paise cash expenses, 6,300-paise expected cash, 6,000-paise physical count, and -300-paise unresolved variance. No customer or staff identity fields were present. Local ignored copy: `../test-evidence/2026-11-26/ct28-csv-export-readback.csv`.
- The initial focused UI run exposed that the success message disappeared when the activity resumed from DocumentsUI. The screen now retains and re-renders that status; the focused instrumentation test passed after the fix.

## Scope limits

This exports a closed drawer session, not a calendar-day accounting statement. It is not a profit, bank settlement, verified UPI, or statutory report. Reported cash entries remain staff-declared. The owner/manager gate remains in place. The focused API 37 flow and independent read-back qualify this one synthetic export scenario only; broad current-source regression, browser visual review, physical-device use, field qualification, independent security review and release qualification remain open.
