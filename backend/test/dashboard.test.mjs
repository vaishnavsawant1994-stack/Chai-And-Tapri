import test from 'node:test';
import assert from 'node:assert/strict';
import { buildDailySalesActivity, buildInventorySnapshot, buildProductPerformance, buildProfitReadiness, buildRecordedMoneyMovementSummary, buildShopOverview } from '../src/dashboard.mjs';
import { ApiError } from '../src/errors.mjs';
import { createApiServer } from '../src/http.mjs';
import { SyncService } from '../src/service.mjs';

const row = (serverRevision, payload, receivedAt = new Date(`2026-11-01T10:00:${String(serverRevision).padStart(2, '0')}Z`)) => ({
  server_revision: serverRevision,
  received_at: receivedAt,
  envelope: { payload: { kind: 'projection_delta.v1', ...payload } }
});
const empty = { orders: [], stock: [], stockMovements: [], customerCredits: [], repayments: [], paymentEntries: [] };

test('owner overview derives latest delivered value and separate cash, UPI and pending totals', () => {
  const firstOrder = {
    orderNumber: 8, fulfillment: 'GIVEN', payment: 'SPLIT', paidPaise: 5000, creditPaise: 0,
    refundedPaise: 1000, cashRefundedPaise: 1000, creditReversedPaise: 0, salePosted: true, voided: false,
    revision: 1, createdAtEpochMs: 1000,
    lines: [{ productId: 'chai', quantity: 2, deliveredQuantity: 2, refundedQuantity: 0, unitPricePaise: 3000 }]
  };
  const changedOrder = { ...firstOrder, revision: 2, refundedPaise: 1000,
    lines: [{ ...firstOrder.lines[0], refundedQuantity: 1 }] };
  const result = buildShopOverview({
    shopId: 'shop-id', shopName: 'Synthetic Tapri', serverRevision: 3,
    events: [
      row(1, { ...empty, orders: [firstOrder], stock: [{ productId: 'chai', quantity: 4 }], stockMovements: [{ movementId: 'm-1', productId: 'chai', quantityDelta: -2, orderNumber: 8 }], paymentEntries: [
        { entryId: 'cash-1', orderNumber: 8, customerId: null, amountPaise: 5000, method: 'cash', status: 'staff_declared', kind: 'order_collection', relatedEntryId: null },
        { entryId: 'upi-pending', orderNumber: 8, customerId: null, amountPaise: 2000, method: 'upi', status: 'pending', kind: 'order_collection', relatedEntryId: null }
      ] }),
      row(2, { ...empty, orders: [changedOrder], stock: [{ productId: 'chai', quantity: 3 }, { productId: 'coffee', quantity: null }], stockMovements: [{ movementId: 'm-2', productId: 'chai', quantityDelta: 1, orderNumber: 8 }], paymentEntries: [
        { entryId: 'cash-refund', orderNumber: 8, customerId: null, amountPaise: -1000, method: 'cash', status: 'staff_declared_refund', kind: 'cash_refund', relatedEntryId: null },
        { entryId: 'upi-collected', orderNumber: 8, customerId: null, amountPaise: 1000, method: 'upi', status: 'staff_declared', kind: 'order_collection', relatedEntryId: null },
        { entryId: 'upi-cancel', orderNumber: 8, customerId: null, amountPaise: 0, method: 'upi', status: 'cancelled', kind: 'payment_attempt_cancelled', relatedEntryId: 'upi-pending' }
      ] })
    ]
  });

  assert.equal(result.recordedOrderCount, 1);
  assert.equal(result.openOrderCount, 0);
  assert.equal(result.netDeliveredItemValuePaise, 3000);
  assert.equal(result.cashCollectedPaise, 5000);
  assert.equal(result.cashRefundedPaise, 1000);
  assert.equal(result.staffDeclaredUpiPaise, 1000);
  assert.equal(result.upiPendingPaise, 0);
  assert.equal(result.trackedProductCount, 2);
  assert.equal(result.productsWithUnknownStockCount, 1);
  assert.equal(result.stockMovementCount, 2);
  assert.match(result.evidence.cash, /Staff-declared/);
  assert.match(result.evidence.upi, /no provider verification/);
  assert.match(result.evidence.customerCredit, /Excluded/);
});

