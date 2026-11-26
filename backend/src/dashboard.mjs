import { ApiError } from './errors.mjs';

function safeTotal(values) {
  let total = 0;
  for (const value of values) {
    total += value;
    if (!Number.isSafeInteger(total)) throw new ApiError(422, 'dashboard_total_out_of_range');
  }
  return total;
}

function safeMultiply(left, right) {
  const value = left * right;
  if (!Number.isSafeInteger(value)) throw new ApiError(422, 'dashboard_total_out_of_range');
  return value;
}

/** Deterministic owner follow-ups. These are record-based prompts, not predictions. */
export function buildOwnerInsights({ shopId, shopName, serverRevision, events }) {
  const overview = buildShopOverview({ shopId, shopName, serverRevision, events });
  const suppliers = buildSupplierBalances({ shopId, shopName, serverRevision, events });
  const customers = buildCustomerBalances({ shopId, shopName, serverRevision, events });
  const coverage = currentAcceptedCostCoverage(events);
  const insights = [];
  const add = (id, severity, title, summary, sourceView, amountPaise = null) => insights.push({ id, severity, title, summary, sourceView, amountPaise });

  if (overview.openOrderCount > 0) add('open_orders', 'info', 'Open orders recorded', `${overview.openOrderCount} accepted order(s) are not marked given or cancelled.`, 'overview');
  if (overview.upiPendingPaise > 0) add('upi_pending', 'review', 'UPI attempts are pending', 'Review the pending attempts; these are not verified collections and may include attempts whose outcome is not yet known.', 'overview', overview.upiPendingPaise);
  if (customers.netReceivablePaise > 0) add('customer_receivables', 'review', 'Customer balances recorded', 'Review the aggregate recorded receivable in customer balances. No customer identity is included here.', 'customers', customers.netReceivablePaise);
  if (suppliers.netPayablePaise > 0) add('supplier_payables', 'review', 'Supplier payable recorded', 'Review the aggregate payable from accepted receipt, payment and return records.', 'suppliers', suppliers.netPayablePaise);
  if (coverage.netDeliveredSaleLines > 0 && !coverage.complete) add('profit_cost_coverage', 'info', 'Profit is not fully calculable', `${coverage.uncostedNetDeliveredSaleLines} of ${coverage.netDeliveredSaleLines} delivered sale line(s) lack an accepted unit-cost estimate.`, 'profit');

  return {
    shopId, shopName, serverRevision, generatedFrom: 'accepted_server_events',
    insightCount: insights.length, insights,
    emptyState: insights.length === 0 ? 'No recorded follow-ups from the accepted events in this snapshot.' : null,
    evidence: {
      scope: 'Derived only from server-accepted events at this revision. Offline, blocked and unsynchronized activity is excluded.',
      meaning: 'These are deterministic operational reminders, not AI advice, predictions, verified bank/cash facts, or a complete accounting statement.',
      privacy: 'Customer and supplier identities are not included in insight records.',
      money: 'Amounts are integer paise. UPI attempts and staff-declared settlements remain unverified unless a separate authorized provider integration supplies evidence.'
    }
  };
}

/** Supplier payable view uses opaque device-issued IDs and accepted declared events only. */
export function buildSupplierBalances({ shopId, shopName, serverRevision, events }) {
  const suppliers = new Map();
  const totalsFor = (id) => {
    if (!suppliers.has(id)) suppliers.set(id, { supplierId: id, receiptCount: 0, receiptsPaise: 0, paymentCount: 0, paymentsPaise: 0, returnCount: 0, returnsPaise: 0 });
    return suppliers.get(id);
  };
  for (const row of events) {
    const payload = row.envelope?.payload;
    if (payload?.kind !== 'projection_delta.v1') continue;
    for (const receipt of payload.purchaseReceipts ?? []) {
      const total = totalsFor(receipt.supplierId); total.receiptCount += 1; total.receiptsPaise = safeTotal([total.receiptsPaise, receipt.totalPaise]);
    }
    for (const payment of payload.supplierPayments ?? []) {
      const total = totalsFor(payment.supplierId); total.paymentCount += 1; total.paymentsPaise = safeTotal([total.paymentsPaise, payment.amountPaise]);
    }
    for (const returned of payload.purchaseReturns ?? []) {
      const total = totalsFor(returned.supplierId); total.returnCount += 1; total.returnsPaise = safeTotal([total.returnsPaise, returned.amountPaise]);
    }
  }
  const rows = [...suppliers.values()].map((row) => ({ ...row, outstandingPaise: row.receiptsPaise - row.paymentsPaise - row.returnsPaise }))
    .sort((a, b) => b.outstandingPaise - a.outstandingPaise || a.supplierId.localeCompare(b.supplierId));
  return {
    shopId, shopName, serverRevision, supplierCount: rows.length,
    totalReceiptsPaise: safeTotal(rows.map((row) => row.receiptsPaise)),
    totalPaymentsPaise: safeTotal(rows.map((row) => row.paymentsPaise)),
    totalReturnsPaise: safeTotal(rows.map((row) => row.returnsPaise)),
    netPayablePaise: safeTotal(rows.map((row) => row.outstandingPaise)), suppliers: rows,
    evidence: {
      scope: 'Accepted server events only; offline, blocked and unsynchronized activity is excluded.',
      identity: 'Suppliers are represented by opaque device-generated identifiers. Names, contacts, invoice references and product lines are not uploaded or displayed.',
      accounting: 'Net payable is recorded receipt value minus staff-declared settlements and recorded returns. A negative value represents a recorded supplier credit/advance.',
      verification: 'Supplier payment values are staff-declared and are not bank-verified. This view is an operational payable summary, not a reconciled accounting statement.'
    }
  };
}

