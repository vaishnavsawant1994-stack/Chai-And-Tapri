import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, applyCustomerLedgerDelta, applyProductCatalogueDelta, applyProductUnitLabelDelta, applySupplierLedgerDelta, applyStaffShiftLedgerDelta, authorizeProjectionForRole, emptyCustomerLedger, emptySupplierLedger, emptyStaffShiftLedger, validateProjectionDelta } from '../src/service.mjs';

const emptyDelta = () => ({
  kind:'projection_delta.v1',orders:[],stock:[],stockMovements:[],customerCredits:[],repayments:[],paymentEntries:[]
});
const invalid = (payload, code = 'invalid_projection_delta') => assert.throws(() => validateProjectionDelta(payload), (error) => error instanceof ApiError && error.status === 400 && error.code === code);

test('projection contract rejects empty deltas and accepts supported stock deltas', () => {
  invalid(emptyDelta(), 'empty_projection_delta');
  const payload = emptyDelta();
  payload.stock.push({ productId:'tea', quantity:0 });
  payload.stockMovements.push({ movementId:'movement-1', productId:'tea', quantityDelta:-2, orderNumber:12 });
  assert.equal(validateProjectionDelta(payload), true);
});

test('projection contract accepts optional unit labels while preserving legacy payloads and locking accepted labels', () => {
  const legacy = emptyDelta();
  legacy.stock.push({ productId:'tea', quantity:4 });
  assert.equal(validateProjectionDelta(legacy), true);
  const labeled = { ...emptyDelta(), stock:[{ productId:'tea', quantity:4, unitLabel:'cup' }], stockMovements:[{ movementId:'m1', productId:'tea', quantityDelta:1, orderNumber:null, unitLabel:'cup' }] };
  assert.equal(validateProjectionDelta(labeled), true);
  const labels = new Map();
  assert.equal(applyProductUnitLabelDelta(labels, labeled), true);
  assert.equal(labels.get('tea'), 'cup');
  assert.throws(() => applyProductUnitLabelDelta(labels, { ...emptyDelta(), stock:[{ productId:'tea', quantity:4, unitLabel:'piece' }] }), (error) => error.code === 'product_unit_label_conflict');
  assert.throws(() => validateProjectionDelta({ ...emptyDelta(), stock:[{ productId:'tea', quantity:4, unitLabel:' ' }] }), (error) => error.code === 'invalid_projection_delta');
  assert.throws(() => validateProjectionDelta({ ...emptyDelta(), stock:[{ productId:'tea', quantity:4, unitLabel:'cup\npack' }] }), (error) => error.code === 'invalid_projection_delta');
  assert.throws(() => validateProjectionDelta({ ...emptyDelta(), stock:[{ productId:'tea', quantity:4, unitLabel:'x'.repeat(25) }] }), (error) => error.code === 'invalid_projection_delta');
  const inconsistent = { ...emptyDelta(), stock:[{ productId:'tea', quantity:4, unitLabel:'cup' }], stockMovements:[{ movementId:'m2', productId:'tea', quantityDelta:1, orderNumber:null, unitLabel:'piece' }] };
  assert.equal(validateProjectionDelta(inconsistent), true);
  assert.throws(() => applyProductUnitLabelDelta(new Map(), inconsistent), (error) => error.code === 'product_unit_label_conflict');
});

test('cashiers may use established unit metadata but cannot establish product units', () => {
  const payload = { ...emptyDelta(), orders:[{ orderNumber:1, fulfillment:'TAKEN', payment:'UNPAID', paidPaise:0, creditPaise:0, refundedPaise:0, cashRefundedPaise:0, creditReversedPaise:0, salePosted:false, voided:false, revision:1, createdAtEpochMs:100, lines:[{ productId:'tea', quantity:1, deliveredQuantity:0, refundedQuantity:0, unitPricePaise:100, unitLabel:'cup' }] }] };
  assert.throws(() => authorizeProjectionForRole('cashier', payload), (error) => error instanceof ApiError && error.status === 403 && error.code === 'cashier_business_permission_denied');
  assert.equal(authorizeProjectionForRole('cashier', payload, new Map(), new Map(), new Map(), new Map([['tea', 'cup']])), true);
  assert.equal(authorizeProjectionForRole('owner', payload), true);
});

const catalogueProduct = (overrides = {}) => ({
  productId:'crate_water', name:'Water Bottle', category:'Drinks', unitPricePaise:2000, active:true, favorite:false,
  priceConfirmed:true, aliases:['water'], reorderThreshold:12, estimatedUnitCostPaise:125, scanCode:null,
  unitLabel:'bottle', purchaseUnitLabel:'crate', baseUnitsPerPurchaseUnit:12, ...overrides
});