test('overview rejects unsafe aggregate totals and keeps empty state at zero', () => {
  const huge = { orderNumber: 1, fulfillment: 'GIVEN', payment: 'STAFF_DECLARED', paidPaise: 0, creditPaise: 0,
    refundedPaise: 0, cashRefundedPaise: 0, creditReversedPaise: 0, salePosted: true, voided: false, revision: 1,
    createdAtEpochMs: 1, lines: [
      { productId: 'chai', quantity: 1, deliveredQuantity: 1, refundedQuantity: 0, unitPricePaise: Number.MAX_SAFE_INTEGER },
      { productId: 'coffee', quantity: 1, deliveredQuantity: 1, refundedQuantity: 0, unitPricePaise: Number.MAX_SAFE_INTEGER }
    ] };
  assert.throws(() => buildShopOverview({ shopId: 's', shopName: 'S', serverRevision: 1, events: [row(1, { ...empty, orders: [huge] })] }), (error) => error instanceof ApiError && error.status === 422);
  const result = buildShopOverview({ shopId: 's', shopName: 'S', serverRevision: 0, events: [] });
  assert.equal(result.recordedOrderCount, 0);
  assert.equal(result.netDeliveredItemValuePaise, 0);
  assert.equal(result.lastAcceptedAt, null);
  assert.match(result.evidence.scope, /Accepted server events only/);
});

test('product performance uses latest order states, subtracts returns, excludes voided orders and separates units', () => {
  const chai1 = { orderNumber: 1, fulfillment: 'GIVEN', payment: 'STAFF_DECLARED', paidPaise: 2000, creditPaise: 0,
    refundedPaise: 0, cashRefundedPaise: 0, creditReversedPaise: 0, salePosted: true, voided: false, revision: 1,
    createdAtEpochMs: 10, lines: [{ productId: 'chai', quantity: 2, deliveredQuantity: 2, refundedQuantity: 0, unitPricePaise: 1000 }] };
  const chai1Returned = { ...chai1, revision: 2, refundedPaise: 1000,
    lines: [{ ...chai1.lines[0], refundedQuantity: 1 }] };
  const chai2 = { ...chai1, orderNumber: 2, revision: 1, lines: [
    { productId: 'chai', quantity: 4, deliveredQuantity: 3, refundedQuantity: 1, unitPricePaise: 1250 },
    { productId: 'coffee', quantity: 1, deliveredQuantity: 1, refundedQuantity: 0, unitPricePaise: 5000 }
  ] };
  const voided = { ...chai1, orderNumber: 3, voided: true, lines: [{ ...chai1.lines[0], productId: 'samosa' }] };
  const result = buildProductPerformance({ shopId: 's', shopName: 'Synthetic', serverRevision: 4, events: [
    row(1, { ...empty, orders: [chai1], stock: [{ productId: 'chai', quantity: 8 }] }),
    row(2, { ...empty, orders: [chai1Returned], stock: [{ productId: 'chai', quantity: 7 }, { productId: 'coffee', quantity: null }] }),
    row(3, { ...empty, orders: [chai2] }),
    row(4, { ...empty, orders: [voided] })
  ] });

  const chai = result.products.find((item) => item.productId === 'chai');
  assert.equal(chai.grossDeliveredUnits, 5);
  assert.equal(chai.refundedUnits, 2);
  assert.equal(chai.netDeliveredUnits, 3);
  assert.equal(chai.netDeliveredItemValuePaise, 3500);
  assert.equal(chai.orderCount, 2);
  assert.equal(chai.stockState, 'known');
  assert.equal(chai.stockQuantity, 7);
  assert.equal(result.products.find((item) => item.productId === 'coffee').stockState, 'unknown');
  assert.equal(result.products.find((item) => item.productId === 'samosa'), undefined);
  assert.equal(result.netDeliveredItemValuePaise, 8500);
  assert.match(result.evidence.itemValue, /not recognized profit/);
  assert.match(result.evidence.units, /must not be compared or summed/);
});