/** Owner receivables from accepted projections. Customer identity is intentionally opaque. */
export function buildCustomerBalances({ shopId, shopName, serverRevision, events }) {
  const credits = new Map(); const repayments = new Map();
  for (const row of events) {
    const payload = row.envelope?.payload;
    if (payload?.kind !== 'projection_delta.v1') continue;
    for (const credit of payload.customerCredits ?? []) credits.set(credit.creditId, credit);
    for (const repayment of payload.repayments ?? []) repayments.set(repayment.repaymentId, repayment);
  }
  const customers = new Map();
  const totalsFor = (id) => { if (!customers.has(id)) customers.set(id, { customerId: id, creditCount: 0, extendedPaise: 0, repaidPaise: 0, reversedPaise: 0, repaymentCount: 0, repaymentsPaise: 0 }); return customers.get(id); };
  for (const credit of credits.values()) { const row = totalsFor(credit.customerId); row.creditCount += 1; row.extendedPaise = safeTotal([row.extendedPaise, credit.amountPaise]); row.repaidPaise = safeTotal([row.repaidPaise, credit.repaidPaise]); row.reversedPaise = safeTotal([row.reversedPaise, credit.reversedPaise]); }
  for (const repayment of repayments.values()) { const row = totalsFor(repayment.customerId); row.repaymentCount += 1; row.repaymentsPaise = safeTotal([row.repaymentsPaise, repayment.amountPaise]); }
  const rows = [...customers.values()].map((row) => ({ ...row, outstandingPaise: row.extendedPaise - row.repaidPaise - row.reversedPaise }))
    .sort((a, b) => b.outstandingPaise - a.outstandingPaise || a.customerId.localeCompare(b.customerId));
  return {
    shopId, shopName, serverRevision, customerCount: rows.length,
    totalExtendedPaise: safeTotal(rows.map((row) => row.extendedPaise)), totalRepaymentsPaise: safeTotal(rows.map((row) => row.repaymentsPaise)),
    totalReversedPaise: safeTotal(rows.map((row) => row.reversedPaise)), netReceivablePaise: safeTotal(rows.map((row) => row.outstandingPaise)), customers: rows,
    evidence: {
      scope: 'Accepted server events only; offline, blocked and unsynchronized activity is excluded.',
      identity: 'Customers are represented only by opaque device-generated identifiers. Names, phone numbers, order details and local customer IDs are not uploaded or displayed.',
      accounting: 'Outstanding balances use the latest accepted credit state per credit record, less its settled and reversed amounts. Repayment events are shown as activity and are not subtracted a second time. This is an operational receivable view, not a reconciled accounting statement.',
      verification: 'Repayments are recorded events and are not independently payment-provider verified.'
    }
  };
}