test('catalogue projection validates full pack metadata, denies cashier writes, and locks conversion after use', () => {
  const payload = { ...emptyDelta(), catalogue:[catalogueProduct()] };
  assert.equal(validateProjectionDelta(payload), true);
  assert.throws(() => authorizeProjectionForRole('cashier', payload), (error) => error instanceof ApiError && error.status === 403 && error.code === 'cashier_catalogue_permission_denied');
  assert.equal(authorizeProjectionForRole('manager', payload), true);
  const catalogue = new Map(); const labels = new Map(); const used = new Set();
  assert.equal(applyProductCatalogueDelta(catalogue, payload, labels, used), true);
  assert.equal(catalogue.get('crate_water').baseUnitsPerPurchaseUnit, 12);
  assert.equal(labels.get('crate_water'), 'bottle');
  used.add('crate_water');
  applyProductCatalogueDelta(catalogue, { catalogue:[catalogueProduct({ unitPricePaise:2100 })] }, labels, used);
  assert.equal(catalogue.get('crate_water').unitPricePaise, 2100);
  assert.throws(() => applyProductCatalogueDelta(catalogue, { catalogue:[catalogueProduct({ baseUnitsPerPurchaseUnit:6 })] }, labels, used), (error) => error.code === 'product_conversion_conflict');
  assert.throws(() => applyProductCatalogueDelta(catalogue, { catalogue:[catalogueProduct({ unitLabel:'piece' })] }, labels, used), (error) => error.code === 'product_unit_label_conflict');
  invalid({ ...emptyDelta(), catalogue:[catalogueProduct({ baseUnitsPerPurchaseUnit:0 })] });
  invalid({ ...emptyDelta(), catalogue:[catalogueProduct({ aliases:'water' })] });
  invalid({ ...emptyDelta(), catalogue:[catalogueProduct({ extra:'unapproved' })] });
});

test('staff shift projection is privacy-minimized, validated and immutable after close', () => {
  const shift = { shiftId:'55555555-5555-4555-8555-555555555555', sessionType:'staff_shift', openedAtEpochMs:1000, closedAtEpochMs:null, openingCashPaise:500, expectedCashPaise:500, closingCashPaise:null };
  assert.equal(validateProjectionDelta({ ...emptyDelta(), staffShifts:[shift] }), true);
  assert.equal(validateProjectionDelta({ ...emptyDelta(), staffShifts:[{ ...shift, expectedCashPaise:-50 }] }), true);
  invalid({ ...emptyDelta(), staffShifts:[{ ...shift, operatorName:'Private name' }] });
  invalid({ ...emptyDelta(), staffShifts:[{ ...shift, closedAtEpochMs:2000 }] });
  const ledger = emptyStaffShiftLedger();
  applyStaffShiftLedgerDelta(ledger, { staffShifts:[shift] });
  applyStaffShiftLedgerDelta(ledger, { staffShifts:[{ ...shift, expectedCashPaise:450 }] });
  const closed = { ...shift, closedAtEpochMs:3000, expectedCashPaise:450, closingCashPaise:400 };
  applyStaffShiftLedgerDelta(ledger, { staffShifts:[closed] });
  applyStaffShiftLedgerDelta(ledger, { staffShifts:[closed] });
  assert.throws(() => applyStaffShiftLedgerDelta(ledger, { staffShifts:[{ ...closed, closingCashPaise:420 }] }), (error) => error.code === 'staff_shift_closed_identity_conflict');
  assert.throws(() => applyStaffShiftLedgerDelta(ledger, { staffShifts:[{ ...closed, openingCashPaise:600 }] }), (error) => error.code === 'staff_shift_identity_conflict');
});

test('projection contract rejects unknown payload kinds and unexpected fields', () => {
  invalid({ ...emptyDelta(), kind:'synthetic.anything' });
  invalid({ ...emptyDelta(), audit:'private operator note' });
});

test('projection contract requires exact order, line and integer paise fields', () => {
  const payload = emptyDelta();
  payload.orders.push({
    orderNumber:12,fulfillment:'GIVEN',payment:'STAFF_DECLARED',paidPaise:1500,creditPaise:0,refundedPaise:0,cashRefundedPaise:0,
    creditReversedPaise:0,salePosted:true,voided:false,revision:2,createdAtEpochMs:1,
    lines:[{productId:'chai',quantity:1,deliveredQuantity:1,refundedQuantity:0,unitPricePaise:1500}]
  });
  assert.equal(validateProjectionDelta(payload), true);
  payload.orders[0].lines[0].estimatedUnitCostPaise = 425;
  assert.equal(validateProjectionDelta(payload), true);
  payload.orders[0].lines[0].estimatedUnitCostPaise = null;
  assert.equal(validateProjectionDelta(payload), true);
  payload.orders[0].lines[0].estimatedUnitCostPaise = -1;
  invalid(payload);
  payload.orders[0].lines[0].estimatedUnitCostPaise = 1.25;
  invalid(payload);
  delete payload.orders[0].lines[0].estimatedUnitCostPaise;
  payload.orders[0].lines[0].unitPricePaise = 1.5;
  invalid(payload);
});

