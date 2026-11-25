# CT-10 cash plus observed UPI split — 26 November 2026

## Delivered

The native Payment screen now supports one atomic split of an outstanding order balance between physical cash and UPI observed by staff. The operator enters the cash amount, reviews a confirmation that identifies the UPI remainder, and explicitly confirms the entry. The business engine posts two append-only payment events under one command and one persistence transaction. Cash is staff-declared and linked to the active drawer when one exists; UPI is also staff-declared and is never marked provider-verified. Existing UPI-pending attempts must be resolved through their separate follow-up flow.

The command validates that the order is finished and has a positive due, the cash portion is positive and less than the full due, no pending UPI state exists, and the supplied order revision is current. Duplicate command IDs are ignored. A failed persistence commit restores the prior order, payment history, and audit state.

## Verification

- Kotlin JVM: **102/102 passed** across 14 suites, including new settlement/idempotency and invalid amount, stale revision, pending-UPI conflict, and rollback cases.
- API 37 emulator: focused Payment screen test **1/1 passed**. It created a synthetic ₹15 order, used the real screen to review and confirm ₹5 cash + ₹10 observed UPI, reopened the encrypted database, and verified a zero due, two staff-declared payment events, no pending/verified event, unchanged stock, and one audit record.
- `assembleDebug`: PASS.
- `assembleDebugAndroidTest`: PASS.
- `lintDebug`: PASS, **0 errors / 192 warnings**.
- Local ignored emulator log: `../test-evidence/2026-11-26/ct10-observed-cash-upi-split/focused-instrumentation.txt`.

During test development, a rollback assertion initially held a mutable order reference instead of a copy; the test was corrected to take a defensive copy. The first UI assertion also expected a dialog message as a title; it was corrected to assert the actual confirmation title. The implementation passed after those test-harness fixes.

## Scope limits

The UPI event represents the operator's observation only; there is no acquiring-bank or provider verification. This focused synthetic workflow does not establish broad Android regression, manual usability, staff authorization-policy completeness, physical payment integration, real-shop suitability, or release readiness. CT-10 and the six delivery waves remain partial.