/** Build owner-facing numbers only from accepted, validated server journal entries. */
export function buildShopOverview({ shopId, shopName, serverRevision, events }) {
  const orders = new Map();
  const stock = new Map();
  const movements = new Map();
  const payments = new Map();
  let lastAcceptedAt = null;

  for (const row of events) {
    const envelope = row.envelope;
    const payload = envelope?.payload;
    if (payload?.kind !== 'projection_delta.v1') continue;
    for (const order of payload.orders ?? []) orders.set(order.orderNumber, order);
    for (const item of payload.stock ?? []) stock.set(item.productId, item.quantity);
    for (const movement of payload.stockMovements ?? []) movements.set(movement.movementId, movement);
    for (const payment of payload.paymentEntries ?? []) payments.set(payment.entryId, payment);
    const receivedAt = row.received_at instanceof Date ? row.received_at : new Date(row.received_at);
    if (!Number.isNaN(receivedAt.valueOf()) && (!lastAcceptedAt || receivedAt > lastAcceptedAt)) lastAcceptedAt = receivedAt;
  }

  const currentOrders = [...orders.values()];
  const deliveredItemValues = currentOrders.flatMap((order) => {
    if (!order.salePosted || order.voided) return [];
    return order.lines.map((line) => Math.max(0, line.deliveredQuantity - line.refundedQuantity) * line.unitPricePaise);
  });
  const entries = [...payments.values()];
  const cancelledAttempts = new Set(entries
    .filter((entry) => entry.kind === 'payment_attempt_cancelled' && entry.status === 'cancelled' && entry.relatedEntryId)
    .map((entry) => entry.relatedEntryId));
  const cashCollections = entries.filter((entry) => entry.method === 'cash' && entry.status === 'staff_declared' && entry.amountPaise > 0);
  const cashRefunds = entries.filter((entry) => entry.method === 'cash' && entry.status === 'staff_declared_refund' && entry.amountPaise < 0);
  const upiCollections = entries.filter((entry) => entry.method === 'upi' && entry.status === 'staff_declared' && entry.amountPaise > 0);
  const upiPending = entries.filter((entry) => entry.method === 'upi' && entry.status === 'pending' && entry.amountPaise > 0 && !cancelledAttempts.has(entry.entryId));

  return {
    shopId,
    shopName,
    serverRevision,
    acceptedEventCount: events.length,
    lastAcceptedAt: lastAcceptedAt?.toISOString() ?? null,
    recordedOrderCount: currentOrders.length,
    openOrderCount: currentOrders.filter((order) => !['GIVEN', 'CANCELLED'].includes(order.fulfillment)).length,
    netDeliveredItemValuePaise: safeTotal(deliveredItemValues),
    cashCollectedPaise: safeTotal(cashCollections.map((entry) => entry.amountPaise)),
    cashRefundedPaise: safeTotal(cashRefunds.map((entry) => -entry.amountPaise)),
    staffDeclaredUpiPaise: safeTotal(upiCollections.map((entry) => entry.amountPaise)),
    upiPendingPaise: safeTotal(upiPending.map((entry) => entry.amountPaise)),
    trackedProductCount: stock.size,
    productsWithUnknownStockCount: [...stock.values()].filter((quantity) => quantity === null).length,
    stockMovementCount: movements.size,
    evidence: {
      scope: 'Accepted server events only; offline and blocked client events are not included.',
      sales: 'Calculated from latest accepted order lines, delivered quantities, returned quantities and recorded unit prices; not profit.',
      cash: 'Staff-declared collection/refund records; not independently verified cash.',
      upi: 'Staff-declared UPI receipts and pending attempts are separate; no provider verification is asserted.',
      customerCredit: 'Excluded because customer-linked identifiers and ledgers are not in the approved sync contract.',
      inventory: 'Tracked product count only; quantities are not combined because product units may differ.'
    }
  };
}

