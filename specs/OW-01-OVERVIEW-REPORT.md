# OW-01 — Business Overview

**Updated:** 1 November 2026  
**Status:** Initial authenticated web/API slice implemented and backend-integration-tested. Browser visual review, live Android synchronization, and production access controls remain unqualified.

## Delivered

- Added a same-origin `/owner/` web screen served by the local sync API. It has an accessible sign-in form, responsive Warm Tea styling, refresh and sign-out controls, empty/error states, and no fabricated business data.
- The owner signs in with the registered email/password and shop/device IDs. The Android owner-only Cloud Sync screen now displays those non-secret IDs as selectable text; the bearer token is never displayed.
- The dashboard token remains in page memory only; it is not written to local or session storage. Sign-out requests server revocation and clears the in-memory session even if the request cannot be confirmed.
- Added `GET /v1/dashboard/overview?shopId=...`. The service requires an authenticated owner session, checks the session-bound shop/business, uses shop-scoped RLS, and reads accepted immutable sync events only.
- The summary derives the latest accepted order state by order number, delivered item value after returned quantities, cash collections and cash refunds, staff-declared UPI receipts, uncancelled UPI pending attempts, tracked product count, unknown-stock count, distinct movement count, server revision, and event freshness.
- All money is integer paise on the API and formatted as INR in the browser. Arithmetic overflow fails with a structured 422 response.
- Every figure is accompanied by scope/evidence notes. The dashboard distinguishes cash collected from cash refunds, staff-declared UPI from pending UPI, excludes customer-linked credit because it is blocked by the current sync contract, and does not claim profit or provider verification.
- Owner assets use a strict same-origin Content Security Policy, no-store cache headers, `nosniff`, and no third-party scripts or fonts.
- The short `/owner` path returns a same-origin permanent redirect to `/owner/`; it does not accept an external redirect target.

## Verification

- Backend test suite on a fresh isolated PostgreSQL 17 cluster: **13/13 passed, 0 failed, 0 skipped**. This includes API enrollment, accepted sync, order/payment projection summary, wrong-shop denial, logout revocation, RLS tenant isolation, runtime-role checks, and `/owner` redirect behavior. Latest rerun evidence: `../test-evidence/2026-11-01/cat052-owner-route-backend.log`.
- Backend and web JavaScript syntax checks passed.
- Prototype Node suite: **52/52 passed**.
- Android Kotlin JVM suite: **48/48 passed**.
- Debug APK and instrumentation APK build tasks passed; Android lint reported **0 errors / 141 warnings**.
- The Android app displayed the identifiers in source, but the updated activity was not opened in the emulator. `adb devices -l` had no connected device. The current UI has no emulator/runtime qualification.
- Evidence: `test-evidence/2026-11-01/cat052-owner-overview-integration.log`, `cat052-owner-overview-postgres.log`, `full-node-after-owner-overview.log`, and `owner-overview-android-gradle.log`.

## Limits and next work

- The server stores an immutable journal, not a complete authoritative accounting projection. This overview only summarizes the subset of accepted `projection_delta.v1` data; it does not represent offline, blocked, customer-credit, supplier, expense, or shift records.
- Delivered item value is calculated from accepted line prices and fulfillment/refund quantities; it is not net accounting revenue, cashflow, margin, or profit.
- UPI values are staff-declared/pending only. No provider events are integrated.
- There is no external browser visual/usability review, multi-shop user interface, role administration, branch filtering, date filtering, server-side financial reconciliation, or production deployment.
- The backend remains a local prototype: registration is open, the default bind is loopback, operational controls and independent security review are incomplete.

OW-01 is a first real-data vertical slice, not completion of the 20-screen owner dashboard or Wave 4.
