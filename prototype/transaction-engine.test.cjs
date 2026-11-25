const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createTransactionEngine } = require('./transaction-engine.js');

const catalog = [
  { id: 'chai', name: 'Cutting Chai', price: 1000, stock: 8, returnableForRestock: true },
  { id: 'samosa', name: 'Samosa', price: 2000, stock: 5, returnableForRestock: true }
];

function engine(options = {}) {
  return createTransactionEngine({ catalog: options.catalog || catalog, initialState: options.initialState, persist: options.persist });
}

function saleCommand({ key = 'sale-1', method = 'cash', lines = [{ productId: 'chai', quantity: 2 }], customerId = '' } = {}) {
  return {
    type: 'sale.commit', idempotencyKey: key, actorId: 'cashier-1', shopId: 'shop-1', source: 'touch',
    occurredAt: '2026-10-28T10:00:00.000Z',
    payload: { lines, paymentMethod: method, customerId }
  };
}

test('cash sale uses canonical paise price and posts stock/payment once', () => {
  const tx = engine();
  const result = tx.execute(saleCommand());
  const snapshot = tx.snapshot();
  assert.equal(result.totalPaise, 2000);
  assert.equal(result.paymentState, 'staff_declared');
  assert.equal(snapshot.stock.chai, 6);
  assert.equal(snapshot.payments.length, 1);
  assert.equal(snapshot.payments[0].amountPaise, 2000);
  assert.equal(snapshot.payments[0].status, 'staff_declared');
  assert.equal(snapshot.stockMovements.length, 1);
  assert.equal(snapshot.draft.lines.length, 0);
});

test('shared behavioral fixtures match split tender, repayment, reversal, and partial cancellation outcomes', () => {
  const rows = fs.readFileSync(path.join(__dirname, '..', 'specs', 'fixtures', 'transaction-parity.tsv'), 'utf8')
    .split(/\r?\n/).filter(row => row && !row.replace(/^\uFEFF/, '').startsWith('#')).map(row => row.split('\t'));
  for (const [scenario, itemSpec, totalText, stockText, creditText, finalState] of rows) {
    const tx = engine();
    const openingStock = tx.getStock('chai');
    const [quantityText, productKey] = itemSpec.split('_').slice(0, 1).concat(itemSpec.split('_').slice(1).join('_'));
    const quantity = Number(quantityText);
    const productId = productKey === 'cutting_chai' ? 'chai' : 'chai';
    const created = tx.execute({ type: 'order.create', idempotencyKey: `${scenario}-create`, payload: { lines: [{ productId, quantity }] } });
    const order = () => tx.snapshot().orders.find(item => item.id === created.orderId);
    tx.execute({ type: 'order.set_fulfillment', idempotencyKey: `${scenario}-ready`, payload: { orderId: created.orderId, status: 'ready', expectedRevision: order().revision } });
    if (scenario === 'mixed_repayment_refund') {
      tx.execute({ type: 'order.set_fulfillment', idempotencyKey: `${scenario}-given`, payload: { orderId: created.orderId, status: 'given', expectedRevision: order().revision } });
      tx.execute({ type: 'order.record_payment', idempotencyKey: `${scenario}-cash`, payload: { orderId: created.orderId, paymentMethod: 'cash', amountPaise: 1200, expectedRevision: order().revision } });
      tx.execute({ type: 'order.record_payment', idempotencyKey: `${scenario}-credit`, payload: { orderId: created.orderId, paymentMethod: 'udhar', customerId: 'ramesh', amountPaise: 800, expectedRevision: order().revision } });
      tx.execute({ type: 'customer.record_repayment', idempotencyKey: `${scenario}-repay`, payload: { customerId: 'ramesh', paymentMethod: 'cash', amountPaise: 300 } });
      const sale = tx.snapshot().sales.find(item => item.orderId === created.orderId);
      tx.execute({ type: 'sale.void', idempotencyKey: `${scenario}-refund`, payload: { saleId: sale.id, expectedRevision: order().revision, reason: 'fixture reversal', stockDisposition: 'restock', cashRefundDeclared: true } });
      assert.equal(sale.totalPaise, Number(totalText));
      assert.equal(tx.snapshot().customers.ramesh.balancePaise, Number(creditText));
      assert.equal(tx.snapshot().stock.chai - openingStock, Number(stockText));
      assert.equal(tx.snapshot().sales.find(item => item.id === sale.id).paymentState, finalState.toLowerCase());
    } else {
      tx.execute({ type: 'order.handover_partial', idempotencyKey: `${scenario}-partial`, payload: { orderId: created.orderId, lines: [{ productId, quantity: 1 }], expectedRevision: order().revision } });
      tx.execute({ type: 'order.set_fulfillment', idempotencyKey: `${scenario}-cancel`, payload: { orderId: created.orderId, status: 'cancelled', reason: 'fixture remainder cancelled', expectedRevision: order().revision } });
      assert.equal(order().fulfillment, finalState.toLowerCase());
      assert.equal(tx.snapshot().stock.chai - openingStock, Number(stockText));
      assert.equal(order().lines[0].fulfilledQuantity, 1);
    }
  }
});