/** Product-level current delivered performance from accepted order/stock snapshots only. */
export function buildProductPerformance({ shopId, shopName, serverRevision, events }) {
  const orders = new Map();
  const stock = new Map();
  const unitLabels = new Map();
  const catalogue = new Map();
  for (const row of events) {
    const payload = row.envelope?.payload;
    if (payload?.kind !== 'projection_delta.v1') continue;
    for (const product of payload.catalogue ?? []) {
      catalogue.set(product.productId, product);
      unitLabels.set(product.productId, product.unitLabel);
    }
    for (const order of payload.orders ?? []) {
      orders.set(order.orderNumber, order);
      for (const line of order.lines ?? []) if (line.unitLabel) unitLabels.set(line.productId, line.unitLabel);
    }
    for (const item of payload.stock ?? []) {
      stock.set(item.productId, item.quantity);
      if (item.unitLabel) unitLabels.set(item.productId, item.unitLabel);
    }
  }

  const products = new Map();
  const ensure = (productId) => {
    if (!products.has(productId)) products.set(productId, {
      productId, grossDeliveredUnits: 0, refundedUnits: 0, netDeliveredUnits: 0,
      netDeliveredItemValuePaise: 0, orderNumbers: new Set()
    });
    return products.get(productId);
  };

  for (const productId of catalogue.keys()) ensure(productId);
  for (const [productId, quantity] of stock) ensure(productId).stockQuantity = quantity;
  for (const order of orders.values()) {
    if (!order.salePosted || order.voided) continue;
    for (const line of order.lines) {
      const netUnits = Math.max(0, line.deliveredQuantity - line.refundedQuantity);
      const product = ensure(line.productId);
      product.grossDeliveredUnits = safeTotal([product.grossDeliveredUnits, line.deliveredQuantity]);
      product.refundedUnits = safeTotal([product.refundedUnits, line.refundedQuantity]);
      product.netDeliveredUnits = safeTotal([product.netDeliveredUnits, netUnits]);
      product.netDeliveredItemValuePaise = safeTotal([
        product.netDeliveredItemValuePaise, safeMultiply(netUnits, line.unitPricePaise)
      ]);
      if (netUnits > 0) product.orderNumbers.add(order.orderNumber);
    }
  }

  const rows = [...products.values()].map((product) => ({
    productId: product.productId,
    productName: catalogue.get(product.productId)?.name ?? null,
    category: catalogue.get(product.productId)?.category ?? null,
    active: catalogue.get(product.productId)?.active ?? null,
    unitPricePaise: catalogue.get(product.productId)?.priceConfirmed ? catalogue.get(product.productId)?.unitPricePaise ?? null : null,
    purchaseUnitLabel: catalogue.get(product.productId)?.purchaseUnitLabel ?? null,
    baseUnitsPerPurchaseUnit: catalogue.get(product.productId)?.baseUnitsPerPurchaseUnit ?? null,
    grossDeliveredUnits: product.grossDeliveredUnits,
    refundedUnits: product.refundedUnits,
    netDeliveredUnits: product.netDeliveredUnits,
    netDeliveredItemValuePaise: product.netDeliveredItemValuePaise,
    orderCount: product.orderNumbers.size,
    stockState: product.stockQuantity === undefined ? 'unreported' : product.stockQuantity === null ? 'unknown' : 'known',
    stockQuantity: product.stockQuantity ?? null,
    unitLabel: unitLabels.get(product.productId) ?? catalogue.get(product.productId)?.unitLabel ?? null
  })).sort((a, b) => b.netDeliveredItemValuePaise - a.netDeliveredItemValuePaise || a.productId.localeCompare(b.productId));

  return {
    shopId, shopName, serverRevision,
    productCount: rows.length,
    netDeliveredItemValuePaise: safeTotal(rows.map((row) => row.netDeliveredItemValuePaise)),
    products: rows,
    evidence: {
      scope: 'Accepted server events only; offline, blocked and unsynchronized activity is not included.',
      calculation: 'Uses the latest accepted order state per order; voided/unposted orders are excluded and refunded units are subtracted from delivered units.',
      itemValue: 'Net delivered units multiplied by recorded selling price; this is item value, not recognized profit or cost-adjusted revenue.',
      units: 'Base units and configured purchase-unit conversions come only from accepted catalogue upserts; quantities are shown per product and must not be compared or summed across products.',
      catalogue: 'Names, category, active status, current confirmed prices and unit conversions come from the latest accepted catalogue upsert. Unaccepted/offline product edits are not shown; costs, aliases and scan codes are not exposed in this view.',
      stock: 'Stock state reflects the latest accepted stock snapshot; unknown and unreported stock are shown separately.'
    }
  };
}

