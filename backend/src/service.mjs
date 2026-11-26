import { createHash, randomBytes, randomUUID, scrypt as scryptCallback, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { withTransaction } from './db.mjs';
import { ApiError } from './errors.mjs';
import { buildAuditHistory, buildCustomerBalances, buildDailySalesActivity, buildInventorySnapshot, buildOwnerInsights, buildProductPerformance, buildProfitReadiness, buildRecordedMoneyMovementSummary, buildShopCatalogue, buildShopOverview, buildSupplierBalances } from './dashboard.mjs';

export { ApiError } from './errors.mjs';

const scrypt = promisify(scryptCallback);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const hash = (value) => createHash('sha256').update(value).digest();
const isoAfterHours = (hours) => new Date(Date.now() + hours * 3_600_000);
const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const hasExactKeys = (value, keys) => object(value) && Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key));
const isText = (value, max = 160) => typeof value === 'string' && value.length > 0 && value.length <= max;
const isUnitLabel = (value) => typeof value === 'string' && value.length >= 1 && value.length <= 24 && value.trim() === value && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
const isInt = (value, { min = Number.MIN_SAFE_INTEGER, max = Number.MAX_SAFE_INTEGER } = {}) => Number.isSafeInteger(value) && value >= min && value <= max;
const enumIs = (value, values) => typeof value === 'string' && values.includes(value);
const STAFF_ROLES = ['manager', 'cashier'];
const CASHIER_PAYMENT_KINDS = new Map([
  ['order_collection', 'staff_declared'], ['payment_attempt', 'pending'],
  ['payment_attempt_cancelled', 'cancelled'], ['credit_sale', 'receivable'],
  ['customer_repayment', 'staff_declared']
]);
const validEmail = (value) => typeof value === 'string' && value.length <= 254 && /^\S+@\S+\.\S+$/.test(value);
const requireOwner = (actor) => { if (actor?.role !== 'owner') throw new ApiError(403, 'owner_access_required'); };

/** Enforce server-side sync scopes using the authenticated membership, never a client role claim. */
export function authorizeProjectionForRole(role, payload, stockBefore = new Map(), creditsBefore = new Map(), ordersBefore = new Map(), productUnitLabels = new Map()) {
  if (role === 'owner' || role === 'manager') return true;
  if (role !== 'cashier') throw new ApiError(403, 'business_sync_role_denied');
  if ((payload.catalogue ?? []).length > 0) throw new ApiError(403, 'cashier_catalogue_permission_denied');
  const privilegedFamilies = ['expenses', 'purchaseReceipts', 'supplierPayments', 'purchaseReturns'];
  if (privilegedFamilies.some((field) => (payload[field] ?? []).length > 0)) throw new ApiError(403, 'cashier_business_permission_denied');
  const labeledProducts = [
    ...(payload.orders ?? []).flatMap((order) => order.lines ?? []),
    ...(payload.stock ?? []), ...(payload.stockMovements ?? [])
  ].filter((item) => item.unitLabel !== undefined);
  if (labeledProducts.some((item) => !productUnitLabels.has(item.productId))) throw new ApiError(403, 'cashier_business_permission_denied');
  if ((payload.paymentEntries ?? []).some((entry) => CASHIER_PAYMENT_KINDS.get(entry.kind) !== entry.status || entry.amountPaise <= 0)) throw new ApiError(403, 'cashier_business_permission_denied');
  const currentOrders = new Map((payload.orders ?? []).map((order) => [order.orderNumber, order]));
  const expectedDeliveries = new Map();
  if ((payload.orders ?? []).some((order) => order.voided || order.fulfillment === 'CANCELLED' || order.refundedPaise > 0 || order.cashRefundedPaise > 0 || order.creditReversedPaise > 0 || order.payment === 'REFUNDED')) {
    throw new ApiError(403, 'cashier_business_permission_denied');
  }
  const paidByOrder = new Map();
  const creditByOrder = new Map();
  for (const entry of payload.paymentEntries ?? []) {
    if (entry.kind === 'customer_repayment') {
      if (entry.orderNumber !== null || entry.customerId === null) throw new ApiError(403, 'cashier_business_permission_denied');
      continue;
    }
    if (entry.orderNumber === null || !currentOrders.has(entry.orderNumber)) throw new ApiError(403, 'cashier_business_permission_denied');
    if (entry.kind === 'order_collection') paidByOrder.set(entry.orderNumber, (paidByOrder.get(entry.orderNumber) ?? 0) + entry.amountPaise);
    if (entry.kind === 'credit_sale') {
      if (entry.customerId === null) throw new ApiError(403, 'cashier_business_permission_denied');
      creditByOrder.set(entry.orderNumber, (creditByOrder.get(entry.orderNumber) ?? 0) + entry.amountPaise);
    }
  }
  for (const order of payload.orders ?? []) {
    const previous = ordersBefore.get(order.orderNumber);
    if (previous && (order.createdAtEpochMs !== previous.createdAtEpochMs || order.revision <= previous.revision ||
      order.paidPaise < previous.paidPaise || order.creditPaise < previous.creditPaise || (previous.salePosted && !order.salePosted) ||
      JSON.stringify(order.lines.map(({ productId, quantity, unitPricePaise }) => [productId, quantity, unitPricePaise]).sort()) !==
        JSON.stringify(previous.lines.map(({ productId, quantity, unitPricePaise }) => [productId, quantity, unitPricePaise]).sort()))) {
      throw new ApiError(403, 'cashier_business_permission_denied');
    }
    const paidDelta = order.paidPaise - (previous?.paidPaise ?? 0);
    const creditDelta = order.creditPaise - (previous?.creditPaise ?? 0);
    if (!Number.isSafeInteger(paidByOrder.get(order.orderNumber) ?? 0) || (paidByOrder.get(order.orderNumber) ?? 0) !== paidDelta ||
      !Number.isSafeInteger(creditByOrder.get(order.orderNumber) ?? 0) || (creditByOrder.get(order.orderNumber) ?? 0) !== creditDelta) {
      throw new ApiError(403, 'cashier_business_permission_denied');
    }
    const previousDelivered = new Map((previous?.lines ?? []).map((line) => [line.productId, line.deliveredQuantity]));
    for (const line of order.lines) {
      const deliveredDelta = line.deliveredQuantity - (previousDelivered.get(line.productId) ?? 0);
      if (deliveredDelta < 0 || (previous && line.refundedQuantity !== (previous.lines.find((item) => item.productId === line.productId)?.refundedQuantity ?? 0))) {
        throw new ApiError(403, 'cashier_business_permission_denied');
      }
      if (deliveredDelta > 0) expectedDeliveries.set(line.productId, (expectedDeliveries.get(line.productId) ?? 0) - deliveredDelta);
    }
  }
  const movementTotals = new Map();
  for (const movement of payload.stockMovements ?? []) {
    if (movement.orderNumber === null || movement.quantityDelta >= 0) throw new ApiError(403, 'cashier_business_permission_denied');
    const order = currentOrders.get(movement.orderNumber);
    if (!order || order.voided || order.refundedPaise > 0 || !order.lines.some((line) => line.productId === movement.productId && line.deliveredQuantity > 0)) {
      throw new ApiError(403, 'cashier_business_permission_denied');
    }
    const total = (movementTotals.get(movement.productId) ?? 0) + movement.quantityDelta;
    if (!Number.isSafeInteger(total)) throw new ApiError(403, 'cashier_stock_reconciliation_required');
    movementTotals.set(movement.productId, total);
  }
  if (movementTotals.size !== expectedDeliveries.size || [...expectedDeliveries].some(([productId, quantityDelta]) => movementTotals.get(productId) !== quantityDelta)) {
    throw new ApiError(403, 'cashier_business_permission_denied');
  }
  const stockChanges = new Map((payload.stock ?? []).map((item) => [item.productId, item.quantity]));
  if (stockChanges.size !== movementTotals.size) throw new ApiError(403, 'cashier_business_permission_denied');
  for (const [productId, delta] of movementTotals) {
    const before = stockBefore.get(productId);
    const after = stockChanges.get(productId);
    const expected = before + delta;
    if (!Number.isSafeInteger(before) || !Number.isSafeInteger(after) || !Number.isSafeInteger(expected) || after !== expected || after < 0) {
      throw new ApiError(403, 'cashier_stock_reconciliation_required');
    }
  }
  for (const credit of payload.customerCredits ?? []) {
    const previous = creditsBefore.get(credit.creditId);
    if (previous && (credit.customerId !== previous.customerId || credit.amountPaise !== previous.amountPaise ||
      credit.reversedPaise !== previous.reversedPaise || credit.repaidPaise < previous.repaidPaise)) {
      throw new ApiError(403, 'cashier_business_permission_denied');
    }
    if (!previous && credit.reversedPaise !== 0) throw new ApiError(403, 'cashier_business_permission_denied');
  }
  return true;
}

