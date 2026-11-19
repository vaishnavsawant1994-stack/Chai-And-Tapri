# CAT-050B — Partial Refunds, Ledger Reconciliation and Transaction Parity

**Updated:** 29 October 2026  
**Status:** Implemented and tested for the selected prototype contract. Exhaustive parity and production accounting are not claimed.

## Implemented in this milestone

- Both the JavaScript and Kotlin engines support partial item refunds and amount-only refunds. A return is limited to delivered, unrefunded value; item quantities cannot exceed delivered, unrefunded units.
- Refund allocation is deterministic: reduce outstanding customer credit first, then reverse collected cash, then reduce the still-uncollected sale balance. Amount-only adjustments do not change inventory.
- Cash reversals are labelled staff-declared and require explicit confirmation. UPI pending is never represented as collected or verified. A pending attempt is cancelled through a new ledger entry, linked to the original attempt; it is not deleted or rewritten.
- Credit reversals decrease outstanding receivables and append negative reversal entries. Repayments already received are treated as collected funds when allocating a later refund.
- Item returns require an explicit disposition. Restocking is allowed only when the product is configured returnable; the Kotlin default is no restock. Consumed/non-returnable goods are not restored automatically.
- Partial handover followed by cancellation leaves delivered items posted and permits settlement/refund of the delivered portion. Revision checks and command idempotency apply to refund mutations. Duplicate retries are checked before item validation.
- SQLite schema v5 stores refunded quantities, refund totals, credit reversals, and the relationship between a cancelled UPI attempt and its original pending entry. Additive migration paths v1→v5 preserve existing rows.
- Shared fixture file `specs/fixtures/refund-parity.tsv` is executed by both engines and checks totals, collected cash, outstanding credit, customer balance, refund amount, payment state, stock deltas, reversal amounts, and receivable reversal. This is selected fixture parity, not a shared runtime or exhaustive differential harness.
- Android instrumentation covers partial item refund with settled credit, restart recovery, stock restoration, payment/credit ledger persistence, UPI cancellation linkage, v1→v5 migration, duplicate retries and atomic rollback.

## Verification

| Check | Result |
|---|---|
| Node test suite | PASS — 48/48 |
| Kotlin JVM tests | PASS — 22/22 |
| Android emulator instrumentation | PASS — 3/3 test methods, 0 failures |
| Debug APK and instrumentation APK builds | PASS |
| Android lint | PASS — 0 errors, 30 warnings |

Evidence paths:

- `prototype/transaction-engine.test.cjs`
- `android/app/build/test-results/testDebugUnitTest/TEST-com.chaiandtapri.counter.CounterModelTest.xml`
- `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`
- `android/app/build/reports/lint-results-debug.txt`
- `specs/fixtures/refund-parity.tsv`

## Policy and accounting boundaries

- Refund allocation uses the current prototype order projection and customer receivable ledger. It is not a general payment-allocation engine for arbitrary bank settlements or provider-verified UPI refunds.
- The configured returnability catalogue is an injected policy in this prototype; there is no owner-facing product-policy editor yet.
- Refunds do not include tax, discount, invoice, GST, or accounting-period treatment. Partial returns and amount-only corrections require a reason, but approval roles are not yet implemented.
- UPI attempts can be cancelled locally only while unverified/pending. Provider settlement, asynchronous success after cancellation, chargebacks, and external refunds require a future payment-provider integration and reconciliation policy.
- JavaScript and Kotlin remain separately implemented. The shared fixtures cover a finite scenario set; untested command permutations and error semantics may still differ.

## Remaining qualification gaps

- Encrypted offline storage, Android Keystore key lifecycle, backup/restore, corruption recovery and authenticated synchronization are not implemented or qualified.
- Physical Bluetooth microphone capture, genuine continuous listening, noisy-counter speech trials, real-shop pilots and CAT-046 browser visual review remain pending.
- No production-readiness, exhaustive accounting, or external payment verification claim follows from these prototype tests.

## CAT-051 preparation

Next implementation requirements are in [CAT-051 Secure Offline Storage and Recovery](CAT-051-SECURE-OFFLINE-REQUIREMENTS.md). Start with a threat model and library/key lifecycle decision, then prove migration and recovery behavior using disposable synthetic stores before claiming protected production storage.
