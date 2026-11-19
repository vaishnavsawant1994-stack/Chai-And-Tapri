# CAT-043 — CT-06 Functional Audit

**Status:** Source audit and transaction-engine verification complete; visual browser inspection and shopkeeper validation pending.  
**Reviewed:** `prototype/index.html`, `styles.css`, `app.js`, `transaction-engine.js`, and the engine/parser tests in this workspace.

## Findings and changes

| Area | Finding / current behavior |
|---|---|
| Product search/cart | Search, categories, item add, quantity change, remove and draft persistence are wired. Touch, scan demo, and reviewed typed B1 matches add through `order.add_item`. |
| Cash | Sale commit records a staff-declared cash collection. It does not claim drawer-count or external verification. |
| UPI | Quick sale records UPI pending with zero collected. The typed B2 flow also rejects “UPI verified” without provider evidence. |
| Udhar | Quick sale requires a valid demo customer and adds a customer receivable; it does not create a payment. |
| Stock | Handover/sale engine validates available stock and posts one stock movement per line atomically with the sale. A retry with the same key returns the original result. |
| Draft and reload | Draft, sale, payment, stock, customer balance, command receipts, and event history share a single `ct-store-v1` browser-local snapshot. Sale commit clears the draft in that same write. Engine reconstruction preserves committed work and retry receipts. |
| Correction | Sale-history “Correct” creates a reasoned void event. Cash refund confirmation is required; restocking is explicit. UPI pending is cancelled without inventing a refund. Partially/split-paid sale reversal is rejected as unsupported. |
| CT-07 foundation | A typed command preview creates multiple numbered orders, updates only the referenced order, posts sale/stock at handover, and records payment separately. No audio is captured. |
| Duplicate protection | Engine commands require idempotency keys and retain command receipts. A repeated exact command cannot duplicate sale, payment, or inventory movement. Deliberate new commands create new IDs. |

## Verification performed

- `node --check` passed for the counter UI script, transaction engine, and typed command interpreter.
- `node --test prototype/transaction-engine.test.cjs prototype/voice-counter.test.cjs`: **27 passed, 0 failed** on 28 October 2026.
- Coverage includes paise arithmetic/canonical pricing, cash declaration, UPI-pending evidence, Udhar receivable and repayment, partial cash collections, insufficient stock, retry idempotency, atomic local persistence failure, draft reload recovery, audit void/refund behavior, partial and full handover, competing open orders, multiple concurrent orders, ambiguity, invalid order references and unsupported UPI verification.

## Remaining verification and product limits

- The browser layout and control wiring were not visually inspected. Browser security review rejected opening the local preview origin; no alternate browser access was attempted.
- No real shopkeeper session, supported-phone viewport review, assistive-technology review, or field pilot has occurred.
- Browser `localStorage` is not a secure or production-grade offline database. There is no server, account/device authorization, encryption, durable sync, or multi-device conflict resolution.
- Real microphone, Bluetooth routing, continuous recognition, offline speech, and background/session behavior are absent.
- Payment-provider verification, split tender, partial refunds, and provider settlement reconciliation are absent.
- Search/UI handlers and responsive CSS have syntax/source review but not browser end-to-end automation.

## Exit status

**CAT-043:** Functional transaction behavior is verified at the engine/parser layer. The full task exit criterion is still open until the responsive UI is visually inspected in an authorized preview environment and counter usability is reviewed with shopkeepers.