test('product performance rejects unsafe delivered value and reports empty accepted data', () => {
  const huge = { orderNumber: 1, fulfillment: 'GIVEN', payment: 'STAFF_DECLARED', paidPaise: 0, creditPaise: 0,
    refundedPaise: 0, cashRefundedPaise: 0, creditReversedPaise: 0, salePosted: true, voided: false, revision: 1,
    createdAtEpochMs: 1, lines: [{ productId: 'chai', quantity: 2, deliveredQuantity: 2, refundedQuantity: 0, unitPricePaise: Number.MAX_SAFE_INTEGER }] };
  assert.throws(() => buildProductPerformance({ shopId: 's', shopName: 'S', serverRevision: 1, events: [row(1, { ...empty, orders: [huge] })] }), (error) => error instanceof ApiError && error.status === 422);
  const result = buildProductPerformance({ shopId: 's', shopName: 'S', serverRevision: 0, events: [] });
  assert.deepEqual(result.products, []);
  assert.equal(result.productCount, 0);
  assert.equal(result.netDeliveredItemValuePaise, 0);
});

test('inventory snapshot keeps known, unknown, and unreported quantities distinct and deduplicates movements', () => {
  const events = [
    row(1, { ...empty,
      stock: [{ productId: 'chai', quantity: 12 }, { productId: 'coffee', quantity: null }],
      stockMovements: [
        { movementId: 'm-1', productId: 'chai', quantityDelta: -2, orderNumber: 4 },
        { movementId: 'm-2', productId: 'sugar', quantityDelta: -1, orderNumber: null }
      ]
    }),
    row(2, { ...empty,
      stock: [{ productId: 'chai', quantity: 10 }],
      stockMovements: [{ movementId: 'm-1', productId: 'chai', quantityDelta: -2, orderNumber: 4 }]
    })
  ];
  const result = buildInventorySnapshot({ shopId: 'shop-1', shopName: 'Synthetic Tapri', serverRevision: 2, events });
  assert.equal(result.productCount, 3);
  assert.equal(result.knownQuantityCount, 1);
  assert.equal(result.unknownQuantityCount, 1);
  assert.equal(result.unreportedQuantityCount, 1);
  assert.equal(result.products.find((item) => item.productId === 'chai').quantity, 10);
  assert.equal(result.products.find((item) => item.productId === 'coffee').quantityState, 'unknown');
  assert.equal(result.products.find((item) => item.productId === 'sugar').quantityState, 'unreported');
  assert.equal(result.movementCount, 2);
  assert.equal(result.recentMovements[0].movementId, 'm-1');
  assert.equal(result.recentMovements[0].serverRevision, 2);
  assert.match(result.evidence.quantities, /unit metadata is not in the accepted sync model/);
  assert.match(result.evidence.reconciliation, /does not infer missing sales/);
});

test('inventory snapshot returns an explicit empty accepted state', () => {
  const result = buildInventorySnapshot({ shopId: 's', shopName: 'S', serverRevision: 0, events: [] });
  assert.deepEqual(result.products, []);
  assert.deepEqual(result.recentMovements, []);
  assert.equal(result.productCount, 0);
  assert.equal(result.movementCount, 0);
});