/** Current accepted stock snapshots and recent movements; quantities stay product-specific. */
export function buildInventorySnapshot({ shopId, shopName, serverRevision, events, movementLimit = 100 }) {
  const stock = new Map();
  const movements = new Map();
  const unitLabels = new Map();
  const catalogue = new Map();
  for (const row of events) {
    const payload = row.envelope?.payload;
    if (payload?.kind !== 'projection_delta.v1') continue;
    for (const product of payload.catalogue ?? []) {
      catalogue.set(product.productId, product);
      unitLabels.set(product.productId, product.unitLabel);
    }
    const receivedAt = row.received_at instanceof Date ? row.received_at : new Date(row.received_at);
    const acceptedAt = Number.isNaN(receivedAt.valueOf()) ? null : receivedAt.toISOString();
    const revision = Number(row.server_revision);
    for (const item of payload.stock ?? []) {
      stock.set(item.productId, { quantity: item.quantity, serverRevision: revision, acceptedAt });
      if (item.unitLabel) unitLabels.set(item.productId, item.unitLabel);
    }
    for (const movement of payload.stockMovements ?? []) {
      movements.set(movement.movementId, { ...movement, serverRevision: revision, acceptedAt });
      if (movement.unitLabel) unitLabels.set(movement.productId, movement.unitLabel);
    }
  }

  const products = new Set([...stock.keys(), ...[...movements.values()].map((movement) => movement.productId)]);
  const inventory = [...products].sort((a, b) => a.localeCompare(b)).map((productId) => {
    const current = stock.get(productId);
    return {
      productId,
      productName: catalogue.get(productId)?.name ?? null,
      purchaseUnitLabel: catalogue.get(productId)?.purchaseUnitLabel ?? null,
      baseUnitsPerPurchaseUnit: catalogue.get(productId)?.baseUnitsPerPurchaseUnit ?? null,
      unitLabel: unitLabels.get(productId) ?? null,
      quantityState: !current ? 'unreported' : current.quantity === null ? 'unknown' : 'known',
      quantity: current?.quantity ?? null,
      lastReportedRevision: current?.serverRevision ?? null,
      lastReportedAt: current?.acceptedAt ?? null
    };
  });
  const recentMovements = [...movements.values()]
    .sort((a, b) => b.serverRevision - a.serverRevision || b.movementId.localeCompare(a.movementId))
    .slice(0, movementLimit)
    .map((movement) => ({
      movementId: movement.movementId,
      productId: movement.productId,
      quantityDelta: movement.quantityDelta,
      orderNumber: movement.orderNumber,
      unitLabel: movement.unitLabel ?? unitLabels.get(movement.productId) ?? null,
      serverRevision: movement.serverRevision,
      acceptedAt: movement.acceptedAt
    }));

  return {
    shopId,
    shopName,
    serverRevision,
    productCount: inventory.length,
    knownQuantityCount: inventory.filter((product) => product.quantityState === 'known').length,
    unknownQuantityCount: inventory.filter((product) => product.quantityState === 'unknown').length,
    unreportedQuantityCount: inventory.filter((product) => product.quantityState === 'unreported').length,
    movementCount: movements.size,
    products: inventory,
    recentMovements,
    evidence: {
      scope: 'Accepted server events only; offline, blocked and unsynchronized activity is not included.',
      quantities: 'Known, unknown and unreported quantities are distinct. Product names and purchase-unit conversions come from the latest accepted catalogue upsert; quantities remain base-unit counts and must not be added or compared across products.',
      movementHistory: 'Movement entries are deduplicated by movement ID and ordered by server revision. They are records, not proof of a physical count.',
      reconciliation: 'This view does not infer missing sales, waste, theft, or reorder quantities from a stock difference.'
    }
  };
}

function deliveredItemValue(order) {
  if (!order?.salePosted || order.voided) return 0;
  return safeTotal((order.lines ?? []).map((line) => safeMultiply(
    Math.max(0, line.deliveredQuantity - line.refundedQuantity), line.unitPricePaise
  )));
}

function zeroActivityDay(date) {
  return {
    date,
    eventsAccepted: 0,
    ordersWithSaleStarted: 0,
    netDeliveredItemValueChangePaise: 0,
    staffDeclaredCashPaise: 0,
    staffDeclaredCashRefundPaise: 0,
    staffDeclaredUpiPaise: 0,
    upiPendingAttemptsStartedPaise: 0,
    staffDeclaredExpensesPaise: 0,
    staffDeclaredSupplierPaymentsPaise: 0
  };
}

function currentAcceptedCostCoverage(events) {
  const orders = new Map();
  for (const row of events) {
    const payload = row.envelope?.payload;
    if (payload?.kind !== 'projection_delta.v1') continue;
    for (const order of payload.orders ?? []) orders.set(order.orderNumber, order);
  }
  let netDeliveredSaleLines = 0;
  let costedNetDeliveredSaleLines = 0;
  for (const order of orders.values()) {
    if (!order.salePosted || order.voided) continue;
    for (const line of order.lines) {
      const units = Math.max(0, line.deliveredQuantity - line.refundedQuantity);
      if (units === 0) continue;
      netDeliveredSaleLines = safeTotal([netDeliveredSaleLines, 1]);
      if (Number.isSafeInteger(line.estimatedUnitCostPaise) && line.estimatedUnitCostPaise >= 0) {
        costedNetDeliveredSaleLines = safeTotal([costedNetDeliveredSaleLines, 1]);
      }
    }
  }
  return {
    basis: 'latest accepted order states across available sync history; all dates',
    netDeliveredSaleLines,
    costedNetDeliveredSaleLines,
    uncostedNetDeliveredSaleLines: safeTotal([netDeliveredSaleLines, -costedNetDeliveredSaleLines]),
    complete: netDeliveredSaleLines > 0 && netDeliveredSaleLines === costedNetDeliveredSaleLines
  };
}

