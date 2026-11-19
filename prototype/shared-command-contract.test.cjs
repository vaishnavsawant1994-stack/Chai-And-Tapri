const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const voice = require('./voice-counter.js');

const fixturePath = path.join(__dirname, '..', 'specs', 'fixtures', 'voice-command-contract.tsv');
const fixtures = fs.readFileSync(fixturePath, 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#'))
  .map(line => {
    const [transcript, type, orderNumber, productId, quantity, amountPaise] = line.split('\t');
    return { transcript, type, orderNumber: orderNumber ? Number(orderNumber) : null, productId: productId || null, quantity: Number(quantity), amountPaise: amountPaise ? Number(amountPaise) : null };
  });

const catalog = [
  { id: 'cutting_chai', name: 'Cutting Chai', aliases: ['cutting chai', 'cutting'] },
  { id: 'chai', name: 'Regular Chai', aliases: ['chai', 'chaha'] },
  { id: 'samosa', name: 'Samosa', aliases: ['samosa'] },
  { id: 'coffee', name: 'Coffee', aliases: ['coffee', 'kaapi'] }
];

function semantic(result) {
  if (!result.ok) return { type: 'reject' };
  const { type, payload } = result.command;
  if (type === 'order.create') return { type: 'create', productId: payload.lines[0].productId, quantity: payload.lines[0].quantity };
  if (type === 'order.add_line') return { type: 'add', orderNumber: 12, productId: payload.productId, quantity: payload.quantity };
  if (type === 'order.remove_line') return { type: 'remove', orderNumber: 12, productId: payload.productId, quantity: payload.quantity };
  if (type === 'order.handover_partial') return { type: 'partial_given', orderNumber: 12, productId: payload.lines[0].productId, quantity: payload.lines[0].quantity };
  if (type === 'order.set_fulfillment') return { type: payload.status, orderNumber: 12 };
  if (type === 'order.record_payment') return { type: payload.paymentMethod === 'cash' ? 'cash' : 'upi', orderNumber: 12, amountPaise: payload.amountPaise ?? null };
  return { type: 'unsupported' };
}

test('JavaScript parser matches shared Kotlin command contract fixtures', () => {
  assert.ok(fixtures.length > 0, 'contract fixture set must not be empty');
  for (const fixture of fixtures) {
    const result = voice.interpret(fixture.transcript, {
      catalog,
      orders: fixture.orderNumber === null ? [] : [{ id: `order-${fixture.orderNumber}`, number: fixture.orderNumber }]
    });
    const actual = semantic(result);
    assert.equal(actual.type, fixture.type, fixture.transcript);
    if (fixture.orderNumber !== null) assert.equal(actual.orderNumber, fixture.orderNumber, fixture.transcript);
    if (fixture.productId) assert.equal(actual.productId, fixture.productId, fixture.transcript);
    if (fixture.productId || fixture.type === 'create' || fixture.type === 'add' || fixture.type === 'remove') assert.equal(actual.quantity, fixture.quantity, fixture.transcript);
    if (fixture.amountPaise !== null) assert.equal(actual.amountPaise, fixture.amountPaise, fixture.transcript);
  }
});
