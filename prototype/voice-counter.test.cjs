const test = require('node:test');
const assert = require('node:assert/strict');
const { interpret } = require('./voice-counter.js');
const { createTransactionEngine } = require('./transaction-engine.js');

const catalog = [
  { id: 'cutting', name: 'Cutting Chai', aliases: ['cutting chai', 'cutting'] },
  { id: 'chai', name: 'Regular Chai', aliases: ['regular chai', 'chai'] },
  { id: 'samosa', name: 'Samosa', aliases: ['samosa'] },
  { id: 'biscuit', name: 'Biscuit', aliases: ['biscuit'] }
];
const base = { catalog, orders: [], customers: { ramesh: { id: 'ramesh', name: 'Ramesh Patil' } } };

test('mixed Hinglish new-order phrase maps product aliases and quantities to structured data', () => {
  const result = interpret('Naya order, do cutting chai, ek samosa', base);
  assert.equal(result.ok, true);
  assert.equal(result.command.type, 'order.create');
  assert.deepEqual(result.command.payload.lines, [
    { productId: 'cutting', quantity: 2 },
    { productId: 'samosa', quantity: 1 }
  ]);
});

test('an order mutation without an order number asks for clarification', () => {
  const result = interpret('de diya', base);
  assert.equal(result.ok, false);
  assert.match(result.message, /order number/i);
});

test('UPI verified speech is rejected without provider evidence', () => {
  const result = interpret('Order 101 UPI verified', { ...base, orders: [{ id: 'order-x', number: 101 }] });
  assert.equal(result.ok, false);
  assert.match(result.message, /cannot verify UPI/i);
});

test('order reference produces typed fulfillment and payment commands', () => {
  const order = { id: 'order-x', number: 101 };
  const args = { ...base, orders: [order] };
  assert.deepEqual(interpret('Order 101 ready', args).command, { type: 'order.set_fulfillment', payload: { orderId: 'order-x', status: 'ready' } });
  assert.deepEqual(interpret('Order 101 cash mila', args).command, { type: 'order.record_payment', payload: { orderId: 'order-x', paymentMethod: 'cash' } });
});

test('two live orders stay separate when an item is added and one is handed over', () => {
  const tx = createTransactionEngine({ catalog: [
    { id: 'cutting', name: 'Cutting Chai', price: 1000, stock: 10 },
    { id: 'samosa', name: 'Samosa', price: 2000, stock: 8 },
    { id: 'biscuit', name: 'Biscuit', price: 500, stock: 20 }
  ] });
  const context = () => { const snapshot = tx.snapshot(); return { catalog: [
    { id: 'cutting', name: 'Cutting Chai', aliases: ['cutting chai'] },
    { id: 'samosa', name: 'Samosa', aliases: ['samosa'] },
    { id: 'biscuit', name: 'Biscuit', aliases: ['biscuit'] }
  ], orders: snapshot.orders, customers: snapshot.customers }; };
  const dispatch = (phrase, key) => {
    const result = interpret(phrase, context());
    assert.equal(result.ok, true, result.message);
    return tx.execute({ ...result.command, idempotencyKey: key, source: 'voice_text_preview' });
  };
  const first = dispatch('naya order, do cutting chai, ek samosa', 'order-a');
  const second = dispatch('naya order, ek samosa', 'order-b');
  dispatch(`order ${first.orderNumber} mein ek biscuit add`, 'add-a');
  dispatch(`order ${second.orderNumber} ready`, 'ready-b');
  dispatch(`order ${second.orderNumber} de diya`, 'given-b');
  const snapshot = tx.snapshot();
  const orderA = snapshot.orders.find(order => order.id === first.orderId);
  const orderB = snapshot.orders.find(order => order.id === second.orderId);
  assert.equal(orderA.lines.some(line => line.productId === 'biscuit'), true);
  assert.equal(orderA.fulfillment, 'taken');
  assert.equal(orderB.fulfillment, 'given');
  assert.equal(snapshot.sales.length, 1);
  assert.equal(snapshot.stock.samosa, 7);
  assert.equal(snapshot.stock.biscuit, 20);
});

test('invalid order number and post-handover item change leave business records unchanged', () => {
  const tx = createTransactionEngine({ catalog: [{ id: 'chai', name: 'Chai', price: 1000, stock: 4 }] });
  const snapshot = tx.snapshot();
  const unknown = interpret('order 999 de diya', { catalog, orders: snapshot.orders });
  assert.equal(unknown.ok, false);
  const created = tx.execute({ type: 'order.create', idempotencyKey: 'create-final', payload: { lines: [{ productId: 'chai', quantity: 1 }] } });
  tx.execute({ type: 'order.set_fulfillment', idempotencyKey: 'give-final', payload: { orderId: created.orderId, status: 'given' } });
  assert.throws(() => tx.execute({ type: 'order.add_line', idempotencyKey: 'late-line', payload: { orderId: created.orderId, productId: 'chai', quantity: 1 } }), { code: 'ORDER_CLOSED' });
  assert.equal(tx.snapshot().sales.length, 1);
  assert.equal(tx.snapshot().stock.chai, 3);
});

test('Devanagari Hindi and Marathi item names normalize to configured Roman aliases', () => {
  const hindi = interpret('दो कटिंग चाय', { ...base, catalog });
  assert.equal(hindi.ok, false); // Existing-order mutations require an explicit order reference.
  const created = interpret('नया ऑर्डर दो कटिंग चाय, एक समोसा', { ...base, catalog });
  assert.equal(created.ok, true, created.message);
  assert.deepEqual(created.command.payload.lines, [{ productId: 'cutting', quantity: 2 }, { productId: 'samosa', quantity: 1 }]);
  assert.equal(created.originalText, 'नया ऑर्डर दो कटिंग चाय, एक समोसा');
  const marathi = interpret('नवीन ऑर्डर दोन चहा एक समोसा', { ...base, catalog });
  assert.deepEqual(marathi.command.payload.lines, [{ productId: 'chai', quantity: 2 }, { productId: 'samosa', quantity: 1 }]);
});

test('Devanagari order references normalize for ready and handover commands', () => {
  const args = { ...base, orders: [{ id: 'order-12', number: 12 }] };
  assert.deepEqual(interpret('ऑर्डर बारा तैयार है', args).command.payload, { orderId: 'order-12', status: 'ready' });
  assert.deepEqual(interpret('ऑर्डर १२ दिला', args).command.payload, { orderId: 'order-12', status: 'given' });
});

test('Devanagari cash amount is parsed in paise and requires explicit order reference', () => {
  const args = { ...base, orders: [{ id: 'order-12', number: 12 }] };
  assert.deepEqual(interpret('ऑर्डर १२ पन्नास रुपये कॅश मिळाले', args).command.payload, { orderId: 'order-12', paymentMethod: 'cash', amountPaise: 5000 });
  assert.equal(interpret('पन्नास रुपये कॅश मिळाले', args).ok, false);
});

test('Devanagari UPI remains pending and remove-item phrase requires an order reference', () => {
  const args = { ...base, orders: [{ id: 'order-12', number: 12 }] };
  assert.deepEqual(interpret('ऑर्डर १२ यूपीआई पेमेंट बाकी आहे', args).command.payload, { orderId: 'order-12', paymentMethod: 'upi_pending' });
  assert.equal(interpret('एक चहा काढून टाक', args).ok, false);
  const removed = interpret('ऑर्डर १२ एक चहा काढून टाक', args);
  assert.equal(removed.command.type, 'order.remove_line');
  assert.deepEqual(removed.command.payload, { orderId: 'order-12', productId: 'chai', quantity: 1 });
});