test('cashier may sync a split of cash and staff-declared UPI when tenders reconcile to the order delta', () => {
  const previousOrder = {
    orderNumber:12,fulfillment:'GIVEN',payment:'UNPAID',paidPaise:0,creditPaise:0,refundedPaise:0,cashRefundedPaise:0,
    creditReversedPaise:0,salePosted:true,voided:false,revision:2,createdAtEpochMs:1000,
    lines:[{productId:'chai',quantity:1,deliveredQuantity:1,refundedQuantity:0,unitPricePaise:1500}]
  };
  const updatedOrder = { ...previousOrder,payment:'STAFF_DECLARED',paidPaise:1500,revision:3 };
  const payload = {
    ...emptyDelta(),
    orders:[updatedOrder],
    paymentEntries:[
      {entryId:'split-cash',orderNumber:12,customerId:null,amountPaise:500,method:'cash',status:'staff_declared',kind:'order_collection',relatedEntryId:null},
      {entryId:'split-upi',orderNumber:12,customerId:null,amountPaise:1000,method:'upi',status:'staff_declared',kind:'order_collection',relatedEntryId:null}
    ]
  };
  assert.equal(validateProjectionDelta(payload), true);
  assert.equal(authorizeProjectionForRole('cashier', payload, new Map(), new Map(), new Map([[12, previousOrder]])), true);

  const unreconciled = structuredClone(payload);
  unreconciled.paymentEntries[1].amountPaise = 999;
  assert.throws(() => authorizeProjectionForRole('cashier', unreconciled, new Map(), new Map(), new Map([[12, previousOrder]])),
    (error) => error instanceof ApiError && error.status === 403 && error.code === 'cashier_business_permission_denied');
});

test('projection contract accepts opaque customer ledgers and rejects local identifiers', () => {
  const customerId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  const creditId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
  const repaymentId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
  const payment = emptyDelta();
  payment.paymentEntries.push({entryId:'repayment-1',orderNumber:null,customerId:'9876543210',amountPaise:500,method:'cash',status:'staff_declared',kind:'customer_repayment',relatedEntryId:null});
  invalid(payment);
  payment.paymentEntries[0].customerId = customerId;
  assert.equal(validateProjectionDelta(payment), true);
  const credit = emptyDelta();
  credit.customerCredits.push({ creditId,customerId,amountPaise:500,repaidPaise:100,reversedPaise:50 });
  assert.equal(validateProjectionDelta(credit), true);
  credit.customerCredits[0].orderNumber = 12;
  invalid(credit);
  const repayment = emptyDelta();
  repayment.repayments.push({ repaymentId,customerId,amountPaise:500 });
  assert.equal(validateProjectionDelta(repayment), true);
  repayment.repayments[0].customerId = 'account-a';
  invalid(repayment);
});

test('projection contract rejects oversized arrays, invalid enums and negative stock', () => {
  const tooMany = emptyDelta();
  tooMany.stock = Array.from({ length:501 }, (_, i) => ({ productId:`p${i}`, quantity:1 }));
  invalid(tooMany);
  const wrongState = emptyDelta();
  wrongState.orders.push({ orderNumber:1,fulfillment:'READY_TO_EAT' });
  invalid(wrongState);
  const negative = emptyDelta();
  negative.stock.push({ productId:'tea', quantity:-1 });
  invalid(negative);
});

test('projection contract accepts privacy-minimized declared expenses and legacy v1 payloads', () => {
  const legacy = emptyDelta();
  legacy.stock.push({ productId:'tea', quantity:0 });
  assert.equal(validateProjectionDelta(legacy), true);

  const payload = emptyDelta();
  payload.expenses = [{ expenseId:'expense-1', category:'electricity', amountPaise:3450, method:'cash', status:'staff_declared' }];
  assert.equal(validateProjectionDelta(payload), true);
  invalid({ ...payload, expenses: [{ ...payload.expenses[0], note:'personal details' }] });
  invalid({ ...payload, expenses: [{ ...payload.expenses[0], amountPaise:0 }] });
  invalid({ ...payload, expenses: [{ ...payload.expenses[0], category:'Rent / Shop Owner' }] });
  invalid({ ...payload, expenses: [{ ...payload.expenses[0], method:'udhar' }] });
});