/** Validate the only Android payload contract currently eligible for upload. */
export function validateProjectionDelta(payload) {
  const requiredTopKeys = ['kind','orders','stock','stockMovements','customerCredits','repayments','paymentEntries'];
  const allowedTopKeys = [...requiredTopKeys, 'expenses', 'purchaseReceipts', 'supplierPayments', 'purchaseReturns', 'staffShifts', 'catalogue'];
  if (!object(payload) || requiredTopKeys.some((key) => !Object.hasOwn(payload, key)) ||
    Object.keys(payload).some((key) => !allowedTopKeys.includes(key)) || payload.kind !== 'projection_delta.v1') throw new ApiError(400, 'invalid_projection_delta');
  for (const key of requiredTopKeys.slice(1)) if (!Array.isArray(payload[key]) || payload[key].length > 500) throw new ApiError(400, 'invalid_projection_delta');
  const expenses = payload.expenses ?? [];
  const purchaseReceipts = payload.purchaseReceipts ?? [];
  const supplierPayments = payload.supplierPayments ?? [];
  const purchaseReturns = payload.purchaseReturns ?? [];
  const staffShifts = payload.staffShifts ?? [];
  const catalogue = payload.catalogue ?? [];
  if (![expenses, purchaseReceipts, supplierPayments, purchaseReturns, staffShifts, catalogue].every((items) => Array.isArray(items) && items.length <= 500)) throw new ApiError(400, 'invalid_projection_delta');
  if (payload.orders.length + payload.stock.length + payload.stockMovements.length + payload.customerCredits.length + payload.repayments.length + payload.paymentEntries.length + expenses.length + purchaseReceipts.length + supplierPayments.length + purchaseReturns.length + staffShifts.length + catalogue.length === 0) throw new ApiError(400, 'empty_projection_delta');

  const catalogueIds = new Set();
  const productKeys = ['productId','name','category','unitPricePaise','active','favorite','priceConfirmed','aliases','reorderThreshold','estimatedUnitCostPaise','scanCode','unitLabel','purchaseUnitLabel','baseUnitsPerPurchaseUnit'];
  for (const product of catalogue) {
    if (!hasExactKeys(product, productKeys) || !/^[a-z][a-z0-9_]{1,39}$/.test(product.productId ?? '') || catalogueIds.has(product.productId) ||
      typeof product.name !== 'string' || product.name.trim() !== product.name || product.name.length < 2 || product.name.length > 60 || /[\u0000-\u001f\u007f-\u009f]/.test(product.name) ||
      typeof product.category !== 'string' || product.category.trim() !== product.category || product.category.length < 2 || product.category.length > 40 || /[\u0000-\u001f\u007f-\u009f]/.test(product.category) ||
      !isInt(product.unitPricePaise, { min: 0, max: 10_000_000 }) || typeof product.active !== 'boolean' || typeof product.favorite !== 'boolean' || typeof product.priceConfirmed !== 'boolean' ||
      (product.active && product.priceConfirmed && product.unitPricePaise === 0) || !Array.isArray(product.aliases) || product.aliases.length > 50 ||
      product.aliases.some((alias) => typeof alias !== 'string' || alias.trim() !== alias || alias.length > 40 || /[\u0000-\u001f\u007f-\u009f]/.test(alias)) ||
      (product.reorderThreshold !== null && !isInt(product.reorderThreshold, { min: 1, max: 100_000 })) ||
      (product.estimatedUnitCostPaise !== null && !isInt(product.estimatedUnitCostPaise, { min: 0, max: 10_000_000 })) ||
      (product.scanCode !== null && (typeof product.scanCode !== 'string' || product.scanCode.trim() !== product.scanCode || product.scanCode.length > 128 || /[\u0000-\u001f\u007f-\u009f]/.test(product.scanCode))) ||
      !isUnitLabel(product.unitLabel) || !isUnitLabel(product.purchaseUnitLabel) || !isInt(product.baseUnitsPerPurchaseUnit, { min: 1, max: 100_000 })) throw new ApiError(400, 'invalid_projection_delta');
    catalogueIds.add(product.productId);
  }

  const orderKeys = ['orderNumber','fulfillment','payment','paidPaise','creditPaise','refundedPaise','cashRefundedPaise','creditReversedPaise','salePosted','voided','revision','createdAtEpochMs','lines'];
  const legacyLineKeys = ['productId','quantity','deliveredQuantity','refundedQuantity','unitPricePaise'];
  const costedLineKeys = [...legacyLineKeys, 'estimatedUnitCostPaise'];
  const unitLineKeys = [...legacyLineKeys, 'unitLabel'];
  const costedUnitLineKeys = [...costedLineKeys, 'unitLabel'];
  for (const order of payload.orders) {
    if (!hasExactKeys(order, orderKeys) || !isInt(order.orderNumber, { min: 1 }) ||
      !enumIs(order.fulfillment, ['TAKEN','PREPARING','READY','PARTIALLY_GIVEN','GIVEN','CANCELLED']) ||
      !enumIs(order.payment, ['UNPAID','PARTIALLY_PAID','STAFF_DECLARED','UPI_PENDING','CREDIT','SPLIT','REFUNDED','CANCELLED']) ||
      !['paidPaise','creditPaise','refundedPaise','cashRefundedPaise','creditReversedPaise','revision','createdAtEpochMs'].every((key) => isInt(order[key], { min: 0 })) ||
      typeof order.salePosted !== 'boolean' || typeof order.voided !== 'boolean' || !Array.isArray(order.lines) || order.lines.length > 100) throw new ApiError(400, 'invalid_projection_delta');
    for (const line of order.lines) {
      if (!(hasExactKeys(line, legacyLineKeys) || hasExactKeys(line, costedLineKeys) || hasExactKeys(line, unitLineKeys) || hasExactKeys(line, costedUnitLineKeys)) || !isText(line.productId, 120) || !isInt(line.quantity, { min: 1 }) ||
        !isInt(line.deliveredQuantity, { min: 0, max: line.quantity }) || !isInt(line.refundedQuantity, { min: 0, max: line.deliveredQuantity }) ||
        !isInt(line.unitPricePaise, { min: 0 }) || (Object.hasOwn(line, 'estimatedUnitCostPaise') && line.estimatedUnitCostPaise !== null && !isInt(line.estimatedUnitCostPaise, { min: 0, max: 10_000_000 })) ||
        (Object.hasOwn(line, 'unitLabel') && !isUnitLabel(line.unitLabel))) throw new ApiError(400, 'invalid_projection_delta');
    }
  }

  for (const item of payload.stock) {
    if (!(hasExactKeys(item, ['productId','quantity']) || hasExactKeys(item, ['productId','quantity','unitLabel'])) || !isText(item.productId, 120) || (item.quantity !== null && !isInt(item.quantity, { min: 0 })) || (Object.hasOwn(item, 'unitLabel') && !isUnitLabel(item.unitLabel))) throw new ApiError(400, 'invalid_projection_delta');
  }
  for (const item of payload.stockMovements) {
    if (!(hasExactKeys(item, ['movementId','productId','quantityDelta','orderNumber']) || hasExactKeys(item, ['movementId','productId','quantityDelta','orderNumber','unitLabel'])) || !isText(item.movementId, 160) ||
      !isText(item.productId, 120) || !isInt(item.quantityDelta) || item.quantityDelta === 0 ||
      (item.orderNumber !== null && !isInt(item.orderNumber, { min: 1 })) || (Object.hasOwn(item, 'unitLabel') && !isUnitLabel(item.unitLabel))) throw new ApiError(400, 'invalid_projection_delta');
  }
  const customerRecordIds = new Set();
  for (const credit of payload.customerCredits) {
    if (!hasExactKeys(credit, ['creditId','customerId','amountPaise','repaidPaise','reversedPaise']) || !UUID.test(credit.creditId ?? '') || !UUID.test(credit.customerId ?? '') ||
      customerRecordIds.has(credit.creditId) || !isInt(credit.amountPaise, { min: 1, max: 100_000_000 }) || !isInt(credit.repaidPaise, { min: 0, max: credit.amountPaise }) || !isInt(credit.reversedPaise, { min: 0, max: credit.amountPaise - credit.repaidPaise })) throw new ApiError(400, 'invalid_projection_delta');
    customerRecordIds.add(credit.creditId);
  }
  for (const repayment of payload.repayments) {
    if (!hasExactKeys(repayment, ['repaymentId','customerId','amountPaise']) || !UUID.test(repayment.repaymentId ?? '') || !UUID.test(repayment.customerId ?? '') ||
      customerRecordIds.has(repayment.repaymentId) || !isInt(repayment.amountPaise, { min: 1, max: 100_000_000 })) throw new ApiError(400, 'invalid_projection_delta');
    customerRecordIds.add(repayment.repaymentId);
  }
  const paymentKeys = ['entryId','orderNumber','customerId','amountPaise','method','status','kind','relatedEntryId'];
  const methods = ['cash','upi','udhar'];
  const statuses = ['staff_declared','pending','cancelled','staff_declared_refund','receivable','reversed'];
  const kinds = ['order_collection','payment_attempt','payment_attempt_cancelled','cash_refund','credit_reversal','credit_sale','customer_repayment'];
  for (const entry of payload.paymentEntries) {
    if (!hasExactKeys(entry, paymentKeys) || !isText(entry.entryId, 160) ||
      (entry.orderNumber !== null && !isInt(entry.orderNumber, { min: 1 })) || (entry.customerId !== null && !UUID.test(entry.customerId)) || !isInt(entry.amountPaise) ||
      !enumIs(entry.method, methods) || !enumIs(entry.status, statuses) || !enumIs(entry.kind, kinds) ||
      (entry.relatedEntryId !== null && !isText(entry.relatedEntryId, 160))) throw new ApiError(400, 'invalid_projection_delta');
  }
  for (const expense of expenses) {
    if (!hasExactKeys(expense, ['expenseId','category','amountPaise','method','status']) || !isText(expense.expenseId, 160) ||
      typeof expense.category !== 'string' || expense.category.length < 2 || expense.category.length > 40 || !/^[a-z][a-z0-9 _-]*$/.test(expense.category) ||
      !isInt(expense.amountPaise, { min: 1, max: 100_000_000 }) || !enumIs(expense.method, ['cash','upi','card']) || expense.status !== 'staff_declared') {
      throw new ApiError(400, 'invalid_projection_delta');
    }
  }
  const uniqueOpaqueIds = new Set();
  for (const [items, idKey, keys] of [
    [purchaseReceipts, 'receiptId', ['receiptId','supplierId','totalPaise']],
    [supplierPayments, 'paymentId', ['paymentId','supplierId','amountPaise','method','status']],
    [purchaseReturns, 'returnId', ['returnId','supplierId','receiptId','amountPaise']]
  ]) {
    for (const item of items) {
      if (!hasExactKeys(item, keys) || !UUID.test(item[idKey] ?? '') || !UUID.test(item.supplierId ?? '') ||
        uniqueOpaqueIds.has(item[idKey])) throw new ApiError(400, 'invalid_projection_delta');
      uniqueOpaqueIds.add(item[idKey]);
      if (idKey === 'receiptId' && !isInt(item.totalPaise, { min: 1, max: 100_000_000 })) throw new ApiError(400, 'invalid_projection_delta');
      if (idKey === 'paymentId' && (!isInt(item.amountPaise, { min: 1, max: 100_000_000 }) || !enumIs(item.method, ['cash','upi','card']) || item.status !== 'staff_declared')) throw new ApiError(400, 'invalid_projection_delta');
      if (idKey === 'returnId' && (!UUID.test(item.receiptId ?? '') || !isInt(item.amountPaise, { min: 1, max: 100_000_000 }))) throw new ApiError(400, 'invalid_projection_delta');
    }
  }
  const shiftIds = new Set();
  for (const shift of staffShifts) {
    if (!hasExactKeys(shift, ['shiftId','sessionType','openedAtEpochMs','closedAtEpochMs','openingCashPaise','expectedCashPaise','closingCashPaise']) ||
      !UUID.test(shift.shiftId ?? '') || shiftIds.has(shift.shiftId) || !enumIs(shift.sessionType, ['staff_shift','cash_drawer']) ||
      !isInt(shift.openedAtEpochMs, { min: 1 }) || !isInt(shift.openingCashPaise, { min: 0, max: 100_000_000 }) ||
      !isInt(shift.expectedCashPaise) ||
      (shift.closedAtEpochMs !== null && (!isInt(shift.closedAtEpochMs, { min: 1 }) || shift.closedAtEpochMs <= shift.openedAtEpochMs)) ||
      (shift.closingCashPaise !== null && !isInt(shift.closingCashPaise, { min: 0, max: 100_000_000 })) ||
      ((shift.closedAtEpochMs === null) !== (shift.closingCashPaise === null))) throw new ApiError(400, 'invalid_projection_delta');
    shiftIds.add(shift.shiftId);
  }
  return true;
}

