# OW-14 Branch Management — 26 November 2026

## Delivered slice

Added business-level owner membership and branch/device lifecycle APIs with an owner-web interface. Branch creation atomically creates a shop, owner shop membership, device identity, and branch audit record. The owner directory shows branch identity, server revision, creation time, active/revoked device IDs and whether the current session belongs to it. Owners can rename a branch with an expected-name stale-edit guard; the append-only audit stores the previous and new names. A branch requires separate login using the existing owner account plus its shop/device IDs; the dashboard does not silently switch branches. Device IDs are identifiers, not credentials. The owner can register a replacement device or revoke a lost one; self-revocation is rejected, active target sessions are revoked, and device-level audit events are appended.

The endpoint returns branch identity only. It does not aggregate branch financial data or implement branch-to-branch transfers. Operational screens remain scoped to the currently authenticated shop. Device IDs are identifiers rather than substitutes for authentication; access still requires the owner credentials and a valid branch device identity.

Migration `005_business_branch_ownership.sql` adds `business_memberships`, backfills owner principals from existing owner shop memberships, and adds an actor-attributed branch audit table. Migration `006_business_device_revocation.sql` adds revocation audit records; migration `007_business_device_registration.sql` adds replacement-device enrollment events; migration `008_business_branch_rename.sql` adds structured branch audit details and the rename event. RLS scopes business membership to both the transaction-local business and user. For branch operations, the restricted runtime grants permit updates to `shops.display_name` (alongside the existing `server_revision` update), device `revoked_at`, and inserts of device identities; service checks constrain branch writes to the authenticated business.

## Qualification

- Device lifecycle continuation: disposable PostgreSQL 17 with migrations `001`–`007` and restricted runtime role: backend suite **45/45 passed**, 0 skipped. GitHub CI also passed in [run 45753216781](https://github.com/vaishnavsawant1994-stack/Chai-And-Tapri/actions/runs/45753216781).
- Current branch-rename continuation: disposable PostgreSQL 17 with migrations `001`–`008` and restricted runtime role: backend suite **45/45 passed**, 0 skipped. It validates owner-only rename, cross-business denial, stale-name conflict, no-op rename, and old/new-name audit metadata. Test log: `../test-evidence/2026-11-26/ow14-branch-rename/backend-postgres-tests.log`.
- Integration coverage: owner-only authorization, validation failure, transactional shop/membership/device/audit creation, separate branch owner login, stale rename protection, self-revocation protection, active-session revocation, revoked-device login denial, idempotent retry, replacement-device registration/login, active/revoked device state, business-directory/device isolation, RLS hiding another owner's principal, and authenticated route behavior.
- Owner dashboard HTTP/static asset tests verify the branch UI and endpoints; browser visual review was not run.
- `node --check` passed for changed JS modules; `git diff --check` passed.
- Other repository suites were not rerun for this dashboard/backend-only slice and retain their separately dated evidence.

## Limits and follow-up

This is a local development branch/device lifecycle slice, not full multi-branch management. Business owner setup assumes current registration creates the business and its first owner. Business-level owner invitations, branch archive, consolidated financial reporting, cross-branch transfer, deployment controls, independent authorization review, and browser visual review remain pending. The backend container and accounts used for testing were synthetic and disposable. Rename changes metadata only; it does not alter sync revisions or move financial records.
