# CT-03 — Local Staff Identity and Role Access (Prototype)

**Date:** 2 November 2026  
**Status:** Prototype implemented. Owner/staff sign-in and account-management UI passed API 37 tests. Audited persistence writes and shared business-command rows now store the authenticated local actor where available; full permission and employee-accountability qualification remains open.

## Delivered

- SQLCipher schema v24 adds an additive `staff_credentials` table; v23 shop data is preserved. Schema v25 adds nullable `audit_events.actor_id`; v24 records are preserved and migrate with a null actor.
- Owner-only staff management creates named cashier/manager accounts and enables/disables accounts.
- Staff IDs are normalized; six-digit PINs are salted and stored as PBKDF2-HMAC-SHA256 verifiers (600,000 iterations). PIN text and derived secret buffers are cleared after use.
- Five failed PINs cause a persisted 60-second lock. Login attempts and account administration create audit events that contain no PIN or verifier.
- Cashier sessions can enter Quick Sale, Live Orders, Payments and Order Details. Managers also pass the explicitly manager-gated operational/report screens. Product pricing, owner access, staff management, voice settings and backup/recovery remain owner-only.
- Staff credentials reside in the encrypted local database and are included in the already-encrypted portable database snapshot; recovery still requires the separately held backup secret.
- `CounterModel.commitMutation` passes the session actor ID into the same SQL transaction that commits the business state and audit row. A cashier sale attribution survives encrypted database reopen. No display name, PIN, salt or verifier is included in that actor field.

## Limits and security notes

This is device-local authentication, not a cloud identity provider or multi-device authorization system. The current prototype has no idle session timeout, server-side revocation, PIN reset workflow, or separate device enrollment. Manager/cashier permissions are broad screen gates; high-risk actions inside a manager-accessible screen do not yet have per-action approval rules. Audited local persistence writes use the authenticated actor ID where available; failed login attempts and legacy rows remain null by design. Tests exercise selected owner, staff profile/catalogue and business-command paths, not every event type. Background writes may have a null actor. Disabling an account prevents later authentication; it does not revoke a session already active elsewhere.

Do not use real employee PINs in development. Independent security review remains open. This report does not qualify CAT-051B backup/restore end-to-end or production readiness.

## Verification

The API 37 tests cover synthetic account creation/authentication/disable, owner authentication attribution, product/shop-profile actor attribution, transaction actor persistence across encrypted reopen, and schema migration. The final full v25 API 37 run passed 87/87. Node 52/52, Kotlin JVM 56/56; backend 11 passed / 4 PostgreSQL-dependent skipped; APK builds passed and lint 0 errors / 161 warnings. Full JUnit XML: `../test-evidence/2026-11-02/ct03-actor-attribution-full-instrumentation-final.xml`. Earlier focused staff UI evidence: `../test-evidence/2026-11-02/ct03-staff-login-ui-focused.xml` and `ct03-staff-management-ui-focused.xml`. See `../test-evidence/2026-11-02/ct03-actor-attribution-verification.md` for detailed run accounting.

## Staff account actor assertions - 2 November 2026

The staff authentication test now checks account creation attribution to the owner, successful PIN authentication attribution to the staff ID, null attribution on failed login, and account disable attribution to the currently authenticated manager. Focused API 37 test passed 1/1. Final JUnit: `../test-evidence/2026-11-02/ct03-staff-account-actor-attribution-focused.xml`. This remains a prototype and does not qualify full role/action authorization, background actors, timeout/revocation, or server-side identity.

## Manager-gated customer profile persistence - 2 November 2026

Customer profile creation and update now enforce the same manager-or-owner policy at the SQLCipher persistence boundary as the Android route gate. A cashier cannot create a customer record or change an existing profile/contact-consent record by calling the persistence-backed model directly. The API 37 actor test asserts both cashier rejections and records an allowed manager profile creation under that manager's actor ID. This is selected-path authorization coverage only; it does not establish comprehensive action authorization or contact-data privacy qualification. Current full suite: Node 52/52, Kotlin JVM 56/56, Android API 37 instrumentation 87/87, backend 11 passed / 4 PostgreSQL-dependent skipped; APK builds PASS; lint 0 errors / 161 warnings. Evidence: `../test-evidence/2026-11-02/ct03-manager-customer-profile-full-instrumentation.xml` and `../test-evidence/2026-11-02/ct03-manager-customer-profile-full-gradle.log`.
