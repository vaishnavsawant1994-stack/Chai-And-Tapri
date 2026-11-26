# OW-14 Branch Management — 26 November 2026

## Delivered slice

Added business-level owner membership and a branch directory/create/revoke API with an owner-web interface. Branch creation atomically creates a shop, owner shop membership, device identity, and branch audit record. The owner directory shows branch identity, server revision, creation time, active/revoked device IDs and whether the current session belongs to it. A branch requires a separate login using the existing owner account plus its shop/device IDs; the dashboard does not silently switch branches. Device IDs are identifiers, not credentials. The owner can revoke a lost device; the backend refuses self-revocation, revokes active sessions on the target device and records a device-level audit event.

The endpoint returns branch identity only. It does not aggregate branch financial data or implement branch-to-branch transfers. Operational screens remain scoped to the currently authenticated shop. Device IDs are identifiers rather than substitutes for authentication; access still requires the owner credentials and a valid branch device identity.

Migration `005_business_branch_ownership.sql` adds `business_memberships`, backfills owner principals from existing owner shop memberships, and adds an actor-attributed branch audit table. Migration `006_business_device_revocation.sql` adds device-revocation audit records. RLS scopes business membership to both the transaction-local business and user. The runtime role can update only the device `revoked_at` field in addition to the required `SELECT`/`INSERT` operations.

## Qualification

- Disposable PostgreSQL 17 with migrations `001`–`006` and restricted runtime role: backend suite **45/45 passed**, 0 skipped.
- Integration coverage: owner-only authorization, validation failure, transactional shop/membership/device/audit creation, separate branch owner login, self-revocation protection, active-session revocation, revoked-device login denial, idempotent retry, active/revoked device state, business-directory/device isolation, RLS hiding another owner's principal, and authenticated route behavior.
- Owner dashboard HTTP/static asset tests verify the branch UI and endpoints; browser visual review was not run.
- `node --check` passed for changed JS modules; `git diff --check` passed.
- Other repository suites were not rerun for this dashboard/backend-only slice and retain their separately dated evidence.
- Ignored local test logs: `test-evidence/2026-11-26/ow14-device-revocation/backend-postgres-tests.log` and `prototype-tests.log`.

## Limits and follow-up

This is a local development branch directory/create/device-revocation slice, not full multi-branch management. Business owner setup assumes current registration creates the business and its first owner. Business-level owner invitations, branch rename/archive, consolidated financial reporting, cross-branch transfer, deployment controls, independent authorization review, and browser visual review remain pending. The backend container and accounts used for testing were synthetic and disposable.