test('daily sales activity diffs order states and groups declared tenders by server UTC receipt date', () => {
  const created = {
    orderNumber: 4, fulfillment: 'TAKEN', payment: 'UNPAID', paidPaise: 0, creditPaise: 0,
    refundedPaise: 0, cashRefundedPaise: 0, creditReversedPaise: 0, salePosted: false, voided: false,
    revision: 1, createdAtEpochMs: 10,
    lines: [{ productId: 'chai', quantity: 2, deliveredQuantity: 0, refundedQuantity: 0, unitPricePaise: 1000 }]
  };
  const given = { ...created, fulfillment: 'GIVEN', payment: 'SPLIT', paidPaise: 3000, salePosted: true, revision: 2,
    lines: [{ ...created.lines[0], deliveredQuantity: 2 }] };
  const returned = { ...given, payment: 'REFUNDED', refundedPaise: 1000, cashRefundedPaise: 500, revision: 3,
    lines: [{ ...given.lines[0], refundedQuantity: 1 }] };
  const payment = (entryId, amountPaise, method, status, kind = 'order_collection') => ({
    entryId, orderNumber: 4, customerId: null, amountPaise, method, status, kind, relatedEntryId: null
  });
  const result = buildDailySalesActivity({ shopId: 's', shopName: 'Synthetic', serverRevision: 3, days: 7, endDate: '2026-11-03', events: [
    row(1, { ...empty, orders: [created] }, new Date('2026-10-20T10:00:00Z')),
    row(2, { ...empty, orders: [given], paymentEntries: [
      payment('cash-1', 2000, 'cash', 'staff_declared'),
      payment('upi-1', 1000, 'upi', 'staff_declared'),
      payment('pending-1', 1500, 'upi', 'pending', 'payment_attempt')
    ], expenses: [
      { expenseId: 'expense-rent', category: 'rent', amountPaise: 50000, method: 'upi', status: 'staff_declared' },
      { expenseId: 'expense-electricity', category: 'electricity', amountPaise: 3450, method: 'cash', status: 'staff_declared' }
    ] }, new Date('2026-11-02T18:30:00-05:00')),
    row(3, { ...empty, orders: [returned], paymentEntries: [
      payment('cash-1', 2000, 'cash', 'staff_declared'),
      payment('cash-refund-1', -500, 'cash', 'staff_declared_refund', 'cash_refund')
    ], expenses: [{ expenseId: 'expense-rent', category: 'rent', amountPaise: 50000, method: 'upi', status: 'staff_declared' }] }, new Date('2026-11-03T01:00:00Z'))
  ] });

  const november3 = result.daily.find((day) => day.date === '2026-11-03');
  const november2 = result.daily.find((day) => day.date === '2026-11-02');
  assert.equal(result.daily.length, 7);
  assert.equal(result.acceptedEventCount, 2);
  assert.equal(result.totals.ordersWithSaleStarted, 1);
  assert.equal(result.totals.netDeliveredItemValueChangePaise, 1000);
  assert.equal(result.totals.staffDeclaredCashPaise, 2000);
  assert.equal(result.totals.staffDeclaredCashRefundPaise, 500);
  assert.equal(result.totals.staffDeclaredUpiPaise, 1000);
  assert.equal(result.totals.upiPendingAttemptsStartedPaise, 1500);
  assert.equal(result.totals.staffDeclaredExpensesPaise, 53450);
  assert.deepEqual(result.expenseCategories, [
    { category: 'rent', count: 1, amountPaise: 50000 },
    { category: 'electricity', count: 1, amountPaise: 3450 }
  ]);
  assert.equal(november2.staffDeclaredExpensesPaise, 53450);
  assert.equal(november3.staffDeclaredExpensesPaise, 0);
  assert.equal(november3.netDeliveredItemValueChangePaise, -1000);
  assert.equal(november2.ordersWithSaleStarted, 1);
  assert.equal(november2.eventsAccepted, 1);
  assert.equal(november3.eventsAccepted, 1);
  assert.equal(result.range.timezone, 'UTC');
  assert.match(result.evidence.deliveredValue, /not profit/);
  assert.match(result.evidence.pending, /not the currently outstanding/);
  assert.match(result.evidence.expenses, /not a profit calculation/);
});