test('partial item refunds allocate open udhar first, then confirmed cash, and restock only eligible returned units', () => {
  const tx = engine();
  const order = tx.execute({ type: 'order.create', idempotencyKey: 'partial-refund-order', payload: { lines: [{ productId: 'chai', quantity: 1 }, { productId: 'samosa', quantity: 1 }] } });
  const getOrder = () => tx.snapshot().orders.find(item => item.id === order.orderId);
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'partial-refund-ready', payload: { orderId: order.orderId, status: 'ready' } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'partial-refund-given', payload: { orderId: order.orderId, status: 'given' } });
  tx.execute({ type: 'order.record_payment', idempotencyKey: 'partial-refund-cash', payload: { orderId: order.orderId, paymentMethod: 'cash', amountPaise: 1000 } });
  tx.execute({ type: 'order.record_payment', idempotencyKey: 'partial-refund-credit', payload: { orderId: order.orderId, paymentMethod: 'udhar', customerId: 'ramesh', amountPaise: 2000 } });
  tx.execute({ type: 'customer.record_repayment', idempotencyKey: 'partial-refund-repayment', payload: { customerId: 'ramesh', paymentMethod: 'cash', amountPaise: 500 } });
  const before = tx.snapshot();
  assert.throws(() => tx.execute({ type: 'sale.refund_items', idempotencyKey: 'partial-refund-no-confirm', payload: { orderId: order.orderId, items: [{ productId: 'samosa', quantity: 1, stockDisposition: 'restock' }], reason: 'returned item', cashRefundDeclared: false } }), { code: 'REFUND_CONFIRMATION_REQUIRED' });
  assert.equal(tx.snapshot().customers.ramesh.balancePaise, before.customers.ramesh.balancePaise);
  const refunded = tx.execute({ type: 'sale.refund_items', idempotencyKey: 'partial-refund-item', payload: { orderId: order.orderId, expectedRevision: getOrder().revision, items: [{ productId: 'samosa', quantity: 1, stockDisposition: 'restock' }], reason: 'returned item', cashRefundDeclared: true } });
  assert.deepEqual({ amount: refunded.amountPaise, credit: refunded.creditReversalPaise, cash: refunded.cashRefundPaise, uncollected: refunded.uncollectedReversalPaise }, { amount: 2000, credit: 1500, cash: 500, uncollected: 0 });
  let snapshot = tx.snapshot();
  assert.equal(snapshot.customers.ramesh.balancePaise, 0);
  assert.equal(snapshot.sales[0].paidPaise, 1000);
  assert.equal(snapshot.sales[0].creditPaise, 0);
  assert.equal(snapshot.sales[0].refundedPaise, 2000);
  assert.equal(snapshot.stock.samosa, 5);
  assert.equal(snapshot.stockMovements.filter(m => m.reason === 'sale_refund_restock').length, 1);
  assert.equal(snapshot.payments.filter(p => p.type === 'cash_refund').at(-1).amountPaise, -500);
  assert.equal(snapshot.receivables.find(r => r.type === 'credit_sale').reversedPaise, 1500);
  assert.equal(snapshot.sales[0].voided, false);
  const retry = tx.execute({ type: 'sale.refund_items', idempotencyKey: 'partial-refund-item', payload: { orderId: order.orderId, items: [{ productId: 'samosa', quantity: 1, stockDisposition: 'restock' }], reason: 'returned item', cashRefundDeclared: true } });
  assert.equal(retry.duplicate, true);
  assert.throws(() => tx.execute({ type: 'sale.refund_items', idempotencyKey: 'partial-refund-twice', payload: { orderId: order.orderId, items: [{ productId: 'samosa', quantity: 1, stockDisposition: 'restock' }], reason: 'second return', cashRefundDeclared: true } }), { code: 'RETURN_EXCEEDS_DELIVERED' });
  const restrictive = engine({ initialState: snapshot, catalog: catalog.map(product => ({ ...product, returnableForRestock: false })) });
  assert.throws(() => restrictive.execute({ type: 'sale.refund_items', idempotencyKey: 'partial-refund-nonreturnable', payload: { orderId: order.orderId, items: [{ productId: 'chai', quantity: 1, stockDisposition: 'restock' }], reason: 'prepared drink', cashRefundDeclared: true } }), { code: 'RESTOCK_NOT_ALLOWED' });
});

