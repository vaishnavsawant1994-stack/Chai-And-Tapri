import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, applySupplierLedgerDelta, emptySupplierLedger, validateProjectionDelta } from '../src/service.mjs';

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

test('projection contract blocks customer-linked identifiers and ledgers', () => {
  const payment = emptyDelta();
  payment.paymentEntries.push({entryId:'repayment-1',orderNumber:null,customerId:'9876543210',amountPaise:500,method:'cash',status:'staff_declared',kind:'customer_repayment',relatedEntryId:null});
  invalid(payment, 'customer_identity_contract_pending');
  const credit = emptyDelta();
  credit.customerCredits.push({ creditId:'credit-1',customerId:'account-a',orderNumber:12,amountPaise:500,repaidPaise:0,reversedPaise:0 });
  invalid(credit, 'customer_identity_contract_pending');
  const repayment = emptyDelta();
  repayment.repayments.push({ repaymentId:'repayment-1',customerId:'account-a',amountPaise:500 });
  invalid(repayment, 'customer_identity_contract_pending');
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