/** Daily sales/payment activity based on server receive dates and state transitions. */
export function buildDailySalesActivity({ shopId, shopName, serverRevision, events, days = 30, endDate = new Date().toISOString().slice(0, 10) }) {
  const parsedEndDate = /^\d{4}-\d{2}-\d{2}$/.test(endDate) ? new Date(`${endDate}T00:00:00Z`) : null;
  if (![7, 30, 90].includes(days) || !parsedEndDate || Number.isNaN(parsedEndDate.valueOf()) || parsedEndDate.toISOString().slice(0, 10) !== endDate) {
    throw new ApiError(400, 'invalid_activity_range');
  }
  const through = parsedEndDate;
  const from = new Date(through.valueOf() - (days - 1) * 86_400_000);
  const fromDate = from.toISOString().slice(0, 10);
  const activity = new Map();
  for (let index = 0; index < days; index += 1) {
    const date = new Date(from.valueOf() + index * 86_400_000).toISOString().slice(0, 10);
    activity.set(date, zeroActivityDay(date));
  }

  const orders = new Map();
  const paymentIds = new Set();
  const expenseIds = new Set();
  const supplierPaymentIds = new Set();
  const expenseCategories = new Map();
  for (const row of events) {
    const payload = row.envelope?.payload;
    if (payload?.kind !== 'projection_delta.v1') continue;
    const receivedAt = row.received_at instanceof Date ? row.received_at : new Date(row.received_at);
    if (Number.isNaN(receivedAt.valueOf())) continue;
    const date = receivedAt.toISOString().slice(0, 10);
    const day = activity.get(date);
    if (day) day.eventsAccepted = safeTotal([day.eventsAccepted, 1]);

    for (const order of payload.orders ?? []) {
      const previous = orders.get(order.orderNumber);
      const beforeValue = deliveredItemValue(previous);
      const afterValue = deliveredItemValue(order);
      const delta = afterValue - beforeValue;
      if (day) {
        day.netDeliveredItemValueChangePaise = safeTotal([day.netDeliveredItemValueChangePaise, delta]);
        if (!previous?.salePosted && order.salePosted && !order.voided) {
          day.ordersWithSaleStarted = safeTotal([day.ordersWithSaleStarted, 1]);
        }
      }
      orders.set(order.orderNumber, order);
    }

    for (const entry of payload.paymentEntries ?? []) {
      if (paymentIds.has(entry.entryId)) continue;
      paymentIds.add(entry.entryId);
      if (!day) continue;
      if (entry.method === 'cash' && entry.status === 'staff_declared' && entry.amountPaise > 0) {
        day.staffDeclaredCashPaise = safeTotal([day.staffDeclaredCashPaise, entry.amountPaise]);
      } else if (entry.method === 'cash' && entry.status === 'staff_declared_refund' && entry.amountPaise < 0) {
        day.staffDeclaredCashRefundPaise = safeTotal([day.staffDeclaredCashRefundPaise, -entry.amountPaise]);
      } else if (entry.method === 'upi' && entry.status === 'staff_declared' && entry.amountPaise > 0) {
        day.staffDeclaredUpiPaise = safeTotal([day.staffDeclaredUpiPaise, entry.amountPaise]);
      } else if (entry.method === 'upi' && entry.status === 'pending' && entry.kind === 'payment_attempt' && entry.amountPaise > 0) {
        day.upiPendingAttemptsStartedPaise = safeTotal([day.upiPendingAttemptsStartedPaise, entry.amountPaise]);
      }
    }

    for (const expense of payload.expenses ?? []) {
      if (expenseIds.has(expense.expenseId)) continue;
      expenseIds.add(expense.expenseId);
      if (!day || expense.status !== 'staff_declared') continue;
      day.staffDeclaredExpensesPaise = safeTotal([day.staffDeclaredExpensesPaise, expense.amountPaise]);
      const category = expenseCategories.get(expense.category) ?? { category: expense.category, count: 0, amountPaise: 0 };
      category.count = safeTotal([category.count, 1]);
      category.amountPaise = safeTotal([category.amountPaise, expense.amountPaise]);
      expenseCategories.set(expense.category, category);
    }

    for (const payment of payload.supplierPayments ?? []) {
      if (supplierPaymentIds.has(payment.paymentId)) continue;
      supplierPaymentIds.add(payment.paymentId);
      if (day && payment.status === 'staff_declared') {
        day.staffDeclaredSupplierPaymentsPaise = safeTotal([day.staffDeclaredSupplierPaymentsPaise, payment.amountPaise]);
      }
    }
  }

  const daily = [...activity.values()];
  const totals = {
    eventsAccepted: safeTotal(daily.map((day) => day.eventsAccepted)),
    ordersWithSaleStarted: safeTotal(daily.map((day) => day.ordersWithSaleStarted)),
    netDeliveredItemValueChangePaise: safeTotal(daily.map((day) => day.netDeliveredItemValueChangePaise)),
    staffDeclaredCashPaise: safeTotal(daily.map((day) => day.staffDeclaredCashPaise)),
    staffDeclaredCashRefundPaise: safeTotal(daily.map((day) => day.staffDeclaredCashRefundPaise)),
    staffDeclaredUpiPaise: safeTotal(daily.map((day) => day.staffDeclaredUpiPaise)),
    upiPendingAttemptsStartedPaise: safeTotal(daily.map((day) => day.upiPendingAttemptsStartedPaise)),
    staffDeclaredExpensesPaise: safeTotal(daily.map((day) => day.staffDeclaredExpensesPaise)),
    staffDeclaredSupplierPaymentsPaise: safeTotal(daily.map((day) => day.staffDeclaredSupplierPaymentsPaise))
  };
  return {
    shopId,
    shopName,
    serverRevision,
    range: { fromDate, throughDate: endDate, days, timezone: 'UTC' },
    currentAcceptedCostCoverage: currentAcceptedCostCoverage(events),
    acceptedEventCount: totals.eventsAccepted,
    daily,
    totals,
    expenseCategories: [...expenseCategories.values()].sort((a, b) => b.amountPaise - a.amountPaise || a.category.localeCompare(b.category)),
    evidence: {
      scope: 'Accepted server events only; offline, blocked, and unsynchronized activity is excluded.',
      dates: 'Activity is grouped by server receipt date in UTC. It is not a device-clock sale date or a shop-local business day.',
      deliveredValue: 'Shows changes in net delivered item value from accepted order states, after recorded returns; this is not profit or cost-adjusted revenue.',
      collections: 'Cash and UPI receipts/refunds are staff-declared records. UPI provider verification is not asserted.',
      pending: 'UPI pending amount counts attempts started on that day; it is not the currently outstanding pending balance.',
      credit: 'Customer credit/repayment details are excluded from the current approved sync contract.',
      expenses: 'Expense totals include accepted staff-declared records once per expense ID. Private notes and drawer links are excluded. These recorded outflows are not a profit calculation.',
      supplierPayments: 'Supplier settlements include accepted staff-declared payments once per payment ID. They are not bank-verified and are not treated as purchase expense or cost of goods sold.'
    }
  };
}

