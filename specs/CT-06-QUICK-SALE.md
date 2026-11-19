# CT-06 — Quick Sale

**Specification ID:** CAT-SCR-CT06  
**Status:** Detailed product/engineering draft plus browser interaction prototype; no production Android implementation and not field validated  
**Parent specification:** CAT-ARCH-001 v1.1  
**Platform:** Android shopkeeper app  
**Primary users:** Owner, manager, cashier  
**Dependencies:** Shop/device identity, product catalogue, price versions, order engine, payment ledger, inventory ledger, local journal/sync, audit trail

**Design prototype:** [Responsive CT-06 interaction prototype](../prototype/index.html) (browser/local-storage demo; not the Android implementation).  
**Audit:** [CAT-043 functional audit](CAT-043-CT06-AUDIT.md).

## 1. Purpose and success criteria

Let a staff member record an ordinary walk-in sale with minimal interruption, on a low-cost Android phone and without internet. The same transaction engine must serve touch, scan, B1 quick voice, and B2 continuous voice inputs.

Success means:

- A configured common one-item sale can be recorded in one deliberate action when the shop has explicitly enabled a safe quick-sale preset; otherwise it takes a clear add-and-complete flow.
- No order, payment, or stock event is duplicated after a double tap, app restart, timeout, or sync retry.
- An order is not shown as paid solely because it was created or handed over.
- The shopkeeper can see whether a record is saved locally, synchronized, or needs attention.
- The operator can correct an error through an audited reversal/adjustment, without rewriting history.

Targets such as “two actions” remain pilot hypotheses; this spec defines behavior, not a claim that the target has been achieved.

## 2. Out of scope for this screen

- Full café table service or kitchen routing.
- Supplier purchasing, customer discovery, advanced accounting, and branch management.
- Unverified automatic UPI detection or provider settlement.
- Continuous listening; that belongs to CT-07 and may create a draft to be reviewed/committed through the same transaction service.
- Complex tax invoice creation until the business’s applicable tax configuration is defined.

## 3. Navigation and layout

Persistent app navigation: **Home · Sell · Orders · Stock · More**. CT-06 is reachable from Home in one tap. Returning from product setup or a permission/error screen must not discard an active draft.

### Main screen regions

1. **Top status strip:** shop/shift, local time, offline/sync state, and (if active) a small persistent voice-session indicator. Tapping sync state opens diagnostics; it never blocks sale entry.
2. **Category/filter row:** favourites by default, then configured categories; local search is available but not required for common items.
3. **Product grid:** large tiles show short name, selling price, and optional availability cue. Product tile is a real button with accessible label, sufficient touch target, and clear pressed/disabled state.
4. **Current order/cart:** line name, quantity, unit price, line total, remove/decrease/increase controls, order total, and payment state. It can collapse to preserve product space.
5. **Completion controls:** primary action adapts to the shop’s configured flow (e.g. `Complete sale`, `Given · Cash`, or `Continue order`). Payment method is visible before posting. Secondary actions: UPI pending, credit (authorized customer selection), split, hold, clear draft.
6. **Input shortcuts:** scan and voice actions. Voice action indicates B1 or active B2 state and never starts background listening without an explicit user action.

Do not clutter this screen with customer registration, analytics, supplier dues, AI advice, or compliance explanations unless a blocking rule applies to the selected product.

## 4. Primary flows

### A. Configured instant sale

1. Staff taps a product tile configured for one-tap completion.
2. App reads the current catalogue/price version from the local database and constructs a sale command with a fresh idempotency key.
3. The local transaction service validates shop, role, product availability policy, price, quantity, payment preset, and inventory rule.
4. In one local database transaction, it records the order, line, fulfillment event (for an instant take-away item), payment declaration if selected, inventory movement if tracked, audit/source data, and durable sync outbox entry.
5. UI immediately acknowledges local save and updates the order total/register. Sync occurs later or in background.

The quick-sale preset must be explicit per shop/product. A preset of **Cash received** means staff is declaring that cash was received; it is not a physical drawer verification. If staff cannot safely assert payment, use `Unpaid` or `Payment later`. Never silently default a UPI sale to verified.

### B. Basket sale

1. Tap item to add to current draft. Repeated taps increment quantity only when that behavior is configured and visibly indicated.
2. Adjust quantity, remove line, or scan/add more products. Price is captured from the selected price version; changing the catalogue price later does not alter this order.
3. Select cash received, UPI pending/staff-declared, card, credit, split, or unpaid as supported by shop configuration and permissions.
4. Complete. Validate and atomically save order, fulfillment, payment allocation(s), stock events and audit/outbox records.
5. Show concise receipt/confirmation and preserve the next-sale focus.

### C. Scan or voice-originated draft

The scanner or voice interpreter resolves product identifiers into the same draft cart. Unknown barcode/alias, uncertain quantity, stale price, or ambiguous order context must not post a transaction. Show match choices and allow touch correction. B2 may add items to an existing order only when its order reference/context resolves unambiguously.