test('daily sales activity rejects unsupported ranges and invalid calendar dates', () => {
  const base = { shopId: 's', shopName: 'S', serverRevision: 0, events: [] };
  assert.throws(() => buildDailySalesActivity({ ...base, days: 14 }), (error) => error instanceof ApiError && error.code === 'invalid_activity_range');
  assert.throws(() => buildDailySalesActivity({ ...base, endDate: '2026-02-31' }), (error) => error instanceof ApiError && error.code === 'invalid_activity_range');
});

test('recorded money movement summary excludes pending UPI and checks paise aggregates', () => {
  const activity = {
    shopId: 's', shopName: 'Synthetic', serverRevision: 8,
    range: { fromDate: '2026-11-02', throughDate: '2026-11-03', days: 2, timezone: 'UTC' },
    evidence: { scope: 'accepted only', dates: 'server UTC' },
    daily: [
      { date: '2026-11-02', staffDeclaredCashPaise: 2000, staffDeclaredUpiPaise: 1000,
        staffDeclaredCashRefundPaise: 500, staffDeclaredExpensesPaise: 750, upiPendingAttemptsStartedPaise: 9000 },
      { date: '2026-11-03', staffDeclaredCashPaise: 0, staffDeclaredUpiPaise: 0,
        staffDeclaredCashRefundPaise: 0, staffDeclaredExpensesPaise: 0, upiPendingAttemptsStartedPaise: 0 }
    ]
  };
  const result = buildRecordedMoneyMovementSummary(activity);
  assert.equal(result.shopId, 's');
  assert.equal(result.serverRevision, 8);
  assert.equal(result.daily[0].recordedInflowPaise, 3000);
  assert.equal(result.daily[0].recordedOutflowPaise, 1250);
  assert.equal(result.daily[0].netRecordedMovementPaise, 1750);
  assert.equal(result.totals.netRecordedMovementPaise, 1750);
  assert.match(result.evidence.net, /not profit/);
  assert.match(result.evidence.exclusions, /UPI pending/);
  assert.throws(() => buildRecordedMoneyMovementSummary({ ...activity, daily: [
    { ...activity.daily[0], staffDeclaredCashPaise: Number.MAX_SAFE_INTEGER, staffDeclaredUpiPaise: 1 }
  ] }) , (error) => error instanceof ApiError && error.status === 422);
});

test('profit readiness exposes accepted activity and keeps profit unavailable without cost coverage', () => {
  const activity = {
    shopId: 'shop', shopName: 'Synthetic', serverRevision: 7,
    range: { fromDate: '2026-11-01', throughDate: '2026-11-07', days: 7, timezone: 'UTC' },
    totals: { netDeliveredItemValueChangePaise: 9200, staffDeclaredExpensesPaise: 1800 },
    evidence: {
      scope: 'Accepted events only.',
      deliveredValue: 'Delivered value is not profit.',
      expenses: 'Recorded expense subset is incomplete.'
    }
  };
  const result = buildProfitReadiness(activity);
  assert.equal(result.status, 'not_calculable');
  assert.equal(result.figures.netDeliveredItemValueChangePaise, 9200);
  assert.equal(result.figures.staffDeclaredExpensesPaise, 1800);
  assert.equal(result.figures.grossProfitPaise, null);
  assert.equal(result.figures.operatingProfitPaise, null);
  assert.equal(result.missingInputs.length, 4);
  assert.match(result.evidence.result, /No profit value is estimated/);
});

test('profit readiness reports latest accepted estimated-cost coverage without calculating profit', () => {
  const accepted = row(1, { ...empty, orders: [
    { orderNumber: 1, salePosted: true, voided: false, lines: [
      { productId: 'chai', quantity: 2, deliveredQuantity: 2, refundedQuantity: 0, unitPricePaise: 1000, estimatedUnitCostPaise: 425 },
      { productId: 'samosa', quantity: 1, deliveredQuantity: 1, refundedQuantity: 0, unitPricePaise: 2000 }
    ] }
  ] });
  const activity = buildDailySalesActivity({ shopId: 's', shopName: 'S', serverRevision: 1, events: [accepted], days: 7, endDate: '2026-11-07' });
  const result = buildProfitReadiness(activity);
  assert.deepEqual(result.costCoverage, {
    basis: 'latest accepted order states across available sync history; all dates',
    netDeliveredSaleLines: 2,
    costedNetDeliveredSaleLines: 1,
    uncostedNetDeliveredSaleLines: 1,
    complete: false
  });
  assert.equal(result.status, 'not_calculable');
  assert.equal(result.figures.grossProfitPaise, null);
  assert.equal(result.figures.operatingProfitPaise, null);
  assert.match(result.missingInputs[0], /missing for some or all/);
});