test('supplier financial projection accepts only opaque IDs and privacy-minimized paise records', () => {
  const payload = emptyDelta();
  payload.purchaseReceipts = [{ receiptId:'11111111-1111-4111-8111-111111111111', supplierId:'22222222-2222-4222-8222-222222222222', totalPaise:5000 }];
  payload.supplierPayments = [{ paymentId:'33333333-3333-4333-8333-333333333333', supplierId:'22222222-2222-4222-8222-222222222222', amountPaise:1000, method:'cash', status:'staff_declared' }];
  payload.purchaseReturns = [{ returnId:'44444444-4444-4444-8444-444444444444', supplierId:'22222222-2222-4222-8222-222222222222', receiptId:'11111111-1111-4111-8111-111111111111', amountPaise:500 }];
  assert.equal(validateProjectionDelta(payload), true);
  invalid({ ...payload, supplierPayments:[{ ...payload.supplierPayments[0], phone:'private' }] });
  invalid({ ...payload, supplierPayments:[{ ...payload.supplierPayments[0], amountPaise:1.2 }] });
  invalid({ ...payload, purchaseReturns:[{ ...payload.purchaseReturns[0], receiptId:'not-a-uuid' }] });
  invalid({ ...payload, purchaseReceipts:[{ ...payload.purchaseReceipts[0], supplierId:'local-supplier-id' }] });
});

test('supplier ledger rejects replayed identities, mismatched returns and returns above receipt value', () => {
  const supplierId = '22222222-2222-4222-8222-222222222222';
  const receiptId = '11111111-1111-4111-8111-111111111111';
  const ledger = emptySupplierLedger();
  const receipt = { ...emptyDelta(), purchaseReceipts:[{ receiptId, supplierId, totalPaise:5000 }] };
  applySupplierLedgerDelta(ledger, receipt);
  assert.throws(() => applySupplierLedgerDelta(ledger, receipt), (error) => error.code === 'supplier_finance_id_conflict');
  assert.throws(() => applySupplierLedgerDelta(ledger, { ...emptyDelta(), purchaseReturns:[{ returnId:'33333333-3333-4333-8333-333333333333', supplierId, receiptId, amountPaise:5001 }] }), (error) => error.code === 'supplier_return_exceeds_receipt');
  assert.throws(() => applySupplierLedgerDelta(ledger, { ...emptyDelta(), purchaseReturns:[{ returnId:'44444444-4444-4444-8444-444444444444', supplierId:'55555555-5555-4555-8555-555555555555', receiptId, amountPaise:100 }] }), (error) => error.code === 'supplier_return_exceeds_receipt');
  applySupplierLedgerDelta(ledger, { ...emptyDelta(), purchaseReturns:[{ returnId:'66666666-6666-4666-8666-666666666666', supplierId, receiptId, amountPaise:5000 }] });
  assert.equal(ledger.totals.get(supplierId).returns, 5000);
});

test('customer ledger enforces stable credit identity, monotonic settlement and immutable repayment IDs', () => {
  const customerId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  const creditId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
  const repaymentId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
  const ledger = emptyCustomerLedger();
  const credit = { creditId, customerId, amountPaise:1000, repaidPaise:0, reversedPaise:0 };
  applyCustomerLedgerDelta(ledger, { customerCredits:[credit], repayments:[] });
  applyCustomerLedgerDelta(ledger, { customerCredits:[{ ...credit, repaidPaise:250 }], repayments:[] });
  assert.throws(() => applyCustomerLedgerDelta(ledger, { customerCredits:[{ ...credit, repaidPaise:100 }], repayments:[] }), (error) => error.code === 'customer_credit_identity_conflict');
  assert.throws(() => applyCustomerLedgerDelta(ledger, { customerCredits:[{ ...credit, amountPaise:1200 }], repayments:[] }), (error) => error.code === 'customer_credit_identity_conflict');
  const repayment = { repaymentId, customerId, amountPaise:100 };
  applyCustomerLedgerDelta(ledger, { customerCredits:[], repayments:[repayment] });
  applyCustomerLedgerDelta(ledger, { customerCredits:[], repayments:[repayment] });
  assert.throws(() => applyCustomerLedgerDelta(ledger, { customerCredits:[], repayments:[{ ...repayment, amountPaise:200 }] }), (error) => error.code === 'customer_repayment_identity_conflict');
});
