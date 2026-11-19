# CT-29 Local Report CSV Export

**Updated:** 19 November 2026
**Status:** Local CSV formatter JVM-tested; Android SAF destination selection and file-write path manually runtime-qualified on API 37 for an empty synthetic ledger.

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

## Remaining CT-29 work

Calendar-day filters require consistent trusted event timestamps and are not added here. Profit, tax exports, external settlement verification, backend-consistent reporting and broader manual usability review remain unimplemented. This is a local report export, not a statutory accounting export.
