# CAT-052 Local Mutation Role Enforcement

Date: 24 November 2026

## Change

Added a defense-in-depth authorization check at the encrypted SQLite commit boundary. For authenticated app sessions, the actor on the pending audit event must match the currently active local staff/owner ID; a supplied different or missing actor is rejected. Cashier commits are refused for refunds, order cancellations, expenses, procurement/supplier changes, cash drawer and closing operations, customer-credit repayments, and physical stock counts. Quick sales, ordinary order/payment workflow and the existing cashier opening-stock confirmation remain permitted. Managers and owners retain the broader operational set; existing direct owner/manager database guards for catalogue/settings/customer-profile administration remain in place.

The audit event must be bound to the command ID before an authenticated business commit is accepted. This prevents a caller from omitting the event prefix to evade the role classification. The policy is deliberately applied at persistence commit, not only at screen visibility.

## Verification

- Android JVM: **81 passed, 0 failed**.
- Debug APK and instrumentation APK build tasks: **BUILD SUCCESSFUL**.
- API 37 emulator, focused `SqliteCounterPersistenceTest#businessAuditRecordsPersistAuthenticatedActorAcrossEncryptedReopen`: **1 passed, 0 failed**. It verifies that a cashier's attempted order cancellation, expense and refund are rejected with no ledger/order mutation while an allowed quick sale/opening count still records the correct actor; manager corrections remain allowed.
- Android lint: **0 errors, 182 warnings**.

## Limits

- This is local-device authorization, not a complete role/permission matrix and not a production security boundary against a modified client or compromised device.
- No-session fixtures intentionally bypass local role enforcement; authenticated app screens require local access before entering business workflows. Review every new mutation path when added.
- Cloud backend membership checks remain authoritative for uploaded projections; they cannot prevent an offline local action before sync.
- The full unfiltered emulator instrumentation suite previously stalled and remains unqualified. Live staff invitation acceptance, cross-device recovery, Bluetooth hardware/field tests, independent security review and production deployment remain open.

## Next executable work

Test the CAT-052 staff invitation/link/manual-sync/revoke workflow against a disposable PostgreSQL instance from the Android Emulator. Expand the permission matrix around cash drawer/shift ownership, refunds and role changes, and update the product's role specification before broadening permissions.
