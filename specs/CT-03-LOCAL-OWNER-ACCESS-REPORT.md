# CT-03 — Local Owner Access Foundation

**Status: partial implementation; tested and manually exercised on API 37 emulator within the scope below.**

## Implemented

- First-run owner enrollment and subsequent local owner sign-in screens.
- Owner username normalization and minimum-length passphrase validation.
- PBKDF2-HMAC-SHA256 (600,000 iterations for new verifiers, random 16-byte salt, 256-bit verifier) in the SQLCipher-protected database; plaintext passphrases are not persisted. Existing weaker local verifiers are upgraded after successful authentication.
- Failed-attempt counter and 60-second lock after five failures; successful login resets the counter.
- In-memory-only owner session and an explicit Lock action. Process death clears the session.
- Additive SQLite v14→v15 migration creates `local_owner_credentials` without deleting shop records.
- Main counter and business screens require an owner session; Main is no longer exported to other applications.
- A recovery replacement clears the current owner session; the restored database must pass through local owner access again.
- Authentication audit events record enrollment and login result without writing credentials to the sync outbox.

## Not implemented or qualified

- This is a single-device owner gate, not online identity, staff accounts, role-based permissions, multi-user attribution, or server authentication.
- No idle timeout/biometric reauthentication, passphrase reset, second-owner recovery, staff PIN, or sensitive-action step-up flow.
- The verifier work-factor upgrade path for an account created by the earlier 210,000-iteration prototype is implemented but has not been separately exercised; new-enrollment and login behavior at 600,000 iterations is covered.
- Recovery remains available before local sign-in for lost-key/device recovery; backup recovery secret and authenticated shop identity remain the recovery controls.
- No independent security review or production-hardening assessment has occurred.
- Automated tests do not substitute for independent security review. Process-death behavior is based on in-memory session state and has not been separately qualified as a full lifecycle/idle-lock policy.

## Verification

API 37 instrumentation: 31/31 passed. Coverage includes owner enrollment, 600,000-iteration verifier storage, incorrect-secret rejection, persistent lockout, successful unlock after the delay, session clearing, and additive v8→v15 migration with existing-order preservation. Full current suites: Node 48/48; Kotlin JVM 28/28; Android instrumentation 31/31. Debug and instrumentation APK build tasks passed. Android lint: 0 errors, 103 warnings. Synthetic UI smoke manually covered enrollment, counter entry, explicit lock and re-login. UI evidence: `../test-evidence/2026-10-30/ct03-owner-enrollment.png`, `ct03-owner-session.png`, `ct03-owner-relogin.png`, plus corresponding hierarchy XML.

## Current CT-03 continuation — 2 November 2026

This report documents the owner-access subflow and its 31-test historical qualification only. Local cashier/manager credentials, schema v24, role-gated screens, account enable/disable and cashier sign-in UI are tracked separately in [CT-03 staff access report](CT-03-STAFF-ACCESS-REPORT.md). Latest current-source full Android suite: 83/83; an additional focused staff sign-in UI test: 1/1. Owner staff-account management UI, full transaction actor attribution, idle timeout, server identity and independent security review remain open.
