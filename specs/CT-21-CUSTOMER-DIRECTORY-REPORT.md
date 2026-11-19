# CT-21 Customer Directory — Profiles, Contact Consent and Search

**Date:** 30 October 2026  
**Status:** Partial encrypted local directory with profile creation and account-ID/name search. Not a complete CRM.

## Delivered

- Added a search field to the encrypted local credit directory.
- Search filters persisted account IDs and profile names case-insensitively as the operator types, without reopening the database or mutating records.
- Added a profile form for an account ID, customer name and optional phone number. Existing credit account IDs can be linked to a newly created profile.
- Phone storage is optional and blocked unless the operator records that the customer agreed to store it for account-related contact. The UI discloses that no marketing messages are sent. The directory masks stored phone numbers; no messaging integration exists.
- Profile writes use SQLCipher schema v17, a v16→v17 additive migration, a write-barrier permit, one SQLite transaction, a command receipt, an audit event and a pending outbox row. Duplicate profile IDs are rejected; an identical command cannot be posted twice.
- Audit/outbox metadata uses the generated command ID and records whether a phone was stored and consent was asserted; it does not contain the customer ID, name, or phone.
- Profiles are included in the encrypted database snapshot, backup and restore. Migration tests confirm prior order and credit values remain unchanged.
- Each account remains linked to CT-22 Customer Details and the existing repayment workflow.
- Each account remains linked to CT-22 Customer Details and the existing repayment workflow.

## Verification

- API 37 tests cover UI profile creation with consent, contact masking, profile persistence across business mutations, audit redaction, duplicate rejection, no-consent rejection, account-ID/name filtering, schema v16→v17 migration and preservation of financial records, and profile retention in portable backup restore.
- Latest complete run: Node 49/49, Kotlin JVM 31/31, Android instrumentation 42/42 (122 total); debug/instrumentation APK tasks PASS; lint 0 errors / 108 warnings.
- Evidence: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`.

## Remaining work

Profile deletion, duplicate matching across different account IDs, profile-based recent activity, consent evidence/retention controls, and customer contact messaging remain unimplemented. Consent is an operator-recorded assertion, not independently verified evidence. Only synthetic data was used.

## Superseding profile maintenance — 30 October 2026

Customer profiles can now be edited from the directory. Revoking contact consent clears the stored phone, preserves `created_at`, advances `updated_at`, and leaves credit/sales unchanged. The update uses the write barrier and one SQLite transaction, then appends a command receipt, redacted audit event, and outbox row. Audit metadata excludes customer ID, name, and phone. The API 37 UI test creates a profile and revokes contact; persistence coverage checks redaction and unchanged credit.

Latest complete verification: Node 49/49, Kotlin JVM 31/31, Android instrumentation 43/43 (123 total), APK build tasks PASS, lint 0 errors / 109 warnings. Profile deletion, duplicate matching, consent evidence/retention controls, dated activity, and messaging remain unimplemented. Consent remains an operator-recorded assertion. Only synthetic data was used.