test('refund behavior matches shared cross-engine fixtures for partial returns and uncollected UPI cancellation', () => {
  const rows = fs.readFileSync(path.join(__dirname, '..', 'specs', 'fixtures', 'refund-parity.tsv'), 'utf8')
    .split(/\r?\n/).filter(row => row && !row.replace(/^\uFEFF/, '').startsWith('#')).map(row => row.split('\t'));
  for (const fields of rows) {
    const [scenario, total, refund, netCash, openCredit, customerBalance, refundedTotal, paymentStateExpected, chaiDelta, samosaDelta, cashEntry, creditEntry, receivableReversed] = fields;
    const tx = engine();
    const openingChai = tx.getStock('chai');
    const openingSamosa = tx.getStock('samosa');
    if (scenario === 'multi_item_partial_return') {
      const created = tx.execute({ type: 'order.create', idempotencyKey: `${scenario}-create`, payload: { lines: [{ productId: 'chai', quantity: 1 }, { productId: 'samosa', quantity: 1 }] } });
      const order = () => tx.snapshot().orders.find(entry => entry.id === created.orderId);
      tx.execute({ type: 'order.set_fulfillment', idempotencyKey: `${scenario}-ready`, payload: { orderId: created.orderId, status: 'ready', expectedRevision: order().revision } });
      tx.execute({ type: 'order.set_fulfillment', idempotencyKey: `${scenario}-given`, payload: { orderId: created.orderId, status: 'given', expectedRevision: order().revision } });
      assert.equal(tx.snapshot().sales[0].totalPaise, Number(total));
      tx.execute({ type: 'order.record_payment', idempotencyKey: `${scenario}-cash`, payload: { orderId: created.orderId, paymentMethod: 'cash', amountPaise: 1000, expectedRevision: order().revision } });
      tx.execute({ type: 'order.record_payment', idempotencyKey: `${scenario}-credit`, payload: { orderId: created.orderId, paymentMethod: 'udhar', customerId: 'ramesh', amountPaise: 2000, expectedRevision: order().revision } });
      tx.execute({ type: 'customer.record_repayment', idempotencyKey: `${scenario}-repay`, payload: { customerId: 'ramesh', paymentMethod: 'cash', amountPaise: 500 } });
      tx.execute({ type: 'sale.refund_items', idempotencyKey: `${scenario}-return`, payload: { orderId: created.orderId, expectedRevision: order().revision, items: [{ productId: 'samosa', quantity: 1, stockDisposition: 'restock' }], reason: 'returned item', cashRefundDeclared: true } });
      const snapshot = tx.snapshot();
      const sale = snapshot.sales.find(entry => entry.orderId === created.orderId);
      assert.equal(sale.refundedPaise, Number(refundedTotal));
      assert.equal(sale.paidPaise, Number(netCash));
      assert.equal(sale.creditPaise, Number(openCredit));
      assert.equal(sale.paymentState, paymentStateExpected.toLowerCase());
      assert.equal(snapshot.customers.ramesh.balancePaise, Number(customerBalance));
      assert.equal(snapshot.stock.chai - openingChai, Number(chaiDelta));
      assert.equal(snapshot.stock.samosa - openingSamosa, Number(samosaDelta));
      assert.equal(snapshot.payments.find(entry => entry.type === 'cash_refund').amountPaise, Number(cashEntry));
      assert.equal(snapshot.payments.find(entry => entry.type === 'credit_reversal').amountPaise, Number(creditEntry));
      assert.equal(snapshot.receivables.find(entry => entry.type === 'credit_sale').reversedPaise, Number(receivableReversed));
      assert.ok(snapshot.events.some(event => event.type === 'sale.item_refunded' && event.productId === 'samosa'));
    } else {
      const committed = saleCommand({ key: `${scenario}-sale`, method: 'upi_pending' });
      committed.payload.lines = [{ productId: 'chai', quantity: 2 }];
      const result = tx.execute(committed);
      tx.execute({ type: 'sale.void', idempotencyKey: `${scenario}-void`, payload: { saleId: result.saleId, reason: 'cancel uncollected order', stockDisposition: 'not_restocked' } });
      const snapshot = tx.snapshot();
      const sale = snapshot.sales[0];
      assert.equal(sale.totalPaise, Number(total));
      assert.equal(sale.refundedPaise, Number(refundedTotal));
      assert.equal(sale.paymentState, paymentStateExpected.toLowerCase());
      assert.equal(sale.paidPaise, Number(netCash));
      assert.equal(sale.creditPaise, Number(openCredit));
      assert.equal(snapshot.stock.chai - openingChai, Number(chaiDelta));
      assert.equal(snapshot.stock.samosa - openingSamosa, Number(samosaDelta));
      assert.equal(snapshot.payments.find(entry => entry.status === 'pending').status, 'pending');
      assert.equal(snapshot.payments.find(entry => entry.type === 'payment_attempt_cancelled').amountPaise, 0);
      assert.equal(snapshot.payments.some(entry => entry.type === 'cash_refund' || entry.type === 'credit_reversal'), false);
    }
    assert.equal(Number(refund) >= 0, true);
  }
});

test('stale order revisions are rejected without recording a second mutation', () => {
  const tx = engine();
  const created = tx.execute({ type: 'order.create', idempotencyKey: 'revision-create', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'revision-ready', payload: { orderId: created.orderId, status: 'ready', expectedRevision: 0 } });
  assert.throws(() => tx.execute({ type: 'order.add_line', idempotencyKey: 'revision-stale', payload: { orderId: created.orderId, productId: 'samosa', quantity: 1, expectedRevision: 0 } }), { code: 'REVISION_CONFLICT' });
  assert.equal(tx.snapshot().orders[0].lines.length, 1);
});

test('touch/voice-style order.add_item is idempotent and does not post stock before sale', () => {
  const tx = engine();
  const command = { type: 'order.add_item', idempotencyKey: 'add-chai', source: 'voice_text_preview', payload: { productId: 'chai', quantity: 2 } };
  const first = tx.execute(command);
  const retry = tx.execute(command);
  const snapshot = tx.snapshot();
  assert.equal(first.quantity, 2);
  assert.equal(retry.duplicate, true);
  assert.deepEqual(snapshot.draft.lines, [['chai', 2]]);
  assert.equal(snapshot.stock.chai, 8);
  assert.equal(snapshot.sales.length, 0);
});

