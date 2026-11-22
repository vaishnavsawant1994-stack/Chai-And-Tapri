# CT-29 Local Report CSV Export

**Updated:** 22 November 2026
**Status:** Date-scoped projection JVM-tested; selected-current-date SAF export, cancellation, and previous-local-day picker interaction focused-tested on API 37. Broad regression remains unqualified.

## Delivered

- Added an **Export current report as CSV** action to Daily Reports.
- Uses Android's Storage Access Framework `CreateDocument` picker, with no broad storage permission.
- Re-reads the encrypted local ledger when a destination is selected, then writes UTF-8 CSV on the shared background executor.
- Exports the selected local business date: net recorded sales, staff-declared cash, current UPI due, current customer credit balances, refund entries, recorded expenses, current supplier payable balance and delivered quantities.
- Labels device-date scope and evidence for each metric. It does not claim verified timestamps, profit, external cash verification or successful UPI collection.
- Applies CSV quoting and protects text fields beginning with formula characters against spreadsheet formula execution. Numeric negative refund values remain numeric.
- Cancellation and write failure report status without mutating business records.

## Date-scoped report continuation

CT-29 now provides a local date picker (not later than the device's current date) and calculates the report using the selected date and device time zone. Orders and delivered units are grouped by order creation timestamp; current refund allocations are shown against the original order date. Refunds and staff-declared cash collection entries use their own ledger-entry timestamps. The report separately labels current UPI due and customer-credit balances for orders created on the selected date, and current supplier payables across all dates. Legacy orders, payment events, and expenses without timestamps are excluded from period totals and counted explicitly.

The report does not imply that device timestamps are server-verified, UPI amounts have settled, cash is independently verified, or that the values are profit, tax accounting, bank settlement, or a daily close. Refunds recorded today can appear as a dated refund event while also reducing net sales on the order's original date; the labels and CSV evidence disclose these different date bases.

### Source-level verification

`DailyReportProjectionTest` covers date-boundary exclusion, Asia/Kolkata local-day conversion, current refund allocation, cash collection/refund event dates, UPI due, credit balance, expense dates, supplier payable scope, delivered unit grouping, and counts for undated legacy data. `DailyReportCsvTest` covers selected-date and timezone labels, undated counts, payment evidence wording, CSV escaping, and spreadsheet-formula protection. Kotlin JVM result: **64 tests passed**. Node: **52/52 passed**. Debug APK and instrumentation APK builds passed; lint: **0 errors / 173 warnings**. Focused API 37 tests passed **1/1** each for report rendering, nonzero CSV export through Android's SAF picker, export cancellation with full persisted-ledger equality, and selecting the previous local date through the native picker while preserving the full persisted ledger. The date test derives today from the report UI and selects the full calendar date to avoid host-clock drift and adjacent-month day-number matches; focused JUnit evidence is `../test-evidence/2026-11-22/ct29-historical-date-focused.xml`. The first export test rerun exposed an outdated assertion for the revised success message; correcting the predicate made the focused SAF run pass. The pulled CSV at `../test-evidence/2026-11-22/ct29-date-scoped-export.csv` shows date `2026-11-22`, timezone `Asia/Calcutta`, 3500 paise recorded sales, 1500 paise staff-declared cash, 2000 paise current UPI due, 500 paise expenses, and the two delivered product quantities. A later broad API 37 regression attempted 110 tests and failed 30, with repeated System UI ANR dialogs obstructing multiple unrelated screens; CT-29 render passed, while export/cancellation were among the UI tests blocked in that run. JUnit evidence: `../test-evidence/2026-11-22/ct29-current-full-instrumentation.xml`. Focused paths are evidenced individually; full-suite qualification is not green.

## Earlier baseline verification

Command: `cd android; .\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --console=plain`

- Kotlin JVM: **50/50 passed** (45 existing CounterModel tests plus 2 CSV tests and other existing suites; aggregate count confirmed from Gradle XML).
- Debug APK and instrumentation APK build tasks: **PASS**.
- Android lint: **0 errors, 146 warnings**.
- Evidence: `../test-evidence/2026-11-01/ct29-csv-export-gradle-final.log`; focused test XML: `../android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.DailyReportCsvTest.xml`.
- No ADB device was connected, so the Activity Result picker and actual destination write were not emulator-run. The exported content formatter is unit-tested; end-to-end SAF qualification remains open.

## API 37 SAF runtime follow-up — 19 November 2026

Installed the current debug APK on the API 37 emulator, created a synthetic local owner on the clean installation, opened Daily Reports from the app navigation, selected the default Downloads destination in Android's Create Document picker, and saved the generated CSV. The Activity returned to CT-29 and displayed the successful export message. The file was present in the emulator's shared Downloads directory and was pulled for inspection; it is 940 bytes and contains the CSV header, all-local-records scope disclosure, staff-declared cash label, and pending UPI label. The report was empty (all totals zero), so this verifies the actual picker/write/success path, not correctness of nonzero financial totals. No report data was seeded, and no external/payment claims are made.

Evidence (ignored synthetic artifacts): `../test-evidence/2026-11-19/ct29-create-document-picker.png`, `ct29-export-result.png`, and `ct29-exported-empty-demo.csv`. A UI screenshot and CSV do not qualify cancellation, write failure, content-provider edge cases, nonzero financial mapping, or manual usability beyond this emulator interaction.

## Nonzero ledger export test — 22 November 2026

Added `dailyReportExportsNonzeroSyntheticLedgerThroughSafSaveDialog`, an Android instrumentation test that seeds two configured products, posts a ₹35 cash/UPI-pending sales mix and a ₹5 recorded expense, launches the actual Daily Reports UI, saves through Android's Create Document picker, and checks the activity's success state. The focused API 37 test passed 1/1 on the isolated `Tapri_Recovery_Isolated` AVD. The downloaded CSV was pulled and inspected at `../test-evidence/2026-11-22/ct29-nonzero-export.csv`.

The exported values were ₹35 (3500 paise) recorded sales, ₹15 (1500 paise) staff-declared cash, ₹20 (2000 paise) UPI pending, and ₹5 (500 paise) recorded operating expenses. It also included one delivered unit for each seeded product, with the all-local-records scope and evidence labels intact. The test exercises the actual Android document picker and destination write; it does not establish provider compatibility beyond this emulator, cancellation/failure behavior, or report usability.

The focused run passed 1/1. The subsequent qualified full-suite XML at `../android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` includes the earlier all-record version of this export test in the 87-test persistence suite (0 failures/errors/skips across the full 105-test run). A stable ignored copy is at `../test-evidence/2026-11-22/ct29-cancellation-full-suite.xml`. The synthetic CSV/XML evidence is ignored by Git and contains no real shop data.

## Cancellation behavior - 22 November 2026

Added `dailyReportExportCancellationLeavesLedgerUnchanged`. The test seeds a synthetic cash sale, opens the real CreateDocument picker, presses Android system Back to cancel (DocumentsUI does not expose a Cancel node on this API 37 layout), waits for the explicit cancellation message, then compares the complete persisted `CounterSnapshot` before and after. It passed 1/1 on the modified current-date report UI on 22 November 2026. The earlier 105/105 full-run evidence is `../test-evidence/2026-11-22/ct29-cancellation-full-suite.xml`; that run predates date-scoped reporting. This validates picker cancellation and local ledger immutability on this emulator only.

## Remaining CT-29 work

Timestamps are device-recorded and can be inaccurate; legacy undated rows are excluded. A future schema should persist explicit timezone/clock provenance and robust period attribution. Profit, tax exports, external settlement verification, backend-consistent reporting, destination write-failure qualification, and broader manual usability review remain unimplemented. This is a local report export, not a statutory accounting export.