test('sync service refuses dashboard access to non-owner roles before reading the database', async () => {
  const service = new SyncService(null);
  await assert.rejects(service.shopOverview({ role: 'staff' }), (error) => error instanceof ApiError && error.status === 403 && error.code === 'owner_access_required');
  await assert.rejects(service.shopProductPerformance({ role: 'staff' }), (error) => error instanceof ApiError && error.status === 403 && error.code === 'owner_access_required');
  await assert.rejects(service.shopInventorySnapshot({ role: 'staff' }), (error) => error instanceof ApiError && error.status === 403 && error.code === 'owner_access_required');
  await assert.rejects(service.shopDailySalesActivity({ role: 'staff' }), (error) => error instanceof ApiError && error.status === 403 && error.code === 'owner_access_required');
  await assert.rejects(service.shopRecordedMoneyMovements({ role: 'staff' }), (error) => error instanceof ApiError && error.status === 403 && error.code === 'owner_access_required');
  await assert.rejects(service.shopProfitReadiness({ role: 'staff' }), (error) => error instanceof ApiError && error.status === 403 && error.code === 'owner_access_required');
});

test('owner overview route requires authentication and serves only owner static assets', async (t) => {
  const calls = [];
  const fixture = { shopId: 'shop-1', shopName: 'Synthetic Tapri', serverRevision: 0, acceptedEventCount: 0 };
  const service = {
    async authenticate(token, shopId) {
      calls.push({ token, shopId });
      if (token !== 'owner-token' || shopId !== 'shop-1') throw new ApiError(401, 'unauthorized');
      return { role: 'owner', shop_id: shopId, business_id: 'business-1' };
    },
    async shopOverview(actor) { return { ...fixture, shopId: actor.shop_id }; },
    async shopProductPerformance(actor) { return { ...fixture, shopId: actor.shop_id, products: [] }; },
    async shopInventorySnapshot(actor) { return { ...fixture, shopId: actor.shop_id, products: [], recentMovements: [] }; },
    async shopDailySalesActivity(actor, days) { return { ...fixture, shopId: actor.shop_id, days, daily: [] }; },
    async shopRecordedMoneyMovements(actor, days) { return { ...fixture, shopId: actor.shop_id, days, daily: [], totals: {} }; },
    async shopProfitReadiness(actor, days) { return { ...fixture, shopId: actor.shop_id, days, status: 'not_calculable', figures: { grossProfitPaise: null, operatingProfitPaise: null } }; }
  };
  const server = createApiServer(service);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;

  const shortPage = await fetch(`${base}/owner`, { redirect: 'manual' });
  assert.equal(shortPage.status, 308);
  assert.equal(shortPage.headers.get('location'), '/owner/');
  const page = await fetch(`${base}/owner/`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  const pageText = await page.text();
  assert.match(pageText, /Owner Overview/);
  assert.match(pageText, /Product performance/);
  assert.match(pageText, /id="product-rows"/);
  assert.match(pageText, /Inventory snapshot/);
  assert.match(pageText, /id="inventory-rows"/);
  assert.match(pageText, /Sales activity/);
  assert.match(pageText, /id="activity-rows"/);
  assert.match(pageText, /Recorded money movements/);
  assert.match(pageText, /id="cashflow-rows"/);
  assert.match(pageText, /Profit readiness/);
  assert.match(pageText, /id="profit-missing-list"/);
  const script = await fetch(`${base}/owner/app.js`);
  assert.equal(script.status, 200);
  const scriptText = await script.text();
  assert.match(scriptText, /not save your password|session = result|sessionStorage/i);
  assert.match(scriptText, /\/v1\/dashboard\/products/);
  assert.match(scriptText, /\/v1\/dashboard\/inventory/);
  assert.match(scriptText, /\/v1\/dashboard\/activity/);
  assert.match(scriptText, /\/v1\/dashboard\/cashflow/);
  assert.match(scriptText, /\/v1\/dashboard\/profit-readiness/);

  const denied = await fetch(`${base}/v1/dashboard/overview?shopId=shop-1`);
  assert.equal(denied.status, 401);
  const deniedActivity = await fetch(`${base}/v1/dashboard/activity?shopId=shop-1&days=7`);
  assert.equal(deniedActivity.status, 401);
  const allowed = await fetch(`${base}/v1/dashboard/overview?shopId=shop-1`, { headers: { authorization: 'Bearer owner-token' } });
  assert.equal(allowed.status, 200);
  assert.equal((await allowed.json()).shopId, 'shop-1');
  const products = await fetch(`${base}/v1/dashboard/products?shopId=shop-1`, { headers: { authorization: 'Bearer owner-token' } });
  assert.equal(products.status, 200);
  assert.deepEqual((await products.json()).products, []);
  const inventory = await fetch(`${base}/v1/dashboard/inventory?shopId=shop-1`, { headers: { authorization: 'Bearer owner-token' } });
  assert.equal(inventory.status, 200);
  assert.deepEqual((await inventory.json()).recentMovements, []);
  const activity = await fetch(`${base}/v1/dashboard/activity?shopId=shop-1&days=7`, { headers: { authorization: 'Bearer owner-token' } });
  assert.equal(activity.status, 200);
  assert.equal((await activity.json()).days, 7);
  const invalidRange = await fetch(`${base}/v1/dashboard/activity?shopId=shop-1&days=14`, { headers: { authorization: 'Bearer owner-token' } });
  assert.equal(invalidRange.status, 400);
  const cashflowDenied = await fetch(`${base}/v1/dashboard/cashflow?shopId=shop-1&days=7`);
  assert.equal(cashflowDenied.status, 401);
  const cashflow = await fetch(`${base}/v1/dashboard/cashflow?shopId=shop-1&days=7`, { headers: { authorization: 'Bearer owner-token' } });
  assert.equal(cashflow.status, 200);
  assert.equal((await cashflow.json()).shopId, 'shop-1');
  const profitDenied = await fetch(`${base}/v1/dashboard/profit-readiness?shopId=shop-1&days=7`);
  assert.equal(profitDenied.status, 401);
  const profit = await fetch(`${base}/v1/dashboard/profit-readiness?shopId=shop-1&days=7`, { headers: { authorization: 'Bearer owner-token' } });
  assert.equal(profit.status, 200);
  const readiness = await profit.json();
  assert.equal(readiness.shopId, 'shop-1');
  assert.equal(readiness.status, 'not_calculable');
  assert.equal(readiness.figures.grossProfitPaise, null);
  const invalidProfitRange = await fetch(`${base}/v1/dashboard/profit-readiness?shopId=shop-1&days=14`, { headers: { authorization: 'Bearer owner-token' } });
  assert.equal(invalidProfitRange.status, 400);
  assert.deepEqual(calls, [
    { token: '', shopId: 'shop-1' },
    { token: '', shopId: 'shop-1' },
    { token: 'owner-token', shopId: 'shop-1' },
    { token: 'owner-token', shopId: 'shop-1' },
    { token: 'owner-token', shopId: 'shop-1' },
    { token: 'owner-token', shopId: 'shop-1' },
    { token: '', shopId: 'shop-1' },
    { token: 'owner-token', shopId: 'shop-1' },
    { token: '', shopId: 'shop-1' },
    { token: 'owner-token', shopId: 'shop-1' }
  ]);
});