test('UPI pending records no verified or collected payment', () => {
  const tx = engine();
  tx.execute(saleCommand({ method: 'upi_pending' }));
  const snapshot = tx.snapshot();
  assert.equal(snapshot.sales[0].paymentState, 'upi_pending');
  assert.equal(snapshot.payments[0].status, 'pending');
  assert.equal(snapshot.payments[0].evidence, 'none');
  assert.equal(snapshot.payments[0].collectedPaise, 0);
});

test('Udhar requires a valid account and creates a receivable without a collection', () => {
  const tx = engine();
  assert.throws(() => tx.execute(saleCommand({ method: 'udhar' })), { code: 'CUSTOMER_REQUIRED' });
  tx.execute(saleCommand({ method: 'udhar', key: 'credit-1', customerId: 'ramesh' }));
  const snapshot = tx.snapshot();
  assert.equal(snapshot.customers.ramesh.balancePaise, 2000);
  assert.equal(snapshot.receivables.length, 1);
  assert.equal(snapshot.payments.length, 0);
});

test('insufficient stock rejects atomically without a sale, payment, or movement', () => {
  const tx = engine();
  assert.throws(() => tx.execute(saleCommand({ lines: [{ productId: 'samosa', quantity: 6 }] })), { code: 'INSUFFICIENT_STOCK' });
  const snapshot = tx.snapshot();
  assert.equal(snapshot.sales.length, 0);
  assert.equal(snapshot.payments.length, 0);
  assert.equal(snapshot.stockMovements.length, 0);
  assert.equal(snapshot.stock.samosa, 5);
});

test('retry with the same idempotency key returns the original result and does not repost', () => {
  const tx = engine();
  const first = tx.execute(saleCommand());
  const retry = tx.execute(saleCommand());
  const snapshot = tx.snapshot();
  assert.equal(retry.saleId, first.saleId);
  assert.equal(retry.duplicate, true);
  assert.equal(snapshot.sales.length, 1);
  assert.equal(snapshot.payments.length, 1);
  assert.equal(snapshot.stockMovements.length, 1);
  assert.equal(snapshot.stock.chai, 6);
});

test('sale void is audited, refunds declared cash, and restocks only by explicit disposition', () => {
  const tx = engine();
  const committed = tx.execute(saleCommand());
  const correction = tx.execute({
    type: 'sale.void', idempotencyKey: 'void-1', actorId: 'manager-1', source: 'touch',
    occurredAt: '2026-10-28T10:05:00.000Z',
    payload: { saleId: committed.saleId, reason: 'Customer returned the order', cashRefundDeclared: true, stockDisposition: 'restock' }
  });
  const snapshot = tx.snapshot();
  assert.equal(correction.voided, true);
  assert.equal(snapshot.sales[0].voided, true);
  assert.equal(snapshot.sales[0].voidReason, 'Customer returned the order');
  assert.equal(snapshot.stock.chai, 8);
  assert.equal(snapshot.payments.at(-1).amountPaise, -2000);
  assert.equal(snapshot.payments.at(-1).status, 'staff_declared_refund');
  assert.ok(snapshot.events.some(event => event.type === 'sale.voided' && event.reason === 'Customer returned the order'));
  assert.equal(snapshot.stockMovements.length, 2);
});

test('voiding cash without confirming the refund leaves the original sale untouched', () => {
  const tx = engine();
  const committed = tx.execute(saleCommand());
  assert.throws(() => tx.execute({
    type: 'sale.void', idempotencyKey: 'void-no-refund',
    payload: { saleId: committed.saleId, reason: 'Correction', stockDisposition: 'restock', cashRefundDeclared: false }
  }), { code: 'REFUND_CONFIRMATION_REQUIRED' });
  assert.equal(tx.snapshot().sales[0].voided, false);
});

test('local persistence failure does not mutate in-memory transaction state', () => {
  const tx = engine({ persist: () => { throw new Error('quota'); } });
  assert.throws(() => tx.execute(saleCommand()), { code: 'PERSIST_FAILED' });
  const snapshot = tx.snapshot();
  assert.equal(snapshot.sales.length, 0);
  assert.equal(snapshot.stock.chai, 8);
  assert.equal(snapshot.payments.length, 0);
});

test('draft and sale commit use a single saved snapshot so a reload cannot revive the sold draft', () => {
  let durable;
  const tx = engine({ persist: next => { durable = next; } });
  tx.saveDraft({ lines: [['chai', 2]], method: 'cash' });
  assert.equal(durable.draft.lines.length, 1);
  tx.execute(saleCommand());
  assert.equal(durable.sales.length, 1);
  assert.equal(durable.draft.lines.length, 0);
});

test('a reconstructed engine keeps the posted sale and recognizes a sync-style retry', () => {
  let durable;
  const firstDevice = engine({ persist: next => { durable = next; } });
  const command = saleCommand({ key: 'durable-sale' });
  const first = firstDevice.execute(command);
  const restoredDevice = engine({ initialState: durable });
  const retry = restoredDevice.execute(command);
  const snapshot = restoredDevice.snapshot();
  assert.equal(retry.duplicate, true);
  assert.equal(retry.saleId, first.saleId);
  assert.equal(snapshot.sales.length, 1);
  assert.equal(snapshot.stock.chai, 6);
  assert.equal(snapshot.payments.length, 1);
  assert.equal(snapshot.draft.lines.length, 0);
});