/** A deliberately partial cash-movement view derived from accepted declarations only. */
export function buildRecordedMoneyMovementSummary(activity) {
  const daily = activity.daily.map((day) => {
    const declaredCashInPaise = day.staffDeclaredCashPaise;
    const declaredUpiInPaise = day.staffDeclaredUpiPaise;
    const recordedCashRefundOutPaise = day.staffDeclaredCashRefundPaise;
    const recordedExpensesOutPaise = day.staffDeclaredExpensesPaise;
    const recordedSupplierPaymentsOutPaise = day.staffDeclaredSupplierPaymentsPaise ?? 0;
    const recordedInflowPaise = safeTotal([declaredCashInPaise, declaredUpiInPaise]);
    const recordedOutflowPaise = safeTotal([recordedCashRefundOutPaise, recordedExpensesOutPaise, recordedSupplierPaymentsOutPaise]);
    const netRecordedMovementPaise = safeTotal([
      declaredCashInPaise, declaredUpiInPaise,
      -recordedCashRefundOutPaise, -recordedExpensesOutPaise, -recordedSupplierPaymentsOutPaise
    ]);
    return {
      date: day.date,
      declaredCashInPaise,
      declaredUpiInPaise,
      recordedCashRefundOutPaise,
      recordedExpensesOutPaise,
      recordedSupplierPaymentsOutPaise,
      recordedInflowPaise,
      recordedOutflowPaise,
      netRecordedMovementPaise
    };
  });
  const totals = {
    declaredCashInPaise: safeTotal(daily.map((day) => day.declaredCashInPaise)),
    declaredUpiInPaise: safeTotal(daily.map((day) => day.declaredUpiInPaise)),
    recordedCashRefundOutPaise: safeTotal(daily.map((day) => day.recordedCashRefundOutPaise)),
    recordedExpensesOutPaise: safeTotal(daily.map((day) => day.recordedExpensesOutPaise)),
    recordedSupplierPaymentsOutPaise: safeTotal(daily.map((day) => day.recordedSupplierPaymentsOutPaise)),
    recordedInflowPaise: safeTotal(daily.map((day) => day.recordedInflowPaise)),
    recordedOutflowPaise: safeTotal(daily.map((day) => day.recordedOutflowPaise)),
    netRecordedMovementPaise: safeTotal(daily.map((day) => day.netRecordedMovementPaise))
  };
  return {
    shopId: activity.shopId,
    shopName: activity.shopName,
    serverRevision: activity.serverRevision,
    range: activity.range,
    daily,
    totals,
    evidence: {
      scope: activity.evidence.scope,
      dates: activity.evidence.dates,
      inflows: 'Cash and UPI are accepted staff-declared receipt records. UPI provider verification is not asserted.',
      outflows: 'Includes accepted staff-declared cash refunds, expenses, and supplier settlements once per recorded ID. Supplier settlements are not bank-verified; this is not a complete outflow ledger.',
      net: 'Net recorded movement is the included declared inflows less included recorded outflows. It is not profit, a reconciled cash drawer, or a bank balance.',
      exclusions: 'UPI pending attempts, customer credit and repayments, purchase receipts, supplier returns, cash-in/out, opening/closing balances, and offline or unsynchronized activity are excluded.'
    }
  };
}