/** Lock a product's display/count unit after its first synchronized appearance. */
export function applyProductUnitLabelDelta(labels, payload) {
  const incoming = new Map();
  const observe = (productId, label) => {
    if (label === undefined) return;
    const previousInDelta = incoming.get(productId);
    if (previousInDelta !== undefined && previousInDelta !== label) throw new ApiError(409, 'product_unit_label_conflict');
    const established = labels.get(productId);
    if (established !== undefined && established !== label) throw new ApiError(409, 'product_unit_label_conflict');
    incoming.set(productId, label);
  };
  for (const order of payload.orders ?? []) for (const line of order.lines ?? []) observe(line.productId, line.unitLabel);
  for (const item of payload.stock ?? []) observe(item.productId, item.unitLabel);
  for (const movement of payload.stockMovements ?? []) observe(movement.productId, movement.unitLabel);
  for (const [productId, label] of incoming) labels.set(productId, label);
  return true;
}

/** Keep accepted catalogue snapshots tenant-scoped in the event journal and freeze unit conversions after use. */
export function applyProductCatalogueDelta(catalogue, payload, unitLabels, usedProducts = new Set()) {
  for (const product of payload.catalogue ?? []) {
    const previous = catalogue.get(product.productId);
    const previousLabel = unitLabels.get(product.productId);
    const scanCode = product.scanCode?.toLocaleLowerCase('en-US');
    if (scanCode && [...catalogue.values()].some((candidate) => candidate.productId !== product.productId && candidate.scanCode?.toLocaleLowerCase('en-US') === scanCode)) {
      throw new ApiError(409, 'product_scan_code_conflict');
    }
    if (previousLabel !== undefined && previousLabel !== product.unitLabel) throw new ApiError(409, 'product_unit_label_conflict');
    const conversionChanged = previous && (previous.purchaseUnitLabel !== product.purchaseUnitLabel ||
      previous.baseUnitsPerPurchaseUnit !== product.baseUnitsPerPurchaseUnit || previous.unitLabel !== product.unitLabel);
    if ((conversionChanged && usedProducts.has(product.productId)) ||
        (!previous && usedProducts.has(product.productId) && (product.baseUnitsPerPurchaseUnit !== 1 || product.purchaseUnitLabel !== product.unitLabel))) {
      throw new ApiError(409, 'product_conversion_conflict');
    }
    catalogue.set(product.productId, product);
    unitLabels.set(product.productId, product.unitLabel);
  }
  return true;
}

function applyProductHistory(usedProducts, payload) {
  for (const order of payload.orders ?? []) for (const line of order.lines ?? []) usedProducts.add(line.productId);
  for (const item of payload.stock ?? []) usedProducts.add(item.productId);
  for (const movement of payload.stockMovements ?? []) usedProducts.add(movement.productId);
}

export function applySupplierLedgerDelta(ledger, payload) {
  const ids = [...(payload.purchaseReceipts ?? []).map((x) => x.receiptId), ...(payload.supplierPayments ?? []).map((x) => x.paymentId), ...(payload.purchaseReturns ?? []).map((x) => x.returnId)];
  if (ids.some((id) => ledger.eventIds.has(id))) throw new ApiError(409, 'supplier_finance_id_conflict');
  for (const receipt of payload.purchaseReceipts ?? []) ledger.receipts.set(receipt.receiptId, { supplierId: receipt.supplierId, totalPaise: receipt.totalPaise, returnedPaise: 0 });
  for (const payment of payload.supplierPayments ?? []) {
    ledger.totals.set(payment.supplierId, (ledger.totals.get(payment.supplierId) ?? { receipts: 0, payments: 0, returns: 0 }));
    const total = ledger.totals.get(payment.supplierId); total.payments = checkedAdd(total.payments, payment.amountPaise);
  }
  for (const receipt of payload.purchaseReceipts ?? []) {
    const total = ledger.totals.get(receipt.supplierId) ?? { receipts: 0, payments: 0, returns: 0 };
    total.receipts = checkedAdd(total.receipts, receipt.totalPaise); ledger.totals.set(receipt.supplierId, total);
  }
  for (const returned of payload.purchaseReturns ?? []) {
    const receipt = ledger.receipts.get(returned.receiptId);
    if (!receipt || receipt.supplierId !== returned.supplierId || checkedAdd(receipt.returnedPaise, returned.amountPaise) > receipt.totalPaise) throw new ApiError(409, 'supplier_return_exceeds_receipt');
    receipt.returnedPaise = checkedAdd(receipt.returnedPaise, returned.amountPaise);
    const total = ledger.totals.get(returned.supplierId) ?? { receipts: 0, payments: 0, returns: 0 };
    total.returns = checkedAdd(total.returns, returned.amountPaise); ledger.totals.set(returned.supplierId, total);
  }
  ids.forEach((id) => ledger.eventIds.add(id));
}

export function emptyCustomerLedger() { return { credits: new Map(), repayments: new Map() }; }

