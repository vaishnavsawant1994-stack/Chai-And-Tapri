# CT-07 — Continuous Voice Counter Foundation

**Specification ID:** CAT-SCR-CT07  
**Status:** Typed-command prototype; not a microphone, Bluetooth, Android, or production voice implementation  
**Shared engine:** [CT-06 transaction engine](../prototype/transaction-engine.js)  
**State contract:** [Order and payment states](TRANSACTION-STATES.md)  
**Runnable prototype:** [Counter UI](../prototype/index.html)

## 1. Goal

Validate the order and payment state model with a typed command queue before adding speech recognition or Bluetooth. Every interpreted command becomes a structured command and is sent to the same deterministic engine used by CT-06 touch sales.

The preview explicitly has audio off. It does not listen to the counter or claim recognition accuracy.

## 2. Supported typed command subset

| Shopkeeper phrase | Structured command | Expected effect |
|---|---|---|
| “Naya order, do cutting chai, ek samosa” | `order.create` | Create a taken order with catalogue-priced line snapshots; no payment or stock movement |
| “Order 101 mein ek biscuit add” | `order.add_line` | Add a line to the referenced order before handover |
| “Order 101 preparing” | `order.set_fulfillment` | Set preparation state |
| “Order 101 ready” | `order.set_fulfillment` | Set ready state |
| “Order 101 de diya” | `order.set_fulfillment` | Mark given, recognize sale and consume configured product stock once |
| “Order 101 cash mila” | `order.record_payment` | Record staff-declared cash after handover |
| “Order 101 UPI pending” | `order.record_payment` | Create a pending UPI attempt with zero collected/verified amount |
| “Order 101 cancel” | `order.set_fulfillment` | Cancel before handover without posting sale or consuming stock |

This is intentionally a limited English/Hinglish text parser with digit order references and a small quantity vocabulary. Hindi/Marathi speech, Devanagari numbers, noise handling, accent support, wake words, and actual continuous listening are not implemented.

## 3. Order and payment rules

- Order fulfillment states: `taken → preparing → ready → partially_given → given`; cancellation is allowed before any handover.
- The shared engine also supports `partially_given` with an `order.handover_partial` command. It records delivered quantities and stock movements; that command is not exposed in the current typed parser/UI yet.
- Payment is independent. A given order can remain unpaid; UPI pending is not a collection; staff-declared cash is not externally verified.
- Order creation snapshots catalogue names/prices but does not consume stock or report a sale.
- Handover validates current available stock and atomically creates one sale plus stock movements. If stock is insufficient, handover is rejected and the order remains open.
- Payment commands are valid only after handover. Cash can be staff-declared; Udhar needs a customer; UPI verification cannot be created without provider evidence.
- The shared engine supports `customer.record_repayment` for staff-declared cash, allocated against open customer receivables. It is not yet exposed in the counter UI or typed voice phrase catalogue.
- A pending UPI attempt blocks another collection in this prototype until it is resolved or cancelled, avoiding silent double collection.
- Retried command IDs return the original result. A new action gets a new ID. Financial and stock corrections append events.
- No inventory is reserved when an order is taken. Multiple offline or open orders can exceed available stock; the later handover conflict remains visible.

## 4. Ambiguity behavior

- No order number for a mutation: ask which order; no command is committed.
- Unknown order number or product: explain the mismatch; no state changes.
- Unsupported or ambiguous phrase: show that no change was made.
- “UPI verified” without provider evidence: reject and offer only the pending state.
- Engine rejects an invalid lifecycle transition, insufficient stock, invalid payment, or permission/persistence failure; the queue remains based on committed engine state.

## 5. Prototype acceptance checks

1. Create two simultaneous orders; each receives a distinct, stable number.
2. Add a product to one numbered order; the other order is unchanged.
3. Mark an order ready and given; only handover creates the sale and stock movement.
4. Retry a handover command with the same idempotency key; no duplicate sale or stock movement.
5. Record cash after handover; order fulfillment stays given and payment becomes staff-declared.
6. Record UPI pending; collection remains zero and payment remains unverified.
7. Try to collect cash while an unresolved UPI attempt exists; engine rejects the second collection.
8. Speak/type an unnumbered correction while multiple orders exist; parser requests clarification.
9. Attempt to mark a non-existent order given or an order cancelled after handover; parser/engine rejects without mutation.
10. Restart/reconstruct the engine from its persisted snapshot; open orders and command receipts remain available.

Automated engine/parser tests cover the applicable cases. Visual inspection and real counter usability still require an authorized preview environment and shopkeeper sessions.

## 6. Later voice and headset work

Only after typed queue flows validate:

- Benchmark on-device/cloud/hybrid ASR for target devices and local language mix.
- Prototype Android foreground-session and Bluetooth microphone routing on representative low-cost devices.
- Add visible user-started listening, pause/stop, command boundaries, short confirmations, and touch fallback.
- Validate nearby customer speech, noisy stalls, interruption, headset disconnect, lock screen, battery, offline behavior, and consent/privacy.
- Preserve the structured command contract and engine boundary; recognition never directly posts money or inventory.

## Native prototype status (28 October 2026)

The native Kotlin app now builds a debug APK and has a separate single-utterance Android speech adapter. Recognition is user-started, transcript-reviewed, and manually re-armed; it is not continuous Bluetooth Mode B2. A Bluetooth communication output route may be selected, but captured microphone source is not proven without device testing. The app's local transaction model is an in-memory subset, not the browser engine; it has no durable storage or sync. See [CAT-049 implementation status](CAT-049-ANDROID-BLUETOOTH-SPIKE.md) and [CAT-049A build status](CAT-049A-ANDROID-BUILD-STATUS.md). The workspace's clean build passes 9 Kotlin tests and 42 Node tests. No Android device was available for installation or runtime qualification.

## Runtime evidence — 29 October 2026

On the API 37 emulator, reviewed demo commands created an order, advanced it to ready and given, then recorded staff-declared cash. A force-stop/relaunch preserved the order and payment state in SQLite. Rejecting a new proposed order did not create a record. Mic permission denial preserved typed fallback. This verifies only the emulator demo flow; real speech recognition accuracy, real Bluetooth input, browser visual qualification, and physical shop behavior remain untested.
