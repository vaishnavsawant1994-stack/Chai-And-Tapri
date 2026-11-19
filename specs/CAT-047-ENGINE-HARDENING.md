# CAT-047 — Transaction Engine Hardening

**Status:** Prototype invariants expanded and tested; production qualification remains open.  
**Related contract:** [Transaction states](TRANSACTION-STATES.md)  
**Test suites:** `prototype/transaction-engine.test.cjs`, `prototype/voice-counter.test.cjs`

## Verification run

Command:

```text
node --check prototype/app.js
node --check prototype/transaction-engine.js
node --check prototype/voice-counter.js
node --test prototype/transaction-engine.test.cjs prototype/voice-counter.test.cjs
```

Result on 28 October 2026: **27 passed, 0 failed**; JavaScript syntax checks passed.

## Invariants exercised

- Same idempotency key returns the prior command result without repeating payment or stock effects.
- Catalogue prices are authoritative; monetary values are integer paise.
- UPI pending is not a collection or provider verification.
- Udhar creates a customer receivable; cash repayments allocate against outstanding receivables.
- Partial cash collections reconcile to the total; overpayment and unsupported tender mixing are rejected.
- Partial handover posts only handed-over quantities. Final handover posts the remainder once.
- Two open orders can compete for stock; the second handover fails atomically if the first consumed the last unit.
- Invalid fulfillment transitions and post-handover line changes do not mutate state.
- Local persistence failure leaves order, sale, payment and stock state unchanged.
- Correction/void events retain the original record, require reason and correct cash-refund confirmation, and distinguish restock disposition.
- Reconstructing the engine from a saved snapshot preserves orders, command receipts, stock, and recovery behavior.

## Not covered by this prototype

- Split tenders or mixing cash and credit for one order.
- Provider-verified UPI, payment attempt resolution, or settlement import.
- Partial refunds, partial cancellation after handover, or high-volume cash drawer reconciliation.
- Concurrent writes from multiple devices, server authorization, durable server sync, encryption, account isolation, or backup restoration on a replacement Android device.
- Real UI interactions in a browser, real audio, Android lifecycle behavior, Bluetooth routing, noise accuracy, or shopkeeper usability.

The engine is a deterministic browser/Node prototype. Passing these tests does not make browser local storage safe for real financial or inventory records. Production release still requires a durable encrypted store, backend authorization, domain-specific sync conflict resolution, migration/restore testing, security review, and Android/device qualification.

## Latest combined Node verification

On 28 October 2026, the current focused combined browser/parser/transaction/trial suite ran as:

```text
node --test prototype/speech-input.test.cjs prototype/speech-trial-log.test.cjs prototype/transaction-engine.test.cjs prototype/voice-counter.test.cjs
```

Result: **42 passed, 0 failed**. CAT-047's earlier scoped 27-case run remains historical scoped coverage; do not add the two counts together.

## 29 October 2026 recheck

At the 29 October recheck, automated results were Node **43/43** and Kotlin JVM **17/17**. The Kotlin SQLite persistence/idempotency/migration instrumentation scenario ran on the API 37 emulator and passed **1/1**. These results demonstrate the current prototype's tested scope only; they do not establish complete Kotlin/JavaScript behavioral parity. Native Kotlin remains a limited subset. Split tenders, returns/refunds and reversals, sync conflict handling, and production database recovery remain open.

## Native payment ledger and migration update — 29 October 2026

The Kotlin snapshot now carries append-only payment entries for cash declarations, UPI-pending attempts, credit-sale receivables, and customer cash repayments. SQLite schema v2 adds `payment_entries`; the additive v1-to-v2 migration was exercised against a seeded legacy store and retained its existing order. Latest Kotlin JVM suite: 17/17 passed. The API 37 instrumentation scenario passed 1/1 and checks payment-event persistence. This closes only this narrow ledger/migration gap; transaction parity is still incomplete and local SQLite remains unencrypted with no sync worker.