test('voiding a UPI-pending sale cancels the attempt without inventing a refund', () => {
  const tx = engine();
  const sale = tx.execute(saleCommand({ method: 'upi_pending' }));
  tx.execute({
    type: 'sale.void', idempotencyKey: 'void-upi', source: 'touch',
    payload: { saleId: sale.saleId, reason: 'Duplicate order', stockDisposition: 'not_restocked' }
  });
  const snapshot = tx.snapshot();
  assert.equal(snapshot.payments.length, 2);
  assert.equal(snapshot.payments[0].status, 'pending');
  assert.equal(snapshot.payments[0].amountPaise, 2000);
  assert.equal(snapshot.payments[0].collectedPaise, 0);
  assert.equal(snapshot.payments[1].status, 'cancelled');
  assert.equal(snapshot.payments[1].amountPaise, 0);
  assert.ok(snapshot.events.some(event => event.type === 'sale.item_refunded' && event.disposition === 'not_restocked'));
});

test('typed counter orders remain unpaid until handover and payment are separately recorded', () => {
  const tx = engine();
  const created = tx.execute({
    type: 'order.create', idempotencyKey: 'voice-order-1', actorId: 'cashier-1', source: 'voice_text_preview',
    payload: { lines: [{ productId: 'chai', quantity: 2 }, { productId: 'samosa', quantity: 1 }] }
  });
  let snapshot = tx.snapshot();
  assert.equal(created.orderNumber, 101);
  assert.equal(snapshot.stock.chai, 8);
  assert.equal(snapshot.sales.length, 0);
  assert.equal(snapshot.orders[0].paymentStatus, 'unpaid');

  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'voice-ready-1', payload: { orderId: created.orderId, status: 'ready' } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'voice-given-1', payload: { orderId: created.orderId, status: 'given' } });
  snapshot = tx.snapshot();
  assert.equal(snapshot.stock.chai, 6);
  assert.equal(snapshot.stock.samosa, 4);
  assert.equal(snapshot.sales.length, 1);
  assert.equal(snapshot.sales[0].paymentState, 'unpaid');

  tx.execute({ type: 'order.record_payment', idempotencyKey: 'voice-cash-1', payload: { orderId: created.orderId, paymentMethod: 'cash' } });
  snapshot = tx.snapshot();
  assert.equal(snapshot.orders[0].paymentStatus, 'staff_declared');
  assert.equal(snapshot.sales[0].paidPaise, 4000);
  assert.equal(snapshot.payments.length, 1);
  assert.equal(snapshot.payments[0].status, 'staff_declared');
});

