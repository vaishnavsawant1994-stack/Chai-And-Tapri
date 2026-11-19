import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, validateProjectionDelta } from '../src/service.mjs';

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
