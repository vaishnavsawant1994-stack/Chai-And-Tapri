# OW-09B — Role-Scoped Sync and Submitter Audit

**Date:** 24 November 2026

**Status:** Implemented and integration-tested for the current projection contract; not production-qualified.

## Delivered

- Sync authorization uses the role returned by server authentication and membership lookup; the client cannot choose its own role.
- Owner and manager sessions can submit all currently supported projection families. Owner-only dashboard and membership-administration routes remain owner-only.
- Cashier sync is limited to sales, collections, customer credit/repayment, and shift summaries. It rejects expense and procurement/supplier-finance projections, refunds and voids, positive/manual stock changes, and sale-stock changes that do not reconcile to a known server stock quantity and the order's delivered-quantity delta.
- Cash collection and new-credit amounts must match the corresponding order balance changes. Existing cashier-edited order line identities, quantities and prices cannot be changed; credit reversal balances cannot be changed by cashiers.
- Migration `004_sync_actor_attribution.sql` adds nullable actor user/role columns to accepted sync events. New accepted events record the authenticated submitter. Owner accepted-event history shows account display name and role; historical events remain unattributed.

## Verification

- Fresh disposable PostgreSQL 17 database, all numbered migrations applied, and restricted `chai_tapri_api` runtime role: backend suite **33 passed, 0 failed, 0 skipped**.
- The integration scenario verifies cashier expense denial without revision advance, cashier sale acceptance against owner-seeded stock, manager expense acceptance after promotion, current role lookup, and owner audit attribution for both cashier and manager.
- Unit coverage rejects mismatched stock, excessive stock deduction, manual stock changes, cashier refunds, expense/procurement writes and order price edits.
- Prototype suite: **52 passed, 0 failed**.
- JavaScript syntax and `git diff --check` passed.
- Backend log: [`backend-postgres-suite.log`](../test-evidence/2026-11-24/ow09b-role-sync/backend-postgres-suite.log) (local ignored test evidence).

## Security and product limits

- This policy protects writes that reach the authenticated sync API. Android offline actions are not linked to the authenticated cloud account; local PIN roles and cloud roles are not yet unified, and the app has no invitation acceptance flow.
- The server has no synchronized authoritative product catalogue. It cannot validate a cashier's newly created order price against an owner-approved product price.
- The sync journal stores declared projections; the backend does not execute every business command against canonical sales/inventory ledgers. Submitter attribution identifies the authenticated account that uploaded a record, not necessarily the person who created an offline action on a shared device.
- Managers can submit all current business projection types. Finer permissions and manager approval policies remain to be defined and tested.
- No Android app end-to-end with a staff cloud login, browser visual qualification, independent security review, or production deployment was performed.

## Follow-up — local operator outbox scoping

The Android owner sync path now obtains local actor IDs from the audit journal and submits only contiguous outbox events belonging to the locally bound operator. A different or unattributed queue head is left untouched and reported; events are never skipped or uploaded under the owner's token. Owner login/registration binds the encrypted session to `local-owner`; legacy sessions without a binding require owner sign-in again. This closes the immediate owner-token/cashier-event misattribution path, but is not staff cloud identity linking. Staff invitation acceptance and per-staff sessions are still absent, so those queued events remain blocked. See [CAT-052 local operator outbox scoping](CAT-052-LOCAL-OPERATOR-OUTBOX-SCOPING-REPORT-2026-11-24.md).

OW-09B is a server-side authorization improvement, not a complete employee identity, business-command, or attendance system. Continue by linking cloud staff accounts to local operator sessions with a reviewed offline authorization policy, then enforce per-command permissions at the point of local mutation and preserve actor attribution across sync.