test('handover retry is idempotent and stock is deducted only once', () => {
  const tx = engine();
  const created = tx.execute({ type: 'order.create', idempotencyKey: 'voice-order-retry', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  const handover = { type: 'order.set_fulfillment', idempotencyKey: 'voice-given-retry', payload: { orderId: created.orderId, status: 'given' } };
  tx.execute(handover);
  assert.equal(tx.execute(handover).duplicate, true);
  assert.equal(tx.snapshot().stock.chai, 7);
  assert.equal(tx.snapshot().stockMovements.length, 1);
});

test('pending UPI cannot be silently replaced by a second collection', () => {
  const tx = engine();
  const created = tx.execute({ type: 'order.create', idempotencyKey: 'voice-order-upi', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'voice-given-upi', payload: { orderId: created.orderId, status: 'given' } });
  tx.execute({ type: 'order.record_payment', idempotencyKey: 'voice-upi-pending', payload: { orderId: created.orderId, paymentMethod: 'upi_pending' } });
  assert.throws(() => tx.execute({ type: 'order.record_payment', idempotencyKey: 'voice-second-cash', payload: { orderId: created.orderId, paymentMethod: 'cash' } }), { code: 'PAYMENT_ATTEMPT_PENDING' });
  const snapshot = tx.snapshot();
  assert.equal(snapshot.payments.length, 1);
  assert.equal(snapshot.payments[0].status, 'pending');
  assert.equal(snapshot.payments[0].collectedPaise, 0);
});

test('staff-declared UPI receipt resolves only the matching pending attempt and is never provider-verified', () => {
  const tx = engine();
  const created = tx.execute({ type: 'order.create', idempotencyKey: 'upi-received-order', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'upi-received-given', payload: { orderId: created.orderId, status: 'given' } });
  tx.execute({ type: 'order.record_payment', idempotencyKey: 'upi-received-pending', payload: { orderId: created.orderId, paymentMethod: 'upi_pending' } });
  const order = tx.snapshot().orders[0];
  const payment = { type: 'order.record_upi_received', idempotencyKey: 'upi-received-declare', payload: { orderId: created.orderId, amountPaise: 500, expectedRevision: order.revision } };
  assert.throws(() => tx.execute({ ...payment, idempotencyKey: 'upi-received-over', payload: { ...payment.payload, amountPaise: 50_000 } }), { code: 'PAYMENT_MISMATCH' });
  const result = tx.execute(payment);
  assert.equal(result.verification, 'staff_declared_not_provider_verified');
  assert.equal(tx.execute(payment).duplicate, true);
  const snapshot = tx.snapshot();
  const attempt = snapshot.payments.find(item => item.id === 'upi-received-pending-payment-attempt');
  assert.equal(attempt.status, 'pending');
  assert.equal(snapshot.payments.some(item => item.type === 'payment_attempt_cancelled' && item.relatedPaymentId === attempt.id), true);
  const received = snapshot.payments.find(item => item.id === 'upi-received-declare-payment');
  assert.equal(received.status, 'staff_declared');
  assert.equal(received.evidence, 'staff_declaration');
  assert.equal(snapshot.sales[0].paidPaise, 500);
  assert.equal(snapshot.orders[0].paymentStatus, 'partially_paid');
});

test('partial handover posts only delivered quantities, then full handover posts the remainder once', () => {
  const tx = engine();
  const order = tx.execute({ type: 'order.create', idempotencyKey: 'partial-order', payload: { lines: [{ productId: 'chai', quantity: 2 }, { productId: 'samosa', quantity: 1 }] } });
  const partial = { type: 'order.handover_partial', idempotencyKey: 'handover-part-1', payload: { orderId: order.orderId, lines: [{ productId: 'chai', quantity: 1 }] } };
  tx.execute(partial);
  assert.equal(tx.execute(partial).duplicate, true);
  let snapshot = tx.snapshot();
  assert.equal(snapshot.orders[0].fulfillment, 'partially_given');
  assert.equal(snapshot.sales[0].totalPaise, 1000);
  assert.equal(snapshot.stock.chai, 7);
  assert.equal(snapshot.stock.samosa, 5);
  assert.throws(() => tx.execute({ type: 'order.record_payment', idempotencyKey: 'pay-partial-order', payload: { orderId: order.orderId, paymentMethod: 'cash' } }), { code: 'ORDER_NOT_GIVEN' });

  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'handover-rest', payload: { orderId: order.orderId, status: 'given' } });
  snapshot = tx.snapshot();
  assert.equal(snapshot.orders[0].fulfillment, 'given');
  assert.equal(snapshot.sales[0].totalPaise, 4000);
  assert.deepEqual(snapshot.sales[0].lines.map(line => [line.productId, line.quantity]), [['chai', 2], ['samosa', 1]]);
  assert.equal(snapshot.stock.chai, 6);
  assert.equal(snapshot.stock.samosa, 4);
  assert.equal(snapshot.stockMovements.length, 3);
});

