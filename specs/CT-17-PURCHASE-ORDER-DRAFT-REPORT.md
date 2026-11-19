# CT-17 Purchase Order Drafts and Partial Receiving

**Date:** 1 November 2026  
**Status:** Implemented in the native prototype; Kotlin-tested, build-qualified, and API 37 emulator-qualified for the current 76-case instrumentation suite.

## Delivered workflow

- Create a multi-line draft against an existing supplier and active catalogue products, with ordered quantities and estimated unit costs in paise.
- Saving a draft is an audited local command. It creates no purchase receipt, stock movement, payable, or cash event.
- Mark a draft sent only after the owner has actually sent it outside the app. The app does not send an order to the supplier.
- Record a partial or final receipt against a sent order. Each receipt is linked to the order and posts stock, purchase value/payable, audit/idempotency data, and outbox state through the existing atomic receiving command.
- Reject unlisted products, changed unit cost, receipt quantities above the ordered remainder, stale order revisions, non-sent orders, and duplicate receipt commands.
- Cancel a draft/sent order only with a reason and only before any linked delivery is recorded. The audit event preserves the cancellation.
- The UI shows ordered and received quantities and derives `DRAFT`, `SENT`, `PARTIALLY RECEIVED`, `RECEIVED`, or `CANCELLED` from stored state and receipts.

## Persistence and accounting

SQLCipher schema **v21** adds purchase-order draft and line tables and a nullable purchase-order reference on purchase receipts. Migration from v20 is additive; older migration paths create the new tables without dropping existing records. Backups carry the database file and use the current schema version. Supplier payable remains receipt value less supplier payments and purchase returns; draft estimates do not enter that calculation.

## Verification

- Kotlin JVM: **54/54 passed**. New tests cover draft non-posting, stale revisions, partial/final receipts, over-receipt rejection, duplicate receipt idempotency, cancellation audit, and model reopen.
- Node prototype: **52/52 passed** (unchanged subsystem, rerun for this slice).
- Debug APK build: **PASS**.
- Instrumentation APK build: **PASS**.
- API 37 instrumentation: **76/76 passed, 0 failed** on the final current-source run. The run includes purchase-order migration/partial delivery, procurement receiving UI, encrypted database and backup/recovery flows, and CT-16 stock-count review. XML: `../test-evidence/2026-11-01/ct17-full-final-instrumentation.xml`; Gradle log: `../test-evidence/2026-11-01/ct17-full-final-instrumentation.log`.
- Focused CT-17 API 37 instrumentation also passed **2/2** in separate runs; preserved focused XML: `../test-evidence/2026-11-01/ct17-migration-focused.xml` and `../test-evidence/2026-11-01/ct17-receiving-ui-focused.xml`.
- Android lint: **0 errors, 154 warnings**.
- Backend without PostgreSQL: **11 passed, 4 skipped**. The separate isolated PostgreSQL 17 OW-05 integration run remains 15/15; no backend source changed for this slice.

The first full run found two testable issues: four older-schema fixtures replayed the v20→v21 upgrade and exposed non-idempotent table creation, and the CT-16 instrumentation assertion searched for each line of a single dialog message as a separate node. The migration now creates tables idempotently; CT-16 test coverage now reads the whole dialog message and checks both variance lines. After these corrections the full API 37 suite passed **76/76**. The earlier failing run is retained at `../test-evidence/2026-11-01/ct17-connected-instrumentation.log`; the final pass supersedes it.

Evidence: `../test-evidence/2026-11-01/ct17-purchase-order-drafts-ui-gradle.log`, `ct17-purchase-order-jvm-rerun.log`, and `ct17-purchase-order-node.log`.

The first JVM run found a bad test assertion that expected a receipt's purchase-order link to contain receipt IDs. The assertion was corrected to verify two receipts link to the same purchase-order ID, and the Kotlin suite then passed 54/54. The emulator surfaced a separate older-schema replay issue, which has also been corrected and passed its focused device test.

## Limits

This is a local prototype workflow. It does not transmit supplier orders, capture an invoice, manage rejected/short deliveries, support editing a saved draft, allocate lots, provide supplier payment verification, or resolve multi-device concurrent order edits. The current synthetic API 37 test suite passes, but this does not qualify a full shop-day workflow, physical Bluetooth or real-shop conditions. The project remains incomplete and is not production-qualified.
