# CT-16 — Stock Count History CSV Export

**Date:** 25 November 2026
**Status:** Focused API 37 emulator qualification passed for the synthetic export path.

## Delivered

Stock Count History now exports its local audit projection through Android's Storage Access Framework `CreateDocument` picker. The CSV uses one row per product in a physical-count session and includes command ID, device-recorded epoch timestamp, device time zone, product ID/name, exact quantity when recorded, reason, whether a local actor was recorded, and an evidence limitation. Older events without exact quantities are exported with a blank quantity and `historical quantity unavailable`; the exporter does not infer a value. CSV cells are quoted and spreadsheet-formula-leading text is neutralized. The export is read-only and uses no broad storage permission.

## Verification

- Kotlin JVM suite: **88 passed, 0 failed**; includes two CSV renderer tests for zero/exact and unavailable legacy quantities, quoting, formula neutralization, and evidence labels.
- Debug APK and instrumentation APK build tasks: **PASS**.
- Focused API 37 emulator test `stockCountHistoryExportsThroughSystemPickerWithoutChangingLedger`: **1 passed, 0 failed/errors/skipped**.
- The test opened the real system Save picker, wrote a CSV, observed completion, and compared the encrypted persistence snapshot before/after.
- The saved synthetic CSV was separately inspected and contains one row with count `4`, device zone `Asia/Calcutta`, reason `initial export count`, and the local-clock/identity caveat. See [`synthetic CSV`](../test-evidence/2026-11-25/ct16-count-history-export/ct16-count-history-synthetic.csv).
- Android lint: **PASS**, 0 errors, 189 warnings.

JUnit: [`focused instrumentation XML`](../test-evidence/2026-11-25/ct16-count-history-export/ct16-count-history-export-instrumentation.xml).

An initial test run exported only the header because its fixture used an opening-stock event rather than a physical-count event. Inspecting the resulting file exposed the fixture error; the fixture was corrected to create the intended physical-count audit record, and the current-source test and CSV were rerun and inspected successfully.

## Limits

Node tests and the full connected instrumentation suite were not rerun for this slice. The export represents this device's local audit history; device timestamps and local actor presence are not independent verification. It is not a synchronized count register or proof of physical inventory accuracy. CT-16 and the product remain partial; hardware, field, independent security review, and production gates remain open.