/** Prevent an opaque credit identity from changing customer/face value or moving backwards. */
export function applyCustomerLedgerDelta(ledger, payload) {
  for (const credit of payload.customerCredits ?? []) {
    if (ledger.repayments.has(credit.creditId)) throw new ApiError(409, 'customer_finance_identity_conflict');
    const previous = ledger.credits.get(credit.creditId);
    if (previous && (previous.customerId !== credit.customerId || previous.amountPaise !== credit.amountPaise ||
      credit.repaidPaise < previous.repaidPaise || credit.reversedPaise < previous.reversedPaise)) throw new ApiError(409, 'customer_credit_identity_conflict');
    ledger.credits.set(credit.creditId, credit);
  }
  for (const repayment of payload.repayments ?? []) {
    if (ledger.credits.has(repayment.repaymentId)) throw new ApiError(409, 'customer_finance_identity_conflict');
    const previous = ledger.repayments.get(repayment.repaymentId);
    if (previous && JSON.stringify(previous) !== JSON.stringify(repayment)) throw new ApiError(409, 'customer_repayment_identity_conflict');
    ledger.repayments.set(repayment.repaymentId, repayment);
  }
  const settledByCustomer = new Map();
  for (const repayment of ledger.repayments.values()) settledByCustomer.set(repayment.customerId, checkedAdd(settledByCustomer.get(repayment.customerId) ?? 0, repayment.amountPaise));
  const creditStateByCustomer = new Map();
  for (const credit of ledger.credits.values()) creditStateByCustomer.set(credit.customerId, checkedAdd(creditStateByCustomer.get(credit.customerId) ?? 0, credit.repaidPaise));
  for (const [customerId, amount] of settledByCustomer) if (amount > (creditStateByCustomer.get(customerId) ?? 0)) throw new ApiError(409, 'customer_repayments_exceed_settled_credit');
}

function checkedAdd(left, right) {
  const total = left + right;
  if (!Number.isSafeInteger(total)) throw new ApiError(422, 'supplier_total_out_of_range');
  return total;
}

export function emptySupplierLedger() { return { receipts: new Map(), totals: new Map(), eventIds: new Set() }; }

export function emptyStaffShiftLedger() { return new Map(); }

export function applyStaffShiftLedgerDelta(ledger, payload) {
  for (const shift of payload.staffShifts ?? []) {
    const previous = ledger.get(shift.shiftId);
    if (previous) {
      if (previous.sessionType !== shift.sessionType || previous.openedAtEpochMs !== shift.openedAtEpochMs || previous.openingCashPaise !== shift.openingCashPaise) throw new ApiError(409, 'staff_shift_identity_conflict');
      if (previous.closedAtEpochMs !== null && JSON.stringify(previous) !== JSON.stringify(shift)) throw new ApiError(409, 'staff_shift_closed_identity_conflict');
    }
    ledger.set(shift.shiftId, shift);
  }
}

export class SyncService {
  constructor(pool, { sessionHours = 8, maxEvents = 100, maxPayloadBytes = 64 * 1024 } = {}) {
    this.pool = pool; this.sessionHours = sessionHours; this.maxEvents = maxEvents; this.maxPayloadBytes = maxPayloadBytes;
  }

