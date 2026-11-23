# OW-09 Staff Membership Administration — 24 November 2026

## Scope delivered

The owner dashboard now supports issuing and revoking manager/cashier invitations, listing memberships and invitation states, changing staff roles, removing a member (including revoking their server sessions), and viewing append-only membership-event metadata. Invitations use a random one-time secret, store only its SHA-256 hash, expire after 24 hours, and are limited to manager/cashier roles. Accepting an invitation creates a session and invited device; an existing account must prove its password. The new PostgreSQL migration applies forced shop-scoped row-level security to membership, invitation and audit access.

## Verification evidence

- Isolated PostgreSQL-backed backend suite, using a restricted runtime API role: **32 passed, 0 failed, 0 skipped**.
- Browser prototype suite: **52 passed, 0 failed**.
- Backend and owner-web JavaScript syntax checks passed.
- `git diff --check` passed.
- Backend test output: [`postgres-backend-suite.log`](../test-evidence/2026-11-24/ow09-staff/postgres-backend-suite.log).

The backend suite exercises owner/staff authorization, invitation expiry/one-time acceptance behavior, role changes and removal/session revocation, append-only audit behavior, and shop-boundary isolation. Dashboard checks are source/API tests; no browser visual review was run.

## Limits and follow-up

- Invitation email ownership is not verified, and no email/SMS is delivered. The owner must securely give the one-time secret to the invitee.
- Invitation acceptance has an API but no Android join-invitation screen.
- Manager/cashier roles currently protect owner-only dashboard/member-administration routes; they are not yet enforced against every synced business command.
- Server accounts are not linked to local Android PIN identities. Business event actor attribution is therefore not established by this slice.
- Browser visual/usability qualification, independent security review, and production deployment remain separate gates.

OW-09 is a partial owner membership workflow, not complete staff authorization or attendance/payroll. Next, connect accepted server identities to device-side access and apply explicit role permissions at each business-command boundary, with tests proving denied operations cannot mutate records.
