# CT-29 Local Report CSV Export

**Updated:** 22 November 2026
**Status:** Formatter JVM-tested; API 37 Android SAF success and cancellation paths qualified with synthetic ledger data.

## Delivered

- Added an **Export current report as CSV** action to Daily Reports.
- Uses Android's Storage Access Framework `CreateDocument` picker, with no broad storage permission.
- Re-reads the encrypted local ledger when a destination is selected, then writes UTF-8 CSV on the shared background executor.
- Exports only the report's current local snapshot: recorded sales net of refunds, staff-declared cash, pending UPI, customer receivables, recorded refunds, expenses, supplier payables and delivered quantities.
- Labels the scope as all local records at export time. It does not claim calendar-day attribution, profit, external cash verification or successful UPI collection.
- Applies CSV quoting and protects text fields beginning with formula characters against spreadsheet formula execution. Numeric negative refund values remain numeric.
- Cancellation and write failure report status without mutating business records.

## Verification

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

The focused run passed 1/1. The subsequent qualified full-suite XML at `../android/app/build/outputs/androidTest-results/connected/debug/TEST-Tapri_Recovery_Isolated(AVD) - 17.xml` includes this export test in the 87-test persistence suite (0 failures/errors/skips across the full 105-test run). A stable ignored copy is at `../test-evidence/2026-11-22/ct29-cancellation-full-suite.xml`. The synthetic CSV/XML evidence is ignored by Git and contains no real shop data.

## Cancellation behavior - 22 November 2026

Added `dailyReportExportCancellationLeavesLedgerUnchanged`. The test seeds a synthetic cash sale, opens the real CreateDocument picker, presses Android system Back to cancel (DocumentsUI does not expose a Cancel node on this API 37 layout), waits for the explicit cancellation message, then compares the complete persisted `CounterSnapshot` before and after. Focused test passed 1/1; the isolated-device full instrumentation suite passed 105/105 with zero failures/errors/skips. The wrapper `android/scripts/run-isolated-connected-tests.cmd emulator-5556` was used; its invalid-device guard was also checked and returned exit code 4 for a nonexistent serial. The full suite evidence is `../test-evidence/2026-11-22/ct29-cancellation-full-suite.xml`. This validates picker cancellation and local ledger immutability on this emulator only.

## Remaining CT-29 work

Calendar-day filters require consistent trusted event timestamps and are not added here. Profit, tax exports, external settlement verification, backend-consistent reporting, destination write-failure qualification and broader manual usability review remain unimplemented. This is a local report export, not a statutory accounting export.