### D. Existing order handoff

Orders intended for preparation remain open and appear in CT-08. CT-06 may add items to the selected order only when the operator explicitly selects it. Do not merge a new customer into another active order based on timing or speech proximity.

## 5. Draft and committed state behavior

- Draft cart is device-local and tied to shop, staff, shift and device. It survives navigation and recoverable process death; it is clearly marked **Not recorded** until committed.
- A committed locally saved transaction is marked **Saved on this device**; after backend acknowledgment, **Synced**. Failed sync stays visible/retryable and does not erase the local record.
- On sign-out/device transfer, unsynced drafts and committed events follow an explicit owner-controlled recovery policy; never discard silently.
- A successful transaction clears the basket only after local commit. If commit fails, retain draft and show actionable failure.
- A repeated submit with the same operation key returns the original result. A deliberate second sale creates a new key and is treated as a new sale.
- Undo after commit creates the appropriate reversal/return event, with reason/permission as required; it does not delete the original.

## 6. Calculation and validation rules

1. Money is integer paise. Never use floating-point money arithmetic.
2. The authoritative engine calculates price, discounts, tax treatment, total, balance, and change from shop configuration and stored price version. Client/voice-supplied totals are ignored.
3. Quantities use the product’s canonical selling unit and configured precision; unit conversion is explicit and versioned.
4. Do not permit negative quantities or zero-quantity lines. Discounts, overrides, and below-cost exceptions follow permissions and audit policy.
5. Payment allocations cannot exceed amount due without an explicit, supported rule for change/advance/overpayment.
6. `UPI pending` and `UPI staff-declared` are separate from `UPI provider-verified`. Only authorized provider evidence can create the verified state.
7. Credit requires a selected customer account and authorization. A regular sale does not require a customer.
8. Stock validation follows shop policy (block, warn/allow, or untracked). The policy is visible; stock shortage must not cause a sale to vanish or silently deduct below zero.
9. Packaged goods consume stock at committed sale/fulfillment according to the configured recognition point. Prepared recipes are not deducted on CT-06 until their configured preparation event; avoid double deduction at handover.
10. Sale recognition, tax handling, and inventory consumption trigger must be consistent with the shop’s accounting policy and be finalized before production. For this draft, an instant take-away sale posts at completion; a preparation order posts the sale under the configured fulfillment rule.

## 7. Data written

Minimum business records (names are logical and may map to normalized tables):

- `Order`: id, shop_id, status, currency, created_at, device_time, server_time, actor/shift/device, source, price_version, total_paise, local_sync_state.
- `OrderLine`: id, order_id, product_id, description_snapshot, quantity, base_unit, unit_price_paise, discount/tax fields as configured, line_total_paise, recipe/stock policy reference.
- `FulfillmentEvent`: order_id, event, quantities, actor, timestamp, reason/source.
- `Payment`: id, method, amount_paise, declared/verified evidence state, reference (when available), actor, timestamp.
- `PaymentAllocation`: payment_id, order_id, amount_paise.
- `StockMovement`: product/lot, signed base-unit quantity, movement type, source order/line, actor, timestamp, reversal reference.
- `CommandEvent`/`AuditEvent`: globally unique command/event ID, idempotency key, input source, actor/device/session, action, accepted/rejected result, validation/evidence metadata, correlation IDs.
- `SyncOutbox`: durable payload reference, retry count, next attempt, server acknowledgment/cursor, error class.

All writes that represent one accepted sale must be atomic locally. Backend posting must be idempotent and validate shop authorization independently of client-provided shop/user IDs.

## 8. Command/API contract

### Logical command

`sale.commit` payload includes product IDs, quantities, optional selected order ID, explicit fulfillment/payment intent, expected catalogue/price revision, and client-generated operation ID. It must not accept authoritative price/total from a voice model or client.

### Candidate endpoint

`POST /v1/shops/{shopId}/commands` with `Idempotency-Key` and the shared command envelope from CAT-ARCH-001. The API validates actor membership, role, device enrollment, catalogue status, expected revisions, payment rules, and stock policy, then returns canonical order ID, revision, calculated totals, fulfillment/payment states, posted event IDs and sync cursor.

Offline: the identical logical command is first validated and committed by the local engine; it enters the outbox. Server acknowledgment maps to the same event ID and never creates another posting. If a stale price/catalogue version or conflicting stock movement prevents safe acceptance, return a conflict object for user resolution; do not silently rewrite the past sale.

### Representative result states

- `COMMITTED_LOCAL`: transaction is durable on device; server sync pending.
- `COMMITTED_SYNCED`: server accepted the idempotent command.
- `NEEDS_REVIEW`: stale data or policy conflict; no unsafe mutation.
- `REJECTED`: invalid input/permission/restriction; draft retained.

## 9. Permissions and safeguards