  async register({ email, password, displayName, businessName, shopName, deviceId, deviceLabel }) {
    email = String(email ?? '').trim().toLowerCase();
    if (!/^\S+@\S+\.\S+$/.test(email) || typeof password !== 'string' || password.length < 12 || password.length > 256) throw new ApiError(400, 'invalid_registration');
    for (const [value, min, max] of [[displayName, 2, 100], [businessName, 2, 120], [shopName, 2, 120], [deviceLabel, 1, 80]]) {
      if (typeof value !== 'string' || value.trim().length < min || value.trim().length > max) throw new ApiError(400, 'invalid_registration');
    }
    if (!UUID.test(deviceId ?? '')) throw new ApiError(400, 'invalid_device_id');
    const salt = randomBytes(16);
    const passwordHash = await scrypt(password, salt, 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
    const token = randomBytes(32).toString('base64url');
    const result = await withTransaction(this.pool, async (c) => {
      const user = await c.query('INSERT INTO users(email,display_name,password_salt,password_hash,password_parameters) VALUES($1,$2,$3,$4,$5) RETURNING user_id', [email, displayName.trim(), salt, passwordHash, { algorithm: 'scrypt', N: 16384, r: 8, p: 1, keyLength: 64 }]);
      const business = await c.query('INSERT INTO businesses(display_name) VALUES($1) RETURNING business_id', [businessName.trim()]);
      const shop = await c.query('INSERT INTO shops(business_id,display_name) VALUES($1,$2) RETURNING shop_id', [business.rows[0].business_id, shopName.trim()]);
      await c.query("SELECT set_config('app.current_business',$1,true)", [business.rows[0].business_id]);
      await c.query("SELECT set_config('app.current_user',$1,true)", [user.rows[0].user_id]);
      await c.query("INSERT INTO business_memberships(business_id,user_id,role) VALUES($1,$2,'owner')", [business.rows[0].business_id, user.rows[0].user_id]);
      await c.query("SELECT set_config('app.current_shop',$1,true)", [shop.rows[0].shop_id]);
      await c.query("INSERT INTO shop_memberships(business_id,shop_id,user_id,role) VALUES($1,$2,$3,'owner')", [business.rows[0].business_id, shop.rows[0].shop_id, user.rows[0].user_id]);
      await c.query('INSERT INTO devices(device_id,business_id,shop_id,label,registered_by) VALUES($1,$2,$3,$4,$5)', [deviceId, business.rows[0].business_id, shop.rows[0].shop_id, deviceLabel.trim(), user.rows[0].user_id]);
      await c.query('INSERT INTO sessions(token_hash,business_id,shop_id,user_id,device_id,expires_at) VALUES($1,$2,$3,$4,$5,$6)', [hash(token), business.rows[0].business_id, shop.rows[0].shop_id, user.rows[0].user_id, deviceId, isoAfterHours(this.sessionHours)]);
      return { userId: user.rows[0].user_id, businessId: business.rows[0].business_id, shopId: shop.rows[0].shop_id };
    });
    return { ...result, token };
  }

  async login({ email, password, shopId, deviceId }) {
    email = String(email ?? '').trim().toLowerCase();
    if (!UUID.test(shopId ?? '') || !UUID.test(deviceId ?? '') || typeof password !== 'string') throw new ApiError(400, 'invalid_login');
    const result = await withTransaction(this.pool, async (c) => {
      const lock = await c.query('SELECT failures, locked_until FROM auth_failures WHERE email_key=$1 FOR UPDATE', [email]);
      if (lock.rows[0]?.locked_until && new Date(lock.rows[0].locked_until) > new Date()) throw new ApiError(429, 'login_temporarily_locked');
      const found = await c.query('SELECT user_id,display_name,password_salt,password_hash,password_parameters FROM users WHERE email=$1 AND disabled_at IS NULL', [email]);
      let valid = false;
      if (found.rowCount) {
        const params = found.rows[0].password_parameters;
        const candidate = await scrypt(password, found.rows[0].password_salt, params.keyLength, { N: params.N, r: params.r, p: params.p, maxmem: 64 * 1024 * 1024 });
        valid = timingSafeEqual(candidate, found.rows[0].password_hash);
      }
      if (!valid) {
        await c.query(`INSERT INTO auth_failures(email_key,failures,window_started_at,locked_until) VALUES($1,1,now(),NULL)
          ON CONFLICT(email_key) DO UPDATE SET failures=CASE WHEN auth_failures.window_started_at < now()-interval '15 minutes' THEN 1 ELSE auth_failures.failures+1 END,
          window_started_at=CASE WHEN auth_failures.window_started_at < now()-interval '15 minutes' THEN now() ELSE auth_failures.window_started_at END,
          locked_until=CASE WHEN auth_failures.failures+1 >= 8 THEN now()+interval '15 minutes' ELSE NULL END`, [email]);
        return { failed: true };
      }
      await c.query("SELECT set_config('app.current_shop',$1,true)", [shopId]);
      const membership = await c.query(`SELECT m.business_id,m.shop_id,m.role,d.device_id FROM shop_memberships m JOIN devices d USING(business_id,shop_id)
        WHERE m.user_id=$1 AND m.shop_id=$2 AND d.device_id=$3 AND d.revoked_at IS NULL`, [found.rows[0].user_id, shopId, deviceId]);
      if (!membership.rowCount) return { denied: true };
      await c.query('DELETE FROM auth_failures WHERE email_key=$1', [email]);
      const token = randomBytes(32).toString('base64url'); const row = membership.rows[0];
      await c.query('INSERT INTO sessions(token_hash,business_id,shop_id,user_id,device_id,expires_at) VALUES($1,$2,$3,$4,$5,$6)', [hash(token), row.business_id, row.shop_id, found.rows[0].user_id, deviceId, isoAfterHours(this.sessionHours)]);
      return { token, userId: found.rows[0].user_id, shopId: row.shop_id, businessId: row.business_id, role: row.role };
    });
    if (result.failed) throw new ApiError(401, 'invalid_credentials');
    if (result.denied) throw new ApiError(403, 'shop_or_device_access_denied');
    return result;
  }

  async authenticate(token, shopId) {
    if (!token || !UUID.test(shopId ?? '')) throw new ApiError(401, 'unauthorized');
    const actor = await withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [shopId]);
      const { rows } = await c.query(`SELECT s.user_id,s.business_id,s.shop_id,s.device_id,m.role FROM sessions s
        JOIN shop_memberships m USING(business_id,shop_id,user_id) JOIN devices d USING(shop_id,device_id)
        JOIN users u USING(user_id) WHERE s.token_hash=$1 AND s.shop_id=$2 AND s.revoked_at IS NULL AND s.expires_at>now()
        AND d.revoked_at IS NULL AND u.disabled_at IS NULL`, [hash(token), shopId]);
      return rows[0] ?? null;
    });
    if (!actor) throw new ApiError(401, 'unauthorized');
    return actor;
  }

  async logout(token) {
    if (!token) throw new ApiError(401, 'unauthorized');
    await this.pool.query('UPDATE sessions SET revoked_at=now() WHERE token_hash=$1 AND revoked_at IS NULL', [hash(token)]);
  }

  async shopStaffMembers(actor) {
    requireOwner(actor);
    const { rows } = await withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      return c.query(`SELECT u.user_id, u.email, u.display_name, m.role, m.created_at
        FROM shop_memberships m JOIN users u USING(user_id)
        WHERE m.business_id=$1 AND m.shop_id=$2 ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'manager' THEN 1 ELSE 2 END, lower(u.display_name), u.user_id`,
      [actor.business_id, actor.shop_id]);
    });
    return { businessId: actor.business_id, shopId: actor.shop_id, members: rows.map((row) => ({
      userId: row.user_id, email: row.email, displayName: row.display_name, role: row.role, joinedAt: row.created_at
    })), evidence: { identity: 'Account email is self-asserted; this system does not verify email ownership.', access: 'Roles are current shop memberships. This endpoint does not provide per-command permissions or a complete employee audit trail.' } };
  }

  async shopStaffInvitations(actor) {
    requireOwner(actor);
    const { rows } = await withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      return c.query(`SELECT invitation_id, email, role, created_at, expires_at, accepted_at, revoked_at
        FROM staff_invitations WHERE business_id=$1 AND shop_id=$2 ORDER BY created_at DESC LIMIT 100`,
      [actor.business_id, actor.shop_id]);
    });
    return { businessId: actor.business_id, shopId: actor.shop_id, invitations: rows.map((row) => ({
      invitationId: row.invitation_id, email: row.email, role: row.role, createdAt: row.created_at,
      expiresAt: row.expires_at, acceptedAt: row.accepted_at, revokedAt: row.revoked_at,
      state: row.revoked_at ? 'revoked' : row.accepted_at ? 'accepted' : new Date(row.expires_at) <= new Date() ? 'expired' : 'pending'
    })), evidence: { invitation: 'Pending tokens are never returned by list; raw invitation secrets are returned once at creation and stored only as a hash.', identity: 'The invited email is not verified.' } };
  }

  async shopStaffAudit(actor) {
    requireOwner(actor);
    const { rows } = await withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      return c.query(`SELECT audit_id,actor_user_id,target_user_id,invitation_id,action,previous_role,next_role,occurred_at
        FROM shop_staff_audit WHERE business_id=$1 AND shop_id=$2 ORDER BY occurred_at DESC,audit_id DESC LIMIT 100`,
      [actor.business_id, actor.shop_id]);
    });
    return { businessId: actor.business_id, shopId: actor.shop_id, events: rows.map((row) => ({
      auditId: row.audit_id, actorUserId: row.actor_user_id, targetUserId: row.target_user_id, invitationId: row.invitation_id,
      action: row.action, previousRole: row.previous_role, nextRole: row.next_role, occurredAt: row.occurred_at
    })), evidence: 'Append-only metadata for staff invitations and membership changes; it is not a complete audit of business actions.' };
  }

  async createStaffInvitation(actor, { email, role }) {
    requireOwner(actor);
    email = String(email ?? '').trim().toLowerCase();
    if (!validEmail(email) || !STAFF_ROLES.includes(role)) throw new ApiError(400, 'invalid_staff_invitation');
    const token = randomBytes(32).toString('base64url');
    const expiresAt = new Date(Date.now() + 24 * 3_600_000);
    const row = await withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      await c.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`${actor.shop_id}:${email}`]);
      const existingMember = await c.query(`SELECT 1 FROM shop_memberships m JOIN users u USING(user_id)
        WHERE m.business_id=$1 AND m.shop_id=$2 AND lower(u.email)=$3`, [actor.business_id, actor.shop_id, email]);
      if (existingMember.rowCount) throw new ApiError(409, 'staff_member_already_exists');
      const retired = await c.query(`UPDATE staff_invitations SET revoked_at=now() WHERE business_id=$1 AND shop_id=$2 AND email=$3
        AND accepted_at IS NULL AND revoked_at IS NULL RETURNING invitation_id,role`, [actor.business_id, actor.shop_id, email]);
      for (const old of retired.rows) {
        await c.query(`INSERT INTO shop_staff_audit(business_id,shop_id,actor_user_id,invitation_id,action,previous_role)
          VALUES($1,$2,$3,$4,'invitation_revoked',$5)`, [actor.business_id, actor.shop_id, actor.user_id, old.invitation_id, old.role]);
      }
      const pending = await c.query(`SELECT count(*)::integer AS count FROM staff_invitations
        WHERE business_id=$1 AND shop_id=$2 AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at>now()`,
      [actor.business_id, actor.shop_id]);
      if (pending.rows[0].count >= 50) throw new ApiError(429, 'too_many_pending_staff_invitations');
      const inserted = await c.query(`INSERT INTO staff_invitations(business_id,shop_id,email,role,token_hash,invited_by,expires_at)
        VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING invitation_id,created_at,expires_at`,
      [actor.business_id, actor.shop_id, email, role, hash(token), actor.user_id, expiresAt]);
      await c.query(`INSERT INTO shop_staff_audit(business_id,shop_id,actor_user_id,invitation_id,action,next_role)
        VALUES($1,$2,$3,$4,'invitation_created',$5)`, [actor.business_id, actor.shop_id, actor.user_id, inserted.rows[0].invitation_id, role]);
      return inserted.rows[0];
    });
    return { invitationId: row.invitation_id, email, role, createdAt: row.created_at, expiresAt: row.expires_at, invitationToken: token,
      evidence: 'Share the one-time secret through a trusted channel. It expires in 24 hours. The invited email is not verified; do not treat it as proof of identity.' };
  }

  async revokeStaffInvitation(actor, invitationId) {
    requireOwner(actor);
    if (!UUID.test(invitationId ?? '')) throw new ApiError(400, 'invalid_staff_invitation');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const result = await c.query(`UPDATE staff_invitations SET revoked_at=now()
        WHERE invitation_id=$1 AND business_id=$2 AND shop_id=$3 AND revoked_at IS NULL AND accepted_at IS NULL RETURNING role`,
      [invitationId, actor.business_id, actor.shop_id]);
      if (!result.rowCount) throw new ApiError(404, 'staff_invitation_not_found');
      await c.query(`INSERT INTO shop_staff_audit(business_id,shop_id,actor_user_id,invitation_id,action,previous_role)
        VALUES($1,$2,$3,$4,'invitation_revoked',$5)`, [actor.business_id, actor.shop_id, actor.user_id, invitationId, result.rows[0].role]);
      return { invitationId, state: 'revoked' };
    });
  }

  async setStaffMemberRole(actor, userId, role) {
    requireOwner(actor);
    if (!UUID.test(userId ?? '') || !STAFF_ROLES.includes(role) || userId === actor.user_id) throw new ApiError(400, 'invalid_staff_member_update');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const existing = await c.query(`SELECT role FROM shop_memberships
        WHERE business_id=$1 AND shop_id=$2 AND user_id=$3 AND role IN ('manager','cashier') FOR UPDATE`,
      [actor.business_id, actor.shop_id, userId]);
      if (!existing.rowCount) throw new ApiError(404, 'staff_member_not_found');
      const previousRole = existing.rows[0].role;
      if (previousRole === role) return { userId, role, changed: false };
      const result = await c.query(`UPDATE shop_memberships SET role=$4
        WHERE business_id=$1 AND shop_id=$2 AND user_id=$3 RETURNING user_id,role`,
      [actor.business_id, actor.shop_id, userId, role]);
      await c.query(`INSERT INTO shop_staff_audit(business_id,shop_id,actor_user_id,target_user_id,action,previous_role,next_role)
        VALUES($1,$2,$3,$4,'role_changed',$5,$6)`, [actor.business_id, actor.shop_id, actor.user_id, userId, previousRole, role]);
      return { userId: result.rows[0].user_id, role: result.rows[0].role, changed: true };
    });
  }

  async removeStaffMember(actor, userId) {
    requireOwner(actor);
    if (!UUID.test(userId ?? '') || userId === actor.user_id) throw new ApiError(400, 'invalid_staff_member_update');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const member = await c.query(`SELECT role FROM shop_memberships WHERE business_id=$1 AND shop_id=$2 AND user_id=$3 FOR UPDATE`,
        [actor.business_id, actor.shop_id, userId]);
      if (!member.rowCount || !STAFF_ROLES.includes(member.rows[0].role)) throw new ApiError(404, 'staff_member_not_found');
      await c.query(`INSERT INTO shop_staff_audit(business_id,shop_id,actor_user_id,target_user_id,action,previous_role)
        VALUES($1,$2,$3,$4,'membership_removed',$5)`, [actor.business_id, actor.shop_id, actor.user_id, userId, member.rows[0].role]);
      await c.query('UPDATE sessions SET revoked_at=now() WHERE business_id=$1 AND shop_id=$2 AND user_id=$3 AND revoked_at IS NULL',
        [actor.business_id, actor.shop_id, userId]);
      await c.query('DELETE FROM shop_memberships WHERE business_id=$1 AND shop_id=$2 AND user_id=$3', [actor.business_id, actor.shop_id, userId]);
      return { userId, state: 'removed', sessionsRevoked: true };
    });
  }

  async acceptStaffInvitation({ invitationToken, displayName, password, deviceId, deviceLabel }) {
    if (typeof invitationToken !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(invitationToken) ||
      typeof password !== 'string' || password.length < 12 || password.length > 256 ||
      typeof displayName !== 'string' || displayName.trim().length < 2 || displayName.trim().length > 100 ||
      typeof deviceLabel !== 'string' || deviceLabel.trim().length < 1 || deviceLabel.trim().length > 80 || !UUID.test(deviceId ?? '')) {
      throw new ApiError(400, 'invalid_staff_invitation');
    }
    const token = randomBytes(32).toString('base64url');
    const result = await withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.staff_invitation_hash',$1,true)", [hash(invitationToken).toString('hex')]);
      const inviteResult = await c.query(`SELECT invitation_id,business_id,shop_id,email,role,invited_by,expires_at,revoked_at,accepted_at
        FROM staff_invitations WHERE token_hash=$1`, [hash(invitationToken)]);
      if (!inviteResult.rowCount) throw new ApiError(401, 'invalid_staff_invitation');
      await c.query("SELECT set_config('app.current_shop',$1,true)", [inviteResult.rows[0].shop_id]);
      const lockedInvite = await c.query(`SELECT invitation_id,business_id,shop_id,email,role,invited_by,expires_at,revoked_at,accepted_at
        FROM staff_invitations WHERE invitation_id=$1 FOR UPDATE`, [inviteResult.rows[0].invitation_id]);
      const invitation = lockedInvite.rows[0];
      if (invitation.revoked_at || invitation.accepted_at || new Date(invitation.expires_at) <= new Date()) throw new ApiError(401, 'invalid_staff_invitation');
      const existing = await c.query(`SELECT user_id,display_name,password_salt,password_hash,password_parameters,disabled_at
        FROM users WHERE email=$1`, [invitation.email]);
      let userId; let userName;
      if (existing.rowCount) {
        const user = existing.rows[0];
        if (user.disabled_at) throw new ApiError(403, 'staff_account_unavailable');
        const params = user.password_parameters;
        const candidate = await scrypt(password, user.password_salt, params.keyLength, { N: params.N, r: params.r, p: params.p, maxmem: 64 * 1024 * 1024 });
        if (!timingSafeEqual(candidate, user.password_hash)) throw new ApiError(401, 'invalid_staff_credentials');
        userId = user.user_id; userName = user.display_name;
      } else {
        const salt = randomBytes(16);
        const passwordHash = await scrypt(password, salt, 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
        const created = await c.query(`INSERT INTO users(email,display_name,password_salt,password_hash,password_parameters)
          VALUES($1,$2,$3,$4,$5) RETURNING user_id,display_name`,
        [invitation.email, displayName.trim(), salt, passwordHash, { algorithm: 'scrypt', N: 16384, r: 8, p: 1, keyLength: 64 }]);
        userId = created.rows[0].user_id; userName = created.rows[0].display_name;
      }
      const member = await c.query('SELECT role FROM shop_memberships WHERE business_id=$1 AND shop_id=$2 AND user_id=$3',
        [invitation.business_id, invitation.shop_id, userId]);
      if (member.rowCount) throw new ApiError(409, 'staff_member_already_exists');
      await c.query('INSERT INTO shop_memberships(business_id,shop_id,user_id,role) VALUES($1,$2,$3,$4)',
        [invitation.business_id, invitation.shop_id, userId, invitation.role]);
      const device = await c.query(`INSERT INTO devices(device_id,business_id,shop_id,label,registered_by) VALUES($1,$2,$3,$4,$5)
        ON CONFLICT(shop_id,device_id) DO NOTHING RETURNING device_id`,
      [deviceId, invitation.business_id, invitation.shop_id, deviceLabel.trim(), invitation.invited_by]);
      if (!device.rowCount) throw new ApiError(409, 'staff_device_already_registered');
      await c.query('UPDATE staff_invitations SET accepted_at=now(),accepted_user_id=$2 WHERE invitation_id=$1', [invitation.invitation_id, userId]);
      await c.query(`INSERT INTO shop_staff_audit(business_id,shop_id,actor_user_id,target_user_id,invitation_id,action,next_role)
        VALUES($1,$2,$3,$4,$5,'invitation_accepted',$6)`, [invitation.business_id, invitation.shop_id, invitation.invited_by, userId, invitation.invitation_id, invitation.role]);
      await c.query('INSERT INTO sessions(token_hash,business_id,shop_id,user_id,device_id,expires_at) VALUES($1,$2,$3,$4,$5,$6)',
        [hash(token), invitation.business_id, invitation.shop_id, userId, deviceId, isoAfterHours(this.sessionHours)]);
      return { userId, displayName: userName, businessId: invitation.business_id, shopId: invitation.shop_id, role: invitation.role };
    });
    return { ...result, token };
  }

  async shopOverview(actor) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope,received_at FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      return buildShopOverview({
        shopId: actor.shop_id,
        shopName: shop.rows[0].display_name,
        serverRevision: Number(shop.rows[0].server_revision),
        events: journal.rows
      });
    });
  }

  async ownerBranches(actor) {
    requireOwner(actor);
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      await c.query("SELECT set_config('app.current_business',$1,true)", [actor.business_id]);
      await c.query("SELECT set_config('app.current_user',$1,true)", [actor.user_id]);
      const owner = await c.query(`SELECT 1 FROM business_memberships
        WHERE business_id=$1 AND user_id=$2 AND role='owner'`, [actor.business_id, actor.user_id]);
      if (!owner.rowCount) throw new ApiError(403, 'business_owner_access_required');
      const { rows } = await c.query(`SELECT shop_id,display_name,server_revision,created_at
        FROM shops WHERE business_id=$1 ORDER BY lower(display_name),shop_id`, [actor.business_id]);
      const shopIds = rows.map((row) => row.shop_id);
      const deviceResult = shopIds.length ? await c.query(`SELECT shop_id,device_id,label,created_at,revoked_at
        FROM devices WHERE business_id=$1 AND shop_id=ANY($2::uuid[])
        ORDER BY shop_id,lower(label),device_id`, [actor.business_id, shopIds]) : { rows: [] };
      const devicesByShop = new Map();
      for (const device of deviceResult.rows) {
        const list = devicesByShop.get(device.shop_id) ?? [];
        list.push({ deviceId: device.device_id, label: device.label, createdAt: device.created_at,
          revoked: device.revoked_at !== null });
        devicesByShop.set(device.shop_id, list);
      }
      return {
        businessId: actor.business_id,
        branches: rows.map((row) => ({ shopId: row.shop_id, displayName: row.display_name,
          serverRevision: Number(row.server_revision), createdAt: row.created_at, current: row.shop_id === actor.shop_id,
          devices: devicesByShop.get(row.shop_id) ?? [] })),
        evidence: {
          scope: 'Shop directory for the authenticated business owner; branch list is scoped to the session business.',
          limitation: 'This endpoint exposes branch and device identifiers to the authenticated business owner, not branch financial data. Device IDs are identifiers, not credentials; owner authentication is still required. Each branch needs its own device session for operational or financial views.'
        }
      };
    });
  }

  async createBusinessBranch(actor, { displayName, deviceLabel }) {
    requireOwner(actor);
    if (typeof displayName !== 'string' || displayName.trim().length < 2 || displayName.trim().length > 120 ||
        typeof deviceLabel !== 'string' || deviceLabel.trim().length < 1 || deviceLabel.trim().length > 80) {
      throw new ApiError(400, 'invalid_branch_details');
    }
    const deviceId = randomUUID();
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      await c.query("SELECT set_config('app.current_business',$1,true)", [actor.business_id]);
      await c.query("SELECT set_config('app.current_user',$1,true)", [actor.user_id]);
      const owner = await c.query(`SELECT 1 FROM business_memberships
        WHERE business_id=$1 AND user_id=$2 AND role='owner'`, [actor.business_id, actor.user_id]);
      if (!owner.rowCount) throw new ApiError(403, 'business_owner_access_required');
      const branch = await c.query(`INSERT INTO shops(business_id,display_name)
        VALUES($1,$2) RETURNING shop_id,display_name,server_revision,created_at`, [actor.business_id, displayName.trim()]);
      const row = branch.rows[0];
      await c.query("SELECT set_config('app.current_shop',$1,true)", [row.shop_id]);
      await c.query("INSERT INTO shop_memberships(business_id,shop_id,user_id,role) VALUES($1,$2,$3,'owner')",
        [actor.business_id, row.shop_id, actor.user_id]);
      await c.query('INSERT INTO devices(device_id,business_id,shop_id,label,registered_by) VALUES($1,$2,$3,$4,$5)',
        [deviceId, actor.business_id, row.shop_id, deviceLabel.trim(), actor.user_id]);
      await c.query("INSERT INTO business_branch_audit(business_id,shop_id,actor_user_id,action) VALUES($1,$2,$3,'branch_created')",
        [actor.business_id, row.shop_id, actor.user_id]);
      return {
        businessId: actor.business_id,
        branch: { shopId: row.shop_id, displayName: row.display_name, serverRevision: Number(row.server_revision), createdAt: row.created_at, current: false },
        deviceId,
        evidence: 'A device identity and owner membership were created transactionally. Use the owner account credentials with this new shop ID and device ID to establish a separate branch session.'
      };
    });
  }

  async revokeBusinessDevice(actor, { targetShopId, deviceId }) {
    requireOwner(actor);
    if (!UUID.test(targetShopId ?? '') || !UUID.test(deviceId ?? '')) throw new ApiError(400, 'invalid_device_identity');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      await c.query("SELECT set_config('app.current_business',$1,true)", [actor.business_id]);
      await c.query("SELECT set_config('app.current_user',$1,true)", [actor.user_id]);
      const owner = await c.query(`SELECT 1 FROM business_memberships
        WHERE business_id=$1 AND user_id=$2 AND role='owner'`, [actor.business_id, actor.user_id]);
      if (!owner.rowCount) throw new ApiError(403, 'business_owner_access_required');
      const target = await c.query(`SELECT shop_id,device_id,revoked_at FROM devices
        WHERE business_id=$1 AND shop_id=$2 AND device_id=$3 FOR UPDATE`,
      [actor.business_id, targetShopId, deviceId]);
      if (!target.rowCount) throw new ApiError(404, 'business_device_not_found');
      if (target.rows[0].revoked_at) return { shopId: targetShopId, deviceId, state: 'already_revoked', revokedSessionCount: 0 };
      if (targetShopId === actor.shop_id && deviceId === actor.device_id) throw new ApiError(409, 'current_device_cannot_be_revoked');
      const revoked = await c.query(`UPDATE devices SET revoked_at=now()
        WHERE business_id=$1 AND shop_id=$2 AND device_id=$3 AND revoked_at IS NULL`,
      [actor.business_id, targetShopId, deviceId]);
      if (revoked.rowCount !== 1) throw new ApiError(409, 'business_device_state_conflict');
      const sessions = await c.query(`UPDATE sessions SET revoked_at=now()
        WHERE business_id=$1 AND shop_id=$2 AND device_id=$3 AND revoked_at IS NULL`,
      [actor.business_id, targetShopId, deviceId]);
      await c.query(`INSERT INTO business_branch_audit(business_id,shop_id,actor_user_id,action,device_id)
        VALUES($1,$2,$3,'device_revoked',$4)`, [actor.business_id, targetShopId, actor.user_id, deviceId]);
      return { shopId: targetShopId, deviceId, state: 'revoked', revokedSessionCount: sessions.rowCount };
    });
  }

  async shopOwnerInsights(actor) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope,received_at FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      return buildOwnerInsights({ shopId: actor.shop_id, shopName: shop.rows[0].display_name, serverRevision: Number(shop.rows[0].server_revision), events: journal.rows });
    });
  }

  async shopProductPerformance(actor) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope,received_at FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      return buildProductPerformance({
        shopId: actor.shop_id,
        shopName: shop.rows[0].display_name,
        serverRevision: Number(shop.rows[0].server_revision),
        events: journal.rows
      });
    });
  }

  async shopCatalogue(actor) {
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      return buildShopCatalogue({
        shopId: actor.shop_id,
        shopName: shop.rows[0].display_name,
        serverRevision: Number(shop.rows[0].server_revision),
        events: journal.rows
      });
    });
  }

  async shopInventorySnapshot(actor) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope,received_at FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      return buildInventorySnapshot({
        shopId: actor.shop_id,
        shopName: shop.rows[0].display_name,
        serverRevision: Number(shop.rows[0].server_revision),
        events: journal.rows
      });
    });
  }

  async shopSupplierBalances(actor) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope,received_at FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      return buildSupplierBalances({ shopId: actor.shop_id, shopName: shop.rows[0].display_name, serverRevision: Number(shop.rows[0].server_revision), events: journal.rows });
    });
  }

  async shopCustomerBalances(actor) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope,received_at FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      return buildCustomerBalances({ shopId: actor.shop_id, shopName: shop.rows[0].display_name, serverRevision: Number(shop.rows[0].server_revision), events: journal.rows });
    });
  }

  async shopDailySalesActivity(actor, days = 30) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope,received_at FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      return buildDailySalesActivity({
        shopId: actor.shop_id,
        shopName: shop.rows[0].display_name,
        serverRevision: Number(shop.rows[0].server_revision),
        events: journal.rows,
        days
      });
    });
  }

  async shopRecordedMoneyMovements(actor, days = 30) {
    const activity = await this.shopDailySalesActivity(actor, days);
    return buildRecordedMoneyMovementSummary(activity);
  }

  async shopProfitReadiness(actor, days = 30) {
    const activity = await this.shopDailySalesActivity(actor, days);
    return buildProfitReadiness(activity);
  }

  async shopAuditHistory(actor, limit = 100, beforeRevision = null) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new ApiError(400, 'invalid_audit_limit');
    if (beforeRevision !== null && (!Number.isSafeInteger(beforeRevision) || beforeRevision < 1)) throw new ApiError(400, 'invalid_audit_cursor');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const query = `SELECT e.server_revision,e.event_id,e.event_type,e.base_revision,e.envelope,e.received_at,
        e.actor_user_id,e.actor_role,u.display_name AS actor_display_name
        FROM sync_events e LEFT JOIN users u ON u.user_id=e.actor_user_id
        WHERE e.shop_id=$1${beforeRevision === null ? '' : ' AND e.server_revision<$2'} ORDER BY e.server_revision DESC LIMIT $${beforeRevision === null ? 2 : 3}`;
      const result = await c.query(query, beforeRevision === null
        ? [actor.shop_id, limit + 1]
        : [actor.shop_id, beforeRevision, limit + 1]);
      const hasMore = result.rows.length > limit;
      const pageRows = result.rows.slice(0, limit);
      const nextBeforeRevision = hasMore ? Number(pageRows.at(-1)?.server_revision) : null;
      const report = buildAuditHistory({
        shopId: actor.shop_id, shopName: shop.rows[0].display_name,
        serverRevision: Number(shop.rows[0].server_revision), events: pageRows.reverse(), limit
      });
      return { ...report, hasMore, nextBeforeRevision };
    });
  }

  async shopSyncConflicts(actor, limit = 100, before = null) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new ApiError(400, 'invalid_conflict_limit');
    if (before !== null && (!before || typeof before.createdAt !== 'string' || Number.isNaN(Date.parse(before.createdAt)) || !UUID.test(before.conflictId ?? ''))) throw new ApiError(400, 'invalid_conflict_cursor');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const result = await c.query(`SELECT conflict_id,event_id,command_id,device_id,expected_revision,current_revision,created_at,envelope
        FROM sync_conflicts WHERE shop_id=$1 AND resolved_at IS NULL${before === null ? '' : ' AND (created_at,conflict_id)<($2::timestamptz,$3::uuid)'}
        ORDER BY created_at DESC,conflict_id DESC LIMIT $${before === null ? 2 : 4}`,
      before === null ? [actor.shop_id, limit + 1] : [actor.shop_id, before.createdAt, before.conflictId, limit + 1]);
      const hasMore = result.rows.length > limit;
      const rows = result.rows.slice(0, limit);
      return {
        shopId: actor.shop_id, shopName: shop.rows[0].display_name, serverRevision: Number(shop.rows[0].server_revision),
        returnedConflictCount: rows.length, hasMore,
        nextBefore: hasMore && rows.length ? {
          createdAt: rows.at(-1).created_at instanceof Date ? rows.at(-1).created_at.toISOString() : new Date(rows.at(-1).created_at).toISOString(),
          conflictId: rows.at(-1).conflict_id
        } : null,
        conflicts: rows.map((row) => {
          const payload = row.envelope?.payload;
          return {
            conflictId: row.conflict_id, eventId: row.event_id, commandId: row.command_id, deviceId: row.device_id,
            expectedRevision: Number(row.expected_revision), currentRevision: Number(row.current_revision),
            createdAt: row.created_at instanceof Date ? row.created_at.toISOString() : new Date(row.created_at).toISOString(),
            changeCounts: payload?.kind === 'projection_delta.v1' ? {
              orders: payload.orders?.length ?? 0, stock: payload.stock?.length ?? 0,
              payments: payload.paymentEntries?.length ?? 0, expenses: payload.expenses?.length ?? 0,
              purchases: (payload.purchaseReceipts?.length ?? 0) + (payload.purchaseReturns?.length ?? 0),
              customerRecords: (payload.customerCredits?.length ?? 0) + (payload.repayments?.length ?? 0)
            } : { unknown: true }
          };
        }),
        evidence: {
          scope: 'Open stale-revision submissions stored by the server for this authenticated shop.',
          privacy: 'Raw event payloads, product identifiers, customer details and payment details are not returned.',
          action: 'This queue is review-only. No conflicted event is automatically merged, replayed or accepted. Resolve it on the originating device or through an explicitly authorized correction workflow.',
          limitation: 'A stored conflict is evidence of a rejected revision, not proof that the proposed business activity occurred.'
        }
      };
    });
  }

  async shopStaffShifts(actor) {
    if (actor.role !== 'owner') throw new ApiError(403, 'owner_access_required');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT display_name,server_revision,business_id FROM shops WHERE shop_id=$1', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT server_revision,envelope,received_at FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      const ledger = emptyStaffShiftLedger();
      for (const row of journal.rows) if (row.envelope?.payload?.kind === 'projection_delta.v1') applyStaffShiftLedgerDelta(ledger, row.envelope.payload);
      const shifts = [...ledger.values()].sort((a, b) => b.openedAtEpochMs - a.openedAtEpochMs || a.shiftId.localeCompare(b.shiftId));
      return { shopId: actor.shop_id, shopName: shop.rows[0].display_name, serverRevision: Number(shop.rows[0].server_revision), shiftCount: shifts.length, shifts,
        evidence: { scope: 'Summaries from server-accepted device declarations only.', identity: 'Operator labels and notes are omitted; these records do not authenticate a staff member.', cash: 'Opening, expected and counted cash are device-reported and are not independently reconciled.', time: 'Shift times use the device clock and are not server-verified.', limitation: 'This is an operational shift summary, not attendance, payroll or a staff-performance report.' } };
    });
  }

  async submitEvents(actor, events) {
    if (!Array.isArray(events) || events.length < 1 || events.length > this.maxEvents) throw new ApiError(400, 'invalid_event_batch');
    return withTransaction(this.pool, async (c) => {
      await c.query("SELECT set_config('app.current_shop',$1,true)", [actor.shop_id]);
      const shop = await c.query('SELECT server_revision,business_id FROM shops WHERE shop_id=$1 FOR UPDATE', [actor.shop_id]);
      if (!shop.rowCount || shop.rows[0].business_id !== actor.business_id) throw new ApiError(403, 'shop_access_denied');
      const journal = await c.query('SELECT envelope FROM sync_events WHERE shop_id=$1 ORDER BY server_revision', [actor.shop_id]);
      const supplierLedger = emptySupplierLedger();
      const customerLedger = emptyCustomerLedger();
      const staffShiftLedger = emptyStaffShiftLedger();
      const stockLedger = new Map();
      const creditState = new Map();
      const orderState = new Map();
      const productUnitLabels = new Map();
      const productCatalogue = new Map();
      const usedProducts = new Set();
      for (const row of journal.rows) if (row.envelope?.payload?.kind === 'projection_delta.v1') {
        applySupplierLedgerDelta(supplierLedger, row.envelope.payload);
        applyCustomerLedgerDelta(customerLedger, row.envelope.payload);
        applyStaffShiftLedgerDelta(staffShiftLedger, row.envelope.payload);
        for (const item of row.envelope.payload.stock ?? []) stockLedger.set(item.productId, item.quantity);
        for (const credit of row.envelope.payload.customerCredits ?? []) creditState.set(credit.creditId, credit);
        for (const order of row.envelope.payload.orders ?? []) orderState.set(order.orderNumber, order);
        applyProductUnitLabelDelta(productUnitLabels, row.envelope.payload);
        applyProductCatalogueDelta(productCatalogue, row.envelope.payload, productUnitLabels, usedProducts);
        applyProductHistory(usedProducts, row.envelope.payload);
      }
      const results = [];
      for (const e of events) {
        if (!e || e.type !== 'business.command.v1' || e.schemaVersion !== 1 || !UUID.test(e.eventId ?? '') || typeof e.commandId !== 'string' || e.commandId.length < 1 || e.commandId.length > 160 || !UUID.test(e.deviceId ?? '') || e.shopId !== actor.shop_id || e.businessId !== actor.business_id || !Number.isSafeInteger(e.baseRevision) || e.baseRevision < 0 || !e.payload || typeof e.payload !== 'object' || Array.isArray(e.payload)) throw new ApiError(400, 'invalid_event_envelope');
        validateProjectionDelta(e.payload);
        if (e.deviceId !== actor.device_id) throw new ApiError(403, 'device_mismatch');
        const raw = Buffer.from(JSON.stringify(e)); if (raw.length > this.maxPayloadBytes) throw new ApiError(413, 'event_too_large');
        const payloadHash = hash(raw);
        const previous = await c.query('SELECT payload_hash,server_revision FROM sync_events WHERE shop_id=$1 AND (event_id=$2 OR command_id=$3)', [actor.shop_id,e.eventId,e.commandId]);
        if (previous.rowCount) {
          const same = previous.rows.length === 1 && timingSafeEqual(previous.rows[0].payload_hash,payloadHash);
          if (same) { results.push({ eventId:e.eventId,status:'duplicate',serverRevision:Number(previous.rows[0].server_revision) }); continue; }
          throw new ApiError(409, 'event_idempotency_conflict');
        }
        const revision = Number(shop.rows[0].server_revision);
        if (e.baseRevision !== revision) {
          await c.query(`INSERT INTO sync_conflicts(business_id,shop_id,event_id,command_id,device_id,expected_revision,current_revision,envelope)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, [actor.business_id,actor.shop_id,e.eventId,e.commandId,e.deviceId,e.baseRevision,revision,e]);
          results.push({ eventId:e.eventId,status:'conflict',currentRevision:revision }); continue;
        }
        const candidateLabels = new Map(productUnitLabels);
        applyProductUnitLabelDelta(candidateLabels, e.payload);
        applyProductCatalogueDelta(new Map(productCatalogue), e.payload, candidateLabels, usedProducts);
        authorizeProjectionForRole(actor.role, e.payload, stockLedger, creditState, orderState, candidateLabels);
        applyProductUnitLabelDelta(productUnitLabels, e.payload);
        applyProductCatalogueDelta(productCatalogue, e.payload, productUnitLabels, usedProducts);
        applyProductHistory(usedProducts, e.payload);
        applySupplierLedgerDelta(supplierLedger, e.payload);
        applyCustomerLedgerDelta(customerLedger, e.payload);
        applyStaffShiftLedgerDelta(staffShiftLedger, e.payload);
        const next = revision + 1;
        await c.query(`INSERT INTO sync_events(business_id,shop_id,server_revision,event_id,command_id,device_id,actor_user_id,actor_role,event_type,schema_version,base_revision,payload_hash,envelope)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,1,$10,$11,$12)`, [actor.business_id,actor.shop_id,next,e.eventId,e.commandId,e.deviceId,actor.user_id,actor.role,e.type,e.baseRevision,payloadHash,e]);
        await c.query('UPDATE shops SET server_revision=$2 WHERE shop_id=$1',[actor.shop_id,next]); shop.rows[0].server_revision=next;
        for (const item of e.payload.stock ?? []) stockLedger.set(item.productId, item.quantity);
        for (const credit of e.payload.customerCredits ?? []) creditState.set(credit.creditId, credit);
        for (const order of e.payload.orders ?? []) orderState.set(order.orderNumber, order);
        results.push({ eventId:e.eventId,status:'accepted',serverRevision:next });
      }
      return { currentRevision:Number(shop.rows[0].server_revision),results };
    });
  }
}
