# CT-13/14 product unit metadata sync and owner projections — 25 November 2026

## Delivered

- The Android allow-listed `projection_delta.v1` encoder now optionally includes a product's configured base-unit label with changed order lines, stock snapshots and stock movements.
- Existing clients/events that omit this field remain valid. The backend accepts only trimmed, printable labels of 1–24 characters.
- The sync service derives the first accepted label for each product ID from the journal and rejects a later conflicting label. It checks this after idempotency and revision handling and only records a new label when the event is accepted.
- Cashiers may include an already-established label in sale events but cannot establish the first synchronized label; owner/manager setup must establish it.
- Owner product-performance and inventory projections return the optional label seen in accepted server events. Recent movement rows also expose the label.
- This shares quantity labels alongside existing transaction events; it does not synchronize a complete product catalogue or add product editing, product name/price/category sync, pack conversion, recipes, or variants.

## Verification

- Backend Node: **32 passed, 0 failed, 5 skipped**. The skipped cases require PostgreSQL and were not run in this invocation (`node --test test/*.test.mjs` from `backend/`).
- Kotlin JVM: **90 passed, 0 failed** (`:app:testDebugUnitTest`). Includes optional label emission on sale line, stock snapshot and movement, plus legacy no-label payload behavior.
- Prototype Node: **52 passed, 0 failed** (`node --test *.test.cjs` from `prototype/`).
- `assembleDebug assembleDebugAndroidTest lintDebug`: **BUILD SUCCESSFUL**. Lint: **0 errors, 189 warnings**.
- No live PostgreSQL sync integration test or connected Android instrumentation test was completed for this slice.

## Regression result and limits

A connected Android regression attempt is **failed/incomplete**, not passed. It recorded 28 passing cases, then `cashierCanSignInThroughAccessScreenAndSeesCounterOnlyMenu` failed because the emulator displayed “System UI isn't responding” instead of the staff sign-in screen. The follow-up XML is at `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`. API 37 requires a 4 GB emulator memory allocation, and the host ran low on available memory. This test result does not establish an app defect or pass; the broad suite still needs a stable run.

The label-lock helper has unit coverage, but the PostgreSQL-backed event acceptance path was skipped here. This slice is not production-sync, cross-device recovery, independent security review, field, physical Bluetooth, deployment, or full-product qualification.

## Next executable task

Run the authenticated PostgreSQL integration suite against a disposable database with a synthetic owner/shop/device and test: first accepted unit label, legacy unlabeled history, conflicting subsequent label, duplicate event idempotency, and stale-revision conflict. Then rerun the Android connected regression on an emulator with sufficient host headroom or an API 36 AVD, preserving the existing synthetic data and test artifacts.
