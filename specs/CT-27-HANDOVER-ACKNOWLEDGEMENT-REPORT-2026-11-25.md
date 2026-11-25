# CT-27 recipient-side cash handover acknowledgement

**Date:** 25 November 2026  
**Status:** Implemented and focused API 37 emulator-tested; CT-27 remains partial.

## Behavior delivered

After a shift is closed, its designated enabled local staff recipient can see the pending handover on CT-27 and record a physical cash count. Only the authenticated local actor whose ID was assigned as the receiver can acknowledge it. The event can be accepted once; replays are rejected. Disabled accounts, another staff account, unassigned handovers, open shifts, invalid counts, and incomplete restored state are rejected.

The acknowledgement is a separate audit event attributed to the recipient's local actor ID. It stores the receipt time and count. The sender's recorded closing count and expected drawer cash are unchanged. Any difference is shown as unresolved rather than reconciled automatically.

## Persistence, backup, and sync

Encrypted SQLite schema v33 adds nullable `handover_received_at` and `handover_received_cash_paise`, with an additive v32→v33 migration. Existing handovers remain unacknowledged. Snapshot integrity requires timestamp and amount together, an already-closed shift, and an assigned receiver. The fields are included in local persistence and portable encrypted backup snapshots.

The sync encoder now compares the financial/session projection fields explicitly instead of treating every local session metadata change as a financial shift update. The receiver acknowledgement therefore does not enqueue a duplicate shift projection or expose the recipient ID/count to the server. Existing shift-open/close projection fields remain unchanged.

## Verification

- Kotlin JVM: **95/95 passed** (`testDebugUnitTest`), including receipt-state integrity and local-only outbox projection tests.
- Prototype Node: **52/52 passed** (`node --test 'prototype/*.test.cjs'`).
- API 37 focused CT-27 UI/model test: **1/1 passed**. It verified recipient assignment, v32→v33 migration, mismatch rejection, pending acknowledgement and button visibility for the signed-in recipient, successful receipt count, unresolved variance, replay rejection, database reopen, and recipient actor in audit history.
- API 37 focused portable encrypted snapshot test: **1/1 passed**. `verifiedBackupRestoresSyntheticFinancialHistoryIntoSeparateStoreWithNewLocalKey` now includes a synthetic receiver account and acknowledgement and verifies recipient ID, count, timestamp and audit actor after restore.
- Debug and instrumentation APK builds: passed.
- Android lint: passed, **0 errors / 189 warnings**.
- Full connected Android instrumentation and backend suites were not rerun for this slice.
- `git diff --check` is required before commit; no production or real shop data was used.

## Limits and remaining qualification

This is a local account association and self-reported count. It does not prove the recipient physically received the cash, establish independent identity assurance, resolve a variance, or provide cloud identity or a complete shift audit. The focused UI test verified that the pending action is rendered; command submission was tested through the transaction model, not by tapping through the count dialog. No physical device, real shop, independent security review, full emulator regression, or production deployment is qualified.

## Changed files

- `android/app/src/main/java/com/chaiandtapri/counter/CounterModel.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/CounterPersistence.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/CounterSnapshotIntegrity.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/LocalOwnerAuth.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/SqliteCounterPersistence.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/StaffShiftsActivity.kt`
- `android/app/src/main/java/com/chaiandtapri/counter/SyncOutboxPayload.kt`
- `android/app/src/test/java/com/chaiandtapri/counter/CounterModelTest.kt`
- `android/app/src/androidTest/java/com/chaiandtapri/counter/SqliteCounterPersistenceTest.kt`
- `PROJECT_PLAN.md`
- `specs/PROJECT-PROGRESS-AUDIT-2026-11-25.md`
- `specs/MASTER-IMPLEMENTATION-TRACKER.md`
