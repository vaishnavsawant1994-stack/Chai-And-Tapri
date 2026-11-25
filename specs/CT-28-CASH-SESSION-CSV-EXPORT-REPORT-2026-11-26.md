# CT-28 closed cash-session CSV export — 26 November 2026

## Delivered

Daily Closing now offers a user-controlled Android Storage Access Framework export for the selected closed drawer session. The CSV contains integer-paise amounts, local session timestamps, expected cash, operator-counted cash, and the unresolved variance. It includes clear evidence descriptions for staff-declared cash entries and device-clock timestamps.

The export omits staff names, actor identifiers, handover notes, customer identities, and free-text reasons. CSV cells are quoted and formula-leading values are escaped. Export and picker cancellation do not post, clear, or adjust business records. Export data is snapshotted before opening the picker, so activity reload while DocumentsUI is foregrounded cannot change the exported values.

## Verification

- Kotlin/JVM: **100/100 passed**, including two new CSV tests for paise values, evidence labels, negative variance handling, privacy omissions, and formula escaping.
- `assembleDebug`: PASS.
- `assembleDebugAndroidTest`: PASS; the updated CT-28 instrumentation test compiles and checks that the export control is exposed.
- `lintDebug`: PASS, 0 errors / 196 warnings.
- API 37 instrumentation runtime: **not run** for this slice because the emulator was lost during the current-source full regression. SAF file creation, picker cancellation, and read-back of a created file remain runtime qualification items.

## Scope limits

This exports a closed drawer session, not a calendar-day accounting statement. It is not a profit, bank settlement, verified UPI, or statutory report. Reported cash entries remain staff-declared. The owner/manager gate remains in place. Browser visual review, physical-device use, field qualification, independent security review and release qualification remain open.