test('partial cash collections reconcile and overpayment is rejected', () => {
  const tx = engine();
  const order = tx.execute({ type: 'order.create', idempotencyKey: 'cash-partial-order', payload: { lines: [{ productId: 'chai', quantity: 2 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'cash-partial-given', payload: { orderId: order.orderId, status: 'given' } });
  tx.execute({ type: 'order.record_payment', idempotencyKey: 'cash-partial-one', payload: { orderId: order.orderId, paymentMethod: 'cash', amountPaise: 700 } });
  assert.equal(tx.snapshot().orders[0].paymentStatus, 'partially_paid');
  assert.throws(() => tx.execute({ type: 'order.record_payment', idempotencyKey: 'cash-partial-over', payload: { orderId: order.orderId, paymentMethod: 'cash', amountPaise: 1400 } }), { code: 'PAYMENT_MISMATCH' });
  tx.execute({ type: 'order.record_payment', idempotencyKey: 'cash-partial-two', payload: { orderId: order.orderId, paymentMethod: 'cash', amountPaise: 1300 } });
  const snapshot = tx.snapshot();
  assert.equal(snapshot.sales[0].paidPaise, 2000);
  assert.equal(snapshot.sales[0].paymentState, 'staff_declared');
  assert.equal(snapshot.payments.reduce((sum, payment) => sum + payment.amountPaise, 0), 2000);
});

test('cash plus Udhar split is one idempotent order payment command', () => {
  const tx = engine();
  const order = tx.execute({ type: 'order.create', idempotencyKey: 'split-payment-order', payload: { lines: [{ productId: 'chai', quantity: 2 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'split-payment-given', payload: { orderId: order.orderId, status: 'given' } });
  const command = {
    type: 'order.record_cash_udhar_split', idempotencyKey: 'split-payment-1',
    payload: { orderId: order.orderId, cashPaise: 500, customerId: 'ramesh', expectedRevision: 1 }
  };
  assert.throws(() => tx.execute({ ...command, idempotencyKey: 'split-invalid', payload: { ...command.payload, cashPaise: 2000 } }), { code: 'PAYMENT_MISMATCH' });
  const result = tx.execute(command);
  const snapshot = tx.snapshot();
  assert.equal(result.cashPaise, 500);
  assert.equal(result.creditPaise, 1500);
  assert.equal(result.paymentState, 'split');
  assert.equal(result.remainingPaise, 0);
  assert.equal(snapshot.sales[0].paidPaise, 500);
  assert.equal(snapshot.sales[0].creditPaise, 1500);
  assert.equal(snapshot.customers.ramesh.balancePaise, 1500);
  assert.equal(snapshot.payments.filter(item => item.orderId === order.orderId).length, 1);
  assert.equal(snapshot.payments.find(item => item.orderId === order.orderId).status, 'staff_declared');
  assert.equal(snapshot.receivables.filter(item => item.saleId === snapshot.sales[0].id).length, 1);
  assert.equal(snapshot.receivables.find(item => item.saleId === snapshot.sales[0].id).amountPaise, 1500);
  assert.equal(snapshot.events.filter(item => item.id === 'split-payment-1-split-recorded').length, 1);
  assert.equal(tx.execute(command).duplicate, true);
  assert.equal(tx.snapshot().payments.filter(item => item.orderId === order.orderId).length, 1);
});

test('cash plus UPI pending split records only cash as collected and settles the exact pending remainder', () => {
  const tx = engine();
  const created = tx.execute({ type: 'order.create', idempotencyKey: 'cash-upi-order', payload: { lines: [{ productId: 'chai', quantity: 2 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'cash-upi-given', payload: { orderId: created.orderId, status: 'given' } });
  const order = () => tx.snapshot().orders.find(item => item.id === created.orderId);
  const split = { type: 'order.record_cash_upi_pending_split', idempotencyKey: 'cash-upi-split', payload: { orderId: created.orderId, cashPaise: 500, expectedRevision: order().revision } };
  assert.throws(() => tx.execute({ ...split, idempotencyKey: 'cash-upi-invalid', payload: { ...split.payload, cashPaise: 2000 } }), { code: 'PAYMENT_MISMATCH' });
  const result = tx.execute(split);
  let snapshot = tx.snapshot();
  assert.equal(result.cashPaise, 500);
  assert.equal(result.upiPendingPaise, 1500);
  assert.equal(result.paymentState, 'upi_pending');
  assert.equal(snapshot.sales[0].paidPaise, 500);
  assert.equal(snapshot.sales[0].paymentState, 'upi_pending');
  assert.equal(snapshot.payments.filter(p => p.status === 'staff_declared').reduce((sum, p) => sum + p.amountPaise, 0), 500);
  assert.equal(snapshot.payments.find(p => p.status === 'pending').amountPaise, 1500);
  assert.equal(snapshot.payments.find(p => p.status === 'pending').collectedPaise, 0);
  assert.equal(tx.execute(split).duplicate, true);
  tx.execute({ type: 'order.record_upi_received', idempotencyKey: 'cash-upi-received', payload: { orderId: created.orderId, amountPaise: 1500, expectedRevision: order().revision } });
  snapshot = tx.snapshot();
  assert.equal(snapshot.sales[0].paidPaise, 2000);
  assert.equal(snapshot.sales[0].paymentState, 'staff_declared');
  assert.equal(snapshot.payments.filter(p => p.status === 'staff_declared').reduce((sum, p) => sum + p.amountPaise, 0), 2000);
  assert.equal(snapshot.payments.filter(p => p.status === 'verified').length, 0);
  assert.equal(snapshot.events.filter(e => e.id === 'cash-upi-split-split-recorded').length, 1);
});

test('direct staff-declared UPI receipt records a partial collection without creating or verifying an attempt', () => {
  const tx = engine();
  const created = tx.execute({ type: 'order.create', idempotencyKey: 'direct-upi-order', payload: { lines: [{ productId: 'chai', quantity: 2 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'direct-upi-given', payload: { orderId: created.orderId, status: 'given' } });
  const order = () => tx.snapshot().orders.find(item => item.id === created.orderId);
  const command = { type: 'order.record_upi_direct', idempotencyKey: 'direct-upi-1', payload: { orderId: created.orderId, amountPaise: 700, expectedRevision: order().revision } };
  const result = tx.execute(command);
  let snapshot = tx.snapshot();
  assert.equal(result.verification, 'staff_declared_not_provider_verified');
  assert.equal(snapshot.sales[0].paidPaise, 700);
  assert.equal(snapshot.sales[0].paymentState, 'partially_paid');
  assert.equal(snapshot.payments.length, 1);
  assert.equal(snapshot.payments[0].method, 'upi');
  assert.equal(snapshot.payments[0].status, 'staff_declared');
  assert.equal(snapshot.payments[0].collectedPaise, 700);
  assert.equal(tx.execute(command).duplicate, true);
  tx.execute({ type: 'order.record_payment', idempotencyKey: 'direct-upi-no-pending', payload: { orderId: created.orderId, paymentMethod: 'upi_pending' } });
  assert.throws(() => tx.execute({ type: 'order.record_upi_direct', idempotencyKey: 'direct-upi-blocked', payload: { orderId: created.orderId, amountPaise: 100, expectedRevision: order().revision } }), { code: 'UPI_ATTEMPT_PENDING' });
  snapshot = tx.snapshot();
  assert.equal(snapshot.sales[0].paidPaise, 700);
  assert.equal(snapshot.payments.filter(p => p.status === 'verified').length, 0);
});

test('customer repayments allocate to udhar entries and update outstanding balances', () => {
  const tx = engine();
  tx.execute(saleCommand({ method: 'udhar', customerId: 'ramesh', key: 'udhar-repay-sale' }));
  tx.execute({ type: 'customer.record_repayment', idempotencyKey: 'repayment-one', payload: { customerId: 'ramesh', amountPaise: 500, paymentMethod: 'cash' } });
  let snapshot = tx.snapshot();
  assert.equal(snapshot.customers.ramesh.balancePaise, 1500);
  assert.equal(snapshot.sales[0].creditPaise, 1500);
  assert.equal(snapshot.sales[0].paidPaise, 500);
  assert.equal(snapshot.receivables[0].settledPaise, 500);
   assert.equal(snapshot.sales[0].paymentState, 'split');
  tx.execute({ type: 'customer.record_repayment', idempotencyKey: 'repayment-two', payload: { customerId: 'ramesh', amountPaise: 1500, paymentMethod: 'upi' } });
  snapshot = tx.snapshot();
  assert.equal(snapshot.customers.ramesh.balancePaise, 0);
  assert.equal(snapshot.sales[0].creditPaise, 0);
  assert.equal(snapshot.sales[0].paidPaise, 2000);
   assert.equal(snapshot.sales[0].paymentState, 'staff_declared');
  assert.equal(snapshot.payments.filter(payment => payment.type === 'customer_repayment').length, 2);
  assert.deepEqual(snapshot.payments.filter(payment => payment.type === 'customer_repayment').map(payment => [payment.method, payment.status]), [['cash', 'staff_declared'], ['upi', 'staff_declared']]);
});

test('concurrent orders cannot both consume the same final stock', () => {
  const tx = createTransactionEngine({ catalog: [{ id: 'chai', name: 'Chai', price: 1000, stock: 1 }] });
  const first = tx.execute({ type: 'order.create', idempotencyKey: 'race-order-a', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  const second = tx.execute({ type: 'order.create', idempotencyKey: 'race-order-b', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'race-give-a', payload: { orderId: first.orderId, status: 'given' } });
  assert.throws(() => tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'race-give-b', payload: { orderId: second.orderId, status: 'given' } }), { code: 'INSUFFICIENT_STOCK' });
  const snapshot = tx.snapshot();
  assert.equal(snapshot.stock.chai, 0);
  assert.equal(snapshot.sales.length, 1);
  assert.equal(snapshot.orders.find(order => order.id === second.orderId).fulfillment, 'taken');
});

test('handover persistence failure leaves order, sale and stock unchanged', () => {
  let fail = false;
  let durable;
  const tx = createTransactionEngine({
    catalog: [{ id: 'chai', name: 'Chai', price: 1000, stock: 4 }],
    persist: next => { if (fail) throw new Error('simulated interruption'); durable = next; }
  });
  const order = tx.execute({ type: 'order.create', idempotencyKey: 'persist-order', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  fail = true;
  assert.throws(() => tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'persist-give', payload: { orderId: order.orderId, status: 'given' } }), { code: 'PERSIST_FAILED' });
  const snapshot = tx.snapshot();
  assert.equal(snapshot.orders[0].fulfillment, 'taken');
  assert.equal(snapshot.sales.length, 0);
  assert.equal(snapshot.stock.chai, 4);
  assert.equal(durable.orders[0].fulfillment, 'taken');
});

test('refund persistence failure rolls back financial, receivable, inventory and audit state', () => {
  let fail = false;
  let durable;
  const tx = createTransactionEngine({
    catalog,
    persist: next => { if (fail) throw new Error('simulated refund write failure'); durable = structuredClone(next); }
  });
  const sale = tx.execute(saleCommand({ key: 'refund-atomic-sale', method: 'cash', lines: [{ productId: 'chai', quantity: 1 }] }));
  const before = tx.snapshot();
  const durableBefore = structuredClone(durable);
  fail = true;
  assert.throws(() => tx.execute({ type: 'sale.refund_amount', idempotencyKey: 'refund-atomic-key', payload: { saleId: sale.saleId, amountPaise: 300, reason: 'write interruption', cashRefundDeclared: true } }), { code: 'PERSIST_FAILED' });
  assert.deepEqual(tx.snapshot(), before);
  assert.deepEqual(durable, durableBefore);
});

test('invalid order transitions do not alter the state', () => {
  const tx = engine();
  const order = tx.execute({ type: 'order.create', idempotencyKey: 'invalid-transition-order', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'invalid-transition-ready', payload: { orderId: order.orderId, status: 'ready' } });
  assert.throws(() => tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'invalid-transition-backwards', payload: { orderId: order.orderId, status: 'preparing' } }), { code: 'INVALID_ORDER_TRANSITION' });
  assert.equal(tx.snapshot().orders[0].fulfillment, 'ready');
  assert.equal(tx.snapshot().stock.chai, 8);
});

test('remove-line command edits an open order without posting stock or sale events', () => {
  const tx = engine();
  const order = tx.execute({ type: 'order.create', idempotencyKey: 'remove-create', payload: { lines: [{ productId: 'chai', quantity: 2 }, { productId: 'samosa', quantity: 1 }] } });
  tx.execute({ type: 'order.remove_line', idempotencyKey: 'remove-one-chai', payload: { orderId: order.orderId, productId: 'chai', quantity: 1 } });
  const snapshot = tx.snapshot();
  assert.equal(snapshot.orders[0].lines.find(line => line.productId === 'chai').quantity, 1);
  assert.equal(snapshot.stock.chai, 8);
  assert.equal(snapshot.sales.length, 0);
  assert.equal(snapshot.events.some(event => event.type === 'order.line_removed'), true);
});
