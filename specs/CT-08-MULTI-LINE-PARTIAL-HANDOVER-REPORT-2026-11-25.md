# CT-08 multi-line partial handover — 25 November 2026

## Delivered

Live Orders now lets the operator select multiple undelivered product lines and enter a quantity for each, then record them through one transaction. A new `CounterModel.partialHandover` validates order state, revision, item membership, remaining quantities, stock confirmation and availability for every selected line before changing state. One atomic commit updates all delivered quantities, stock movements, order state, audit event and sync outbox. Invalid batches leave the entire order and stock unchanged. Duplicate command IDs are ignored; stale revisions are rejected. The sale is marked posted only after all lines are handed over.

## Verification

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest`: **BUILD SUCCESSFUL**; Kotlin JVM **82/82 passed**.
- Focused API 37 `liveOrdersPartialHandoverCanSelectMultipleLinesAtomically`: **1/1 passed**. It selects two lines with different quantities and verifies durable delivery quantities, stock movements, one audit command, and that the partial order is not posted as a completed sale.
- Focused API 37 `liveOrdersCanRecordAValidatedPartialItemHandoverFromTheScreen`: **1/1 passed** after adapting its interaction to select the line explicitly.
- JVM `multiLinePartialHandoverIsAtomicAuditedAndIdempotent`: covered valid multi-line handover, invalid quantity rollback, duplicate replay and stale revision rejection.
- `lintDebug`: **BUILD SUCCESSFUL**, 186 warnings; no lint task failure. Report: `android/app/build/reports/lint-results-debug.html`.
- Full serial-pinned default Android suite: **140 tests, 139 passed, 1 failed, 0 errors, 0 skipped**. The single failure was an unrelated `orderDetailsScreenReadsPersistedFinancialStockAndAuditRecords` Activity launch timeout (45 seconds, Android main queue not idle). That same test passed in a focused rerun **1/1**. The full regression therefore remains unqualified after this change; this result is not reported as a green full-suite pass.

Synthetic emulator data only. This closes one multi-item fulfillment path, not the whole CT-08 module or daily-shop workflow. Physical use, offline sync across devices, full UI usability/accessibility, continuous voice and production readiness remain open.
