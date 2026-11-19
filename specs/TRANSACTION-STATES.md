# Transaction State Contract

**Applies to:** CT-06 Quick Sale, CT-07 typed counter, shared transaction engine  
**Status:** Prototype baseline; production accounting and tax policy still require review

Fulfillment and payment are independent fields on an order. A customer can receive part/all of an order while payment remains open; payment records do not change fulfillment state.

## Order fulfillment

| State | Meaning | Allowed next state |
|---|---|---|
| `taken` | Order recorded; no stock consumed and no sale recognized | `preparing`, `ready`, `partially_given`, `given`, `cancelled` |
| `preparing` | Work has started | `ready`, `partially_given`, `given`, `cancelled` |
| `ready` | Ready to hand over | `partially_given`, `given`, `cancelled` |
| `partially_given` | Some quantities handed over; remaining quantities stay on the order | `partially_given`, `given` |
| `given` | All ordered quantities handed over | No fulfillment changes; correction uses a governed sale reversal |
| `cancelled` | Cancelled before any handover | Terminal |

`order.handover_partial` accepts product quantities no greater than the remaining quantity per line. It validates all requested lines and stock before any mutation, then atomically reduces inventory and adds the handed-over quantities to the order’s sale projection. The order becomes `given` only when every line quantity has been handed over. Payments are blocked until full handover in this prototype. A dedicated partial-return/cancellation workflow is not implemented.

Stock is not reserved at order creation. Competing open orders may therefore contend for the same inventory; a later handover must either commit atomically or fail without changing the order, sale, stock, or ledger.

## Payment/settlement state

| State | Meaning |
|---|---|
| `unpaid` | No collection/credit entry has been recorded |
| `partially_paid` | Some amount is recorded, with a remaining balance |
| `staff_declared` | Staff declared cash received; not a drawer count or external verification |
| `upi_pending` | UPI expected or declared; zero verified collection until provider evidence exists |
| `credit` | Amount assigned to a named customer receivable |
| `settled` | Recorded collections and/or credit repayment account for the full amount due |
| `refunded` | A cash refund was explicitly declared and recorded against the original sale |
| `credit_reversed` | Udhar receivable was reversed with the sale correction |
| `unresolved` | Exception requiring review; never treated as settled |
| `provider_verified` | Reserved for a future authorized payment-provider event; cannot be produced by speech or staff declaration |

The current prototype supports full or partial staff-declared cash collection, UPI pending, customer Udhar, and cash repayments against receivables. Split tenders across methods, provider verification, and automatic settlement matching are not supported. A pending UPI attempt blocks a second collection until a future authorized resolution/cancellation workflow handles it.

## Ledger invariants

1. Amounts use integer paise; payment allocations cannot exceed amount due.
2. One command idempotency key posts at most one order/payment/stock result.
3. Order creation does not consume stock or create revenue.
4. Handover posts stock movement and recognized sale for exactly the newly handed-over quantities, once.
5. Cash is staff-declared; UPI pending has zero collected amount; Udhar is a receivable, never cash.
6. Customer repayment reduces the named customer balance and allocates to open credit entries; it does not create a second sale.
7. Voids/reversals append linked events; they do not delete or rewrite the original transaction.
8. A persistence failure leaves the in-memory state and persisted snapshot unchanged.