- Cashier: create sale and record allowed payment declarations; cannot alter catalogue prices or approve restricted adjustments by default.
- Manager: configurable overrides/refunds/stock policies within limits.
- Owner: configure quick-sale presets, pricing, tax/inventory policies, user permissions and approvals.
- Enforcement must happen in the local domain layer for immediate offline safety and on the server for authoritative acceptance.
- Restricted products follow applicable configured shop policy. Do not offer tobacco rewards, consumer targeting, or promotional recommendations.
- Voice and scan inputs are untrusted proposals. Customer speech must not be recorded as a shop command. Session must be explicit and visible.

## 10. Screen states and recovery

| State | Required behavior |
|---|---|
| Loading catalogue | Show cached products immediately where available; skeleton only for missing data |
| Empty catalogue | Explain setup path and offer owner/authorized setup action |
| Offline | Keep qualified local actions available; show local-save status and sync queue |
| Sync pending/error | Do not block selling; expose retry and diagnostics, preserve records |
| Stale price/catalogue | Show the current server/local revision and ask whether to refresh or continue under approved policy |
| Insufficient stock | Apply configured block/warn/allow rule; show discrepancy context; never silently mutate |
| Permission denied | Explain restricted action and allowed next step; retain draft |
| Product restricted | Block or constrain according to configured legal rule; record no sale until valid |
| Duplicate submission | Return original committed result; do not show a second order |
| Local storage failure | Do not claim saved; preserve in-memory draft if possible and offer recovery path |
| App interruption | Restore committed state and draft; show voice/Bluetooth session status after foregrounding |
| Conflicting offline activity | Mark stock/order conflict and route to review without last-write-wins |

## 11. Acceptance scenarios

1. **One-tap cash sale:** configured item tap posts one local order, fulfillment, staff-declared cash collection and stock movement exactly once; clear local-save cue appears.
2. **One-tap non-cash sale:** configured UPI pending preset records no provider verification.
3. **Basket mixed input:** add by touch, scan and voice proposal; one reviewed commit creates one order with canonical current prices.
4. **Unpaid handover:** staff completes fulfillment with payment unpaid; sales and collections remain distinct.
5. **Split payment:** allocations equal or remain below due; remainder remains outstanding and correctly classified.
6. **Credit:** requires customer and permission; creates receivable without cash collection.
7. **Changed catalogue price:** committed order retains price snapshot; no retroactive total change.
8. **Offline save and restart:** committed order remains locally visible after process/device restart; sync later posts once.
9. **Repeated network upload:** same idempotency key yields original result, with one payment and one stock movement.
10. **Stock shortage:** configured rule is respected; user sees warning/block and cannot mistake a warning for a successful adjustment.
11. **Correction/refund:** original record remains; authorized reversal references it and correctly adjusts payment/stock according to return disposition.
12. **Role restriction:** cashier cannot change selling price/override a protected policy through UI, API, voice, or offline command.
13. **Voice ambiguity:** uncertain product/order produces no committed mutation; operator resolves with touch.
14. **B2 disconnect:** committed work remains; current uncommitted draft is retained; screen offers tap fallback.

## 12. Implementation dependencies and open decisions

Before coding CT-06, resolve or explicitly configure:

- Whether product-tile tap adds to basket or immediately commits, per product/shop; avoid mixing behaviors invisibly.
- Payment preset defaults and whether staff-declared cash can be recorded with the sale in one step.
- Instant packaged-sale recognition point vs preparation-order recognition point; accounting/tax review required.
- Inventory shortage policy and whether negative on-hand stock is ever allowed.
- Tax-inclusive/exclusive price representation and MVP receipt requirements.
- Whether only one active local draft is needed per counter/device or multiple held drafts are required.
- Supported unit precision, discounts and change/overpayment handling.
- Data retention, local encryption, owner/staff recovery and device replacement behavior.
- Backend technology, API conventions, authentication/device enrollment, and device support matrix.

Recommended prototype baseline: one active basket per counter; product tile adds to basket, with an explicit opt-in instant-sale shortcut for selected products; cash is labeled as staff-declared; UPI begins pending; prepared orders stay unpaid until a payment is recorded; stock and sale events are atomic and idempotent. Validate this with shopkeepers before locking behavior.

## 13. Build sequence

1. Approve the above interaction assumptions and payment/inventory policies.
2. Produce a low-fidelity layout/prototype and review with representative shopkeepers.
3. Specify event schema, database migration, command API schema, and local outbox behavior.
4. Implement deterministic local order/price/payment/stock transaction service.
5. Build CT-06 against that service and connect a simple sales register.
6. Add failure/recovery/permission cases and acceptance tests.
7. Use the same command service for CT-07 voice; do not couple speech recognition to financial posting.

**Completion state:** Designed when reviewed and approved; implemented only when the UI, local persistence, API, permissions, offline recovery, tests, and actual supported-device behavior meet the Definition of Done in CAT-ARCH-001.