/** Show owners which accepted inputs exist while refusing to invent profit without cost coverage. */
export function buildProfitReadiness(activity) {
  const costCoverage = activity.currentAcceptedCostCoverage ?? {
    basis: 'latest accepted order states across available sync history; all dates',
    netDeliveredSaleLines: 0, costedNetDeliveredSaleLines: 0,
    uncostedNetDeliveredSaleLines: 0, complete: false
  };
  return {
    shopId: activity.shopId,
    shopName: activity.shopName,
    serverRevision: activity.serverRevision,
    range: activity.range,
    costCoverage,
    status: 'not_calculable',
    figures: {
      netDeliveredItemValueChangePaise: activity.totals.netDeliveredItemValueChangePaise,
      staffDeclaredExpensesPaise: activity.totals.staffDeclaredExpensesPaise,
      grossProfitPaise: null,
      operatingProfitPaise: null
    },
    missingInputs: [
      ...(costCoverage.complete ? [] : ['Owner-entered estimated cost is missing for some or all net delivered units in accepted order history.']),
      'Purchase receipts and product cost layers are not included in the current server sync contract.',
      'Opening-stock valuation, recipe ingredient costs, and sale-date cost allocation are unavailable.',
      'The accepted expense subset may omit other operating outflows, supplier payments, and owner withdrawals.'
    ],
    evidence: {
      scope: activity.evidence.scope,
      sales: activity.evidence.deliveredValue,
      expenses: activity.evidence.expenses,
      result: 'Profit and margin are unavailable. No profit value is estimated from selling prices or incomplete cost data.'
    }
  };
}

/** Return a privacy-limited summary of accepted event metadata for an owner. */
export function buildAuditHistory({ shopId, shopName, serverRevision, events, limit = 100 }) {
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new Error('invalid_audit_limit');
  // Count every accepted projection family without returning business payloads.
  const fields = [
    'orders', 'stock', 'stockMovements', 'customerCredits', 'repayments',
    'paymentEntries', 'expenses', 'purchaseReceipts', 'supplierPayments', 'purchaseReturns'
  ];
  const rows = events.slice(-limit).reverse().map((row) => {
    const envelope = row.envelope && typeof row.envelope === 'object' ? row.envelope : {};
    const payload = envelope.payload && typeof envelope.payload === 'object' ? envelope.payload : {};
    const changeCounts = Object.fromEntries(fields.map((field) => [field, Array.isArray(payload[field]) ? payload[field].length : 0]));
    const receivedAt = row.received_at instanceof Date ? row.received_at : new Date(row.received_at);
    return {
      serverRevision: Number(row.server_revision),
      eventId: row.event_id,
      eventType: row.event_type,
      baseRevision: Number(row.base_revision),
      actorUserId: row.actor_user_id ?? null,
      actorDisplayName: row.actor_display_name ?? null,
      actorRole: row.actor_role ?? null,
      receivedAt: Number.isNaN(receivedAt.valueOf()) ? null : receivedAt.toISOString(),
      changeCounts
    };
  });
  return {
    shopId, shopName, serverRevision, returnedEventCount: rows.length,
    events: rows,
    evidence: {
      scope: 'Latest events accepted by the server for this authenticated shop, newest first.',
      source: 'Server revision, event envelope metadata, and counts of allow-listed record arrays.',
      exclusions: 'Raw payloads, product/customer identifiers, transaction amounts, and device identifiers are not included. Actor identity is the authenticated account that submitted the sync event, not necessarily the person who created an offline action.',
      limitation: 'This is an accepted sync-event history, not a complete employee audit trail or proof of the underlying business activity.'
    }
  };
}
