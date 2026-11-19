/*
 * Small deterministic business-command engine for the CT-06 prototype.
 * Browser build: exposes window.TapriTransactions.
 * Node tests: exports the same factory through CommonJS.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.TapriTransactions = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const clone = value => JSON.parse(JSON.stringify(value));
  const initialDraft = () => ({ lines: [], method: 'cash', customerId: '', operationId: '' });

  function makeId(prefix) {
    const random = typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
    return `${prefix}-${random}`;
  }

  function error(code, message) {
    const result = new Error(message);
    result.code = code;
    return result;
  }

  function assertRevision(order, expected) {
    if (expected !== undefined && expected !== null && expected !== (order.revision || 0)) {
      throw error('REVISION_CONFLICT', 'The order changed. Refresh it before applying this command.');
    }
  }

  function paymentState(sale) {
    const due = sale.totalPaise - (sale.refundedPaise || 0) - sale.paidPaise - sale.creditPaise;
    if (sale.paymentMethod === 'upi_pending') return 'upi_pending';
    if (due <= 0) {
      if (sale.paidPaise > 0 && sale.creditPaise > 0) return 'split';
      if (sale.creditPaise > 0) return 'credit';
      return sale.paidPaise > 0 ? 'staff_declared' : 'unpaid';
    }
    if (sale.creditPaise > 0 && sale.paidPaise > 0) return 'split';
    if (sale.creditPaise > 0) return 'credit';
    return sale.paidPaise > 0 ? 'partially_paid' : 'unpaid';
  }

  function createTransactionEngine({ catalog, initialState, persist }) {
    const products = new Map(catalog.map(product => [product.id, product]));
    const openingStock = Object.fromEntries(catalog.map(product => [product.id, product.stock]));
    const openingCustomers = {
      ramesh: { id: 'ramesh', name: 'Ramesh Patil', balancePaise: 0 },
      neha: { id: 'neha', name: 'Neha Sharma', balancePaise: 0 },
      office: { id: 'office', name: 'Office order · Mehta & Co.', balancePaise: 0 }
    };
    const blank = {
      schemaVersion: 1,
      sales: [],
      orders: [],
      nextOrderNumber: 101,
      stock: openingStock,
      customers: openingCustomers,
      payments: [],
      receivables: [],
      stockMovements: [],
      events: [],
      commandReceipts: {},
      draft: initialDraft()
    };
    let state = { ...blank, ...(initialState || {}) };
    state.stock = { ...openingStock, ...(initialState && initialState.stock || {}) };
    state.customers = { ...openingCustomers, ...(initialState && initialState.customers || {}) };
    state.draft = { ...initialDraft(), ...(initialState && initialState.draft || {}) };
    state.commandReceipts = state.commandReceipts || {};
    state.payments = state.payments || [];
    state.receivables = state.receivables || [];
    state.stockMovements = state.stockMovements || [];
    state.events = state.events || [];
    state.sales = state.sales || [];
    state.orders = state.orders || [];
    state.nextOrderNumber = state.nextOrderNumber || 101;

    function commit(next) {
      if (persist) {
        try { persist(clone(next)); }
        catch (cause) { throw error('PERSIST_FAILED', `Local transaction was not saved: ${cause.message || cause}`); }
      }
      state = next;
    }

    function execute(command) {
      if (!command || !command.idempotencyKey || !command.type) throw error('INVALID_COMMAND', 'Command type and idempotency key are required.');
      const key = String(command.idempotencyKey);
      const prior = state.commandReceipts[key];
      if (prior) return { ...clone(prior.result), duplicate: true };

      const next = clone(state);
      let result;
      if (command.type === 'order.add_item') result = addDraftItem(next, command, key);
      else if (command.type === 'order.set_item_quantity') result = setDraftQuantity(next, command, key);
      else if (command.type === 'order.set_payment_intent') result = setDraftPayment(next, command, key);
      else if (command.type === 'order.set_customer') result = setDraftCustomer(next, command, key);
      else if (command.type === 'order.create') result = createOrder(next, command, key);
      else if (command.type === 'order.add_line') result = addOrderLine(next, command, key);
      else if (command.type === 'order.remove_line') result = removeOrderLine(next, command, key);
      else if (command.type === 'order.set_fulfillment') result = setOrderFulfillment(next, command, key);
      else if (command.type === 'order.handover_partial') result = handoverPartial(next, command, key);
      else if (command.type === 'order.record_payment') result = recordOrderPayment(next, command, key);
      else if (command.type === 'order.record_cash_udhar_split') result = recordOrderCashUdharSplit(next, command, key);
      else if (command.type === 'order.record_cash_upi_pending_split') result = recordOrderCashUpiPendingSplit(next, command, key);
      else if (command.type === 'order.record_upi_direct') result = recordOrderUpiDirect(next, command, key);
      else if (command.type === 'order.record_upi_received') result = recordOrderUpiReceived(next, command, key);
      else if (command.type === 'customer.record_repayment') result = recordCustomerRepayment(next, command, key);
      else if (command.type === 'sale.commit') result = commitSale(next, command, key);
      else if (command.type === 'sale.refund_items') result = refundSale(next, command, key, false);
      else if (command.type === 'sale.refund_amount') result = refundSale(next, command, key, false);
      else if (command.type === 'sale.void') result = voidSale(next, command, key);
      else throw error('UNSUPPORTED_COMMAND', `Unsupported command: ${command.type}`);

      next.commandReceipts[key] = { result: clone(result), recordedAt: command.occurredAt || new Date().toISOString() };
      commit(next);
      return clone(result);
    }

    function addDraftItem(next, command, key) {
      const payload = command.payload || {};
      const product = products.get(payload.productId);
      if (!product) throw error('PRODUCT_NOT_FOUND', 'A product is not in the current catalogue.');
      const quantity = payload.quantity === undefined ? 1 : payload.quantity;
      if (!Number.isSafeInteger(quantity) || quantity < 1) throw error('INVALID_QUANTITY', 'Quantity must be a positive whole number.');
      const lines = new Map(next.draft.lines || []);
      const updatedQuantity = (lines.get(product.id) || 0) + quantity;
      const available = next.stock[product.id] ?? 0;
      if (updatedQuantity > available) throw error('INSUFFICIENT_STOCK', `Not enough ${product.name} in the demo stock.`);
      lines.set(product.id, updatedQuantity);
      next.draft = {
        ...next.draft,
        lines: [...lines.entries()],
        operationId: `sale-${key}`
      };
      return { status: 'draft_updated', productId: product.id, quantity: updatedQuantity, source: command.source || 'touch' };
    }

    function setDraftQuantity(next, command, key) {
      const payload = command.payload || {};
      const product = products.get(payload.productId);
      if (!product) throw error('PRODUCT_NOT_FOUND', 'A product is not in the current catalogue.');
      const quantity = payload.quantity;
      if (!Number.isSafeInteger(quantity) || quantity < 0) throw error('INVALID_QUANTITY', 'Quantity must be zero or a positive whole number.');
      const available = next.stock[product.id] ?? 0;
      if (quantity > available) throw error('INSUFFICIENT_STOCK', `Not enough ${product.name} in the demo stock.`);
      const lines = new Map(next.draft.lines || []);
      if (quantity === 0) lines.delete(product.id); else lines.set(product.id, quantity);
      next.draft = { ...next.draft, lines: [...lines.entries()], operationId: `sale-${key}` };
      return { status: 'draft_updated', productId: product.id, quantity, source: command.source || 'touch' };
    }

    function setDraftPayment(next, command, key) {
      const method = command.payload && command.payload.paymentMethod;
      if (!['cash', 'upi', 'udhar'].includes(method)) throw error('INVALID_PAYMENT_METHOD', 'Choose cash, UPI pending, or Udhar.');
      next.draft = { ...next.draft, method, customerId: method === 'udhar' ? next.draft.customerId : '', operationId: `sale-${key}` };
      return { status: 'draft_updated', paymentMethod: method };
    }

    function setDraftCustomer(next, command, key) {
      const customerId = command.payload && command.payload.customerId || '';
      if (customerId && (!next.customers[customerId] || next.draft.method !== 'udhar')) throw error('CUSTOMER_NOT_FOUND', 'Choose a valid customer after selecting Udhar.');
      next.draft = { ...next.draft, customerId, operationId: `sale-${key}` };
      return { status: 'draft_updated', customerId };
    }

    function resolveLines(inputLines) {
      if (!Array.isArray(inputLines) || inputLines.length === 0) throw error('EMPTY_ORDER', 'Add at least one product to the order.');
      return inputLines.map(input => {
        const product = products.get(input.productId);
        if (!product) throw error('PRODUCT_NOT_FOUND', 'A product is not in the current catalogue.');
        if (!Number.isSafeInteger(input.quantity) || input.quantity < 1) throw error('INVALID_QUANTITY', `Quantity for ${product.name} must be positive.`);
        return { productId: product.id, name: product.name, quantity: input.quantity, unitPricePaise: product.price, amountPaise: product.price * input.quantity };
      });
    }

    function createOrder(next, command, key) {
      const lines = resolveLines(command.payload && command.payload.lines);
      const now = command.occurredAt || new Date().toISOString();
      const order = {
        id: `order-${key}`, number: next.nextOrderNumber++, shopId: command.shopId || 'demo-shop',
        actorId: command.actorId || 'demo-owner', source: command.source || 'voice_text_preview',
        createdAt: now, lines: lines.map(line => ({ ...line, fulfilledQuantity: 0, refundedQuantity: 0 })), fulfillment: 'taken', paymentStatus: 'unpaid', saleId: null, revision: 0,
        paidPaise: 0, creditPaise: 0, paymentAttemptIds: []
      };
      next.orders.unshift(order);
      next.events.push({ id: `${key}-taken`, type: 'order.taken', orderId: order.id, orderNumber: order.number, actorId: order.actorId, source: order.source, at: now });
      return { status: 'order_created', orderId: order.id, orderNumber: order.number, fulfillment: order.fulfillment };
    }

    function addOrderLine(next, command, key) {
      const payload = command.payload || {};
      const order = next.orders.find(item => item.id === payload.orderId);
      if (!order) throw error('ORDER_NOT_FOUND', 'The order could not be found.');
      assertRevision(order, payload.expectedRevision);
      if (!['taken', 'preparing', 'ready'].includes(order.fulfillment)) throw error('ORDER_CLOSED', 'Items can only be added before handover.');
      const product = products.get(payload.productId);
      if (!product) throw error('PRODUCT_NOT_FOUND', 'A product is not in the current catalogue.');
      const quantity = payload.quantity === undefined ? 1 : payload.quantity;
      if (!Number.isSafeInteger(quantity) || quantity < 1) throw error('INVALID_QUANTITY', 'Quantity must be a positive whole number.');
      const existing = order.lines.find(line => line.productId === product.id);
      if (existing) { existing.quantity += quantity; existing.amountPaise = existing.unitPricePaise * existing.quantity; }
      else order.lines.push({ productId: product.id, name: product.name, quantity, fulfilledQuantity: 0, unitPricePaise: product.price, amountPaise: product.price * quantity });
      next.events.push({ id: `${key}-item-added`, type: 'order.item_added', orderId: order.id, productId: product.id, quantity, actorId: command.actorId || 'demo-owner', source: command.source || 'voice_text_preview', at: command.occurredAt || new Date().toISOString() });
      order.revision = (order.revision || 0) + 1;
      return { status: 'order_updated', orderId: order.id, orderNumber: order.number, productId: product.id, quantity };
    }

    function handoverOrder(next, order, requested, command, key, now) {
      const quantities = new Map();
      requested.forEach(item => quantities.set(item.productId, (quantities.get(item.productId) || 0) + item.quantity));
      if (!quantities.size || [...quantities.values()].some(quantity => !Number.isSafeInteger(quantity) || quantity < 1)) throw error('INVALID_QUANTITY', 'Handover quantities must be positive whole numbers.');
      const lines = [...quantities.entries()].map(([productId, quantity]) => {
        const line = order.lines.find(item => item.productId === productId);
        if (!line) throw error('PRODUCT_NOT_IN_ORDER', 'A handover item is not part of this order.');
        const remaining = line.quantity - (line.fulfilledQuantity || 0);
        if (quantity > remaining) throw error('QUANTITY_EXCEEDS_REMAINING', `Only ${remaining} ${line.name} remain to hand over.`);
        if ((next.stock[productId] ?? 0) < quantity) throw error('INSUFFICIENT_STOCK', `Not enough ${line.name} to hand over this quantity.`);
        return { line, quantity };
      });
      const addedPaise = lines.reduce((sum, { line, quantity }) => sum + line.unitPricePaise * quantity, 0);
      const existingSale = order.saleId && next.sales.find(item => item.id === order.saleId);
      if (!Number.isSafeInteger(addedPaise) || !Number.isSafeInteger((existingSale?.totalPaise || 0) + addedPaise)) throw error('AMOUNT_OVERFLOW', 'The handed-over amount is outside the supported range.');
      const actorId = command.actorId || 'demo-owner';
      const source = command.source || 'voice_text_preview';
      lines.forEach(({ line, quantity }, index) => {
        line.fulfilledQuantity = (line.fulfilledQuantity || 0) + quantity;
        next.stock[line.productId] -= quantity;
        next.stockMovements.push({ id: `${key}-stock-${index}`, productId: line.productId, quantity: -quantity, reason: 'order_handover', orderId: order.id, actorId, at: now, source });
      });
      const fullyGiven = order.lines.every(line => (line.fulfilledQuantity || 0) >= line.quantity);
      order.fulfillment = fullyGiven ? 'given' : 'partially_given';
      const saleLines = lines.map(({ line, quantity }) => ({ productId: line.productId, name: line.name, quantity, refundedQuantity: 0, unitPricePaise: line.unitPricePaise, amountPaise: line.unitPricePaise * quantity }));
      let sale = existingSale;
      const firstHandover = !sale;
      if (!sale) {
        sale = { id: `sale-${order.id}`, orderId: order.id, createdAt: now, actorId, shopId: order.shopId, source: order.source, lines: [], totalPaise: 0, refundedPaise: 0, cashRefundedPaise: 0, creditReversedPaise: 0, fulfillment: order.fulfillment, paymentMethod: 'unpaid', paymentState: 'unpaid', customerId: null, customerName: null, paidPaise: 0, creditPaise: 0, voided: false, idempotencyKey: key };
        order.saleId = sale.id;
        next.sales.unshift(sale);
      }
      saleLines.forEach(incoming => {
        const existing = sale.lines.find(line => line.productId === incoming.productId);
        if (existing) { existing.quantity += incoming.quantity; existing.amountPaise += incoming.amountPaise; }
        else sale.lines.push({ ...incoming, refundedQuantity: 0 });
        sale.totalPaise += incoming.amountPaise;
      });
      sale.fulfillment = order.fulfillment;
      if (firstHandover) next.events.push({ id: `${key}-sale`, type: 'sale.recorded', saleId: sale.id, orderId: order.id, actorId, source: order.source, at: now });
      next.events.push({ id: `${key}-handover`, type: fullyGiven ? 'order.handed_over' : 'order.partially_handed_over', orderId: order.id, saleId: sale.id, lines: saleLines, actorId, source, at: now });
      order.revision = (order.revision || 0) + 1;
      return sale;
    }

    function setOrderFulfillment(next, command, key) {
      const payload = command.payload || {};
      const order = next.orders.find(item => item.id === payload.orderId);
      if (!order) throw error('ORDER_NOT_FOUND', 'The order could not be found.');
      const target = payload.status;
      assertRevision(order, payload.expectedRevision);
      const allowed = { taken: ['preparing', 'ready', 'given', 'cancelled'], preparing: ['ready', 'given', 'cancelled'], ready: ['given', 'cancelled'], partially_given: ['given', 'cancelled'] };
      if (!allowed[order.fulfillment]?.includes(target)) throw error('INVALID_ORDER_TRANSITION', `Cannot move this order from ${order.fulfillment} to ${target}.`);
      const now = command.occurredAt || new Date().toISOString();
      if (target === 'given') {
        const remaining = order.lines.map(line => ({ productId: line.productId, quantity: line.quantity - (line.fulfilledQuantity || 0) })).filter(line => line.quantity > 0);
        const sale = handoverOrder(next, order, remaining, command, key, now);
        order.paymentStatus = sale.paymentState;
      } else {
        order.fulfillment = target;
        if (target === 'cancelled') order.cancelReason = String(payload.reason || 'Cancelled by staff');
        next.events.push({ id: `${key}-fulfillment`, type: target === 'cancelled' ? 'order.cancelled' : `order.${target}`, orderId: order.id, status: target, reason: order.cancelReason || null, actorId: command.actorId || 'demo-owner', source: command.source || 'voice_text_preview', at: now });
        order.revision = (order.revision || 0) + 1;
      }
      return { status: 'order_updated', orderId: order.id, orderNumber: order.number, fulfillment: order.fulfillment, saleId: order.saleId };
    }

    function handoverPartial(next, command, key) {
      const payload = command.payload || {};
      const order = next.orders.find(item => item.id === payload.orderId);
      if (!order) throw error('ORDER_NOT_FOUND', 'The order could not be found.');
      assertRevision(order, payload.expectedRevision);
      if (!['taken', 'preparing', 'ready', 'partially_given'].includes(order.fulfillment)) throw error('ORDER_CLOSED', 'This order cannot be partially handed over.');
      const sale = handoverOrder(next, order, payload.lines || [], command, key, command.occurredAt || new Date().toISOString());
      return { status: 'order_updated', orderId: order.id, orderNumber: order.number, fulfillment: order.fulfillment, saleId: sale.id };
    }

    function recordOrderPayment(next, command, key) {
      const payload = command.payload || {};
      const order = next.orders.find(item => item.id === payload.orderId);
      if (!order || !order.saleId || !['given', 'cancelled'].includes(order.fulfillment)) throw error('ORDER_NOT_GIVEN', 'Record payment after handover and order completion/cancellation.');
      assertRevision(order, payload.expectedRevision);
      const sale = next.sales.find(item => item.id === order.saleId);
      const method = payload.paymentMethod;
      if (!['cash', 'upi_pending', 'udhar'].includes(method)) throw error('INVALID_PAYMENT_METHOD', 'Choose cash, UPI pending, or Udhar.');
      if (order.paymentAttemptIds.some(id => next.payments.find(item => item.id === id)?.status === 'pending' && !next.payments.some(event => event.type === 'payment_attempt_cancelled' && event.relatedPaymentId === id))) throw error('PAYMENT_ATTEMPT_PENDING', 'Resolve or cancel the pending UPI attempt before recording another payment.');
      const totalPaise = sale.totalPaise;
      const remaining = totalPaise - (sale.refundedPaise || 0) - sale.paidPaise - sale.creditPaise;
      if (sale.creditPaise > 0 && (method !== 'udhar' || sale.customerId !== payload.customerId)) throw error('CREDIT_CUSTOMER_CONFLICT', 'An order’s open credit must remain with the same customer.');
      const amountPaise = payload.amountPaise === undefined ? remaining : payload.amountPaise;
      if (!Number.isSafeInteger(amountPaise) || amountPaise <= 0 || amountPaise > remaining) throw error('PAYMENT_MISMATCH', 'Payment amount must be positive and cannot exceed the amount due.');
      const now = command.occurredAt || new Date().toISOString();
      const actorId = command.actorId || 'demo-owner';
      if (method === 'cash') {
        sale.paidPaise += amountPaise; order.paidPaise += amountPaise;
        next.payments.push({ id: `${key}-payment`, saleId: sale.id, orderId: order.id, amountPaise, method: 'cash', status: 'staff_declared', evidence: 'staff_declaration', actorId, at: now });
        sale.paymentMethod = sale.creditPaise > 0 ? 'split' : 'cash';
        sale.paymentState = paymentState(sale);
      } else if (method === 'upi_pending') {
        const paymentId = `${key}-payment-attempt`;
        next.payments.push({ id: paymentId, saleId: sale.id, orderId: order.id, amountPaise, collectedPaise: 0, method: 'upi', status: 'pending', evidence: 'none', actorId, at: now });
        order.paymentAttemptIds.push(paymentId);
        sale.paymentMethod = 'upi_pending'; sale.paymentState = 'upi_pending';
      } else {
        const customer = payload.customerId && next.customers[payload.customerId];
        if (!customer) throw error('CUSTOMER_REQUIRED', 'Udhar needs a valid customer account.');
        customer.balancePaise += amountPaise; sale.creditPaise += amountPaise; order.creditPaise += amountPaise;
        sale.customerId = customer.id; sale.customerName = customer.name;
        next.receivables.push({ id: `${key}-receivable`, customerId: customer.id, saleId: sale.id, amountPaise, settledPaise: 0, type: 'credit_sale', actorId, at: now });
        sale.paymentMethod = sale.paidPaise > 0 ? 'split' : 'udhar';
        sale.paymentState = paymentState(sale);
      }
      order.paymentStatus = sale.paymentState;
      order.revision = (order.revision || 0) + 1;
      next.events.push({ id: `${key}-payment-recorded`, type: method === 'upi_pending' ? 'payment.pending' : method === 'udhar' ? 'receivable.created' : 'payment.staff_declared', orderId: order.id, saleId: sale.id, amountPaise, method, actorId, source: command.source || 'voice_text_preview', at: now });
      return { status: 'payment_recorded', orderId: order.id, orderNumber: order.number, paymentState: sale.paymentState, amountPaise, remainingPaise: totalPaise - (sale.refundedPaise || 0) - sale.paidPaise - sale.creditPaise };
    }

    function recordOrderCashUdharSplit(next, command, key) {
      const payload = command.payload || {};
      const order = next.orders.find(item => item.id === payload.orderId);
      if (!order || !order.saleId || !['given', 'cancelled'].includes(order.fulfillment)) throw error('ORDER_NOT_GIVEN', 'Record split collection after handover and order completion/cancellation.');
      assertRevision(order, payload.expectedRevision);
      const sale = next.sales.find(item => item.id === order.saleId);
      const customer = payload.customerId && next.customers[payload.customerId];
      if (!customer) throw error('CUSTOMER_REQUIRED', 'Choose a valid customer account for the Udhar portion.');
      if (order.paymentAttemptIds.some(id => next.payments.find(item => item.id === id)?.status === 'pending' && !next.payments.some(event => event.type === 'payment_attempt_cancelled' && event.relatedPaymentId === id))) throw error('PAYMENT_ATTEMPT_PENDING', 'Resolve the pending UPI attempt before recording a split collection.');
      if (sale.voided || sale.paymentState === 'upi_pending') throw error('PAYMENT_STATE_INVALID', 'Resolve the current payment state before recording a split collection.');
      if (sale.creditPaise > 0 && sale.customerId !== customer.id) throw error('CREDIT_CUSTOMER_CONFLICT', 'An order’s open credit must remain with the same customer.');
      const duePaise = sale.totalPaise - (sale.refundedPaise || 0) - sale.paidPaise - sale.creditPaise;
      const cashPaise = payload.cashPaise;
      if (!Number.isSafeInteger(cashPaise) || cashPaise <= 0 || cashPaise >= duePaise) throw error('PAYMENT_MISMATCH', 'Cash must be positive and less than the due so a Udhar balance remains.');
      const creditPaise = duePaise - cashPaise;
      const now = command.occurredAt || new Date().toISOString();
      const actorId = command.actorId || 'demo-owner';
      sale.paidPaise += cashPaise; order.paidPaise += cashPaise;
      sale.creditPaise += creditPaise; order.creditPaise += creditPaise;
      sale.customerId = customer.id; sale.customerName = customer.name;
      customer.balancePaise += creditPaise;
      next.payments.push({ id: `${key}-cash`, saleId: sale.id, orderId: order.id, amountPaise: cashPaise, method: 'cash', status: 'staff_declared', evidence: 'staff_declaration', type: 'order_collection', actorId, at: now });
      next.receivables.push({ id: `${key}-receivable`, customerId: customer.id, saleId: sale.id, amountPaise: creditPaise, settledPaise: 0, type: 'credit_sale', actorId, at: now });
      sale.paymentMethod = 'split'; sale.paymentState = paymentState(sale); order.paymentStatus = sale.paymentState;
      order.revision = (order.revision || 0) + 1;
      next.events.push({ id: `${key}-split-recorded`, type: 'payment.cash_udhar_split_recorded', orderId: order.id, saleId: sale.id, cashPaise, creditPaise, customerId: customer.id, actorId, source: command.source || 'touch', at: now });
      return { status: 'payment_recorded', orderId: order.id, orderNumber: order.number, paymentState: sale.paymentState, cashPaise, creditPaise, remainingPaise: duePaise - cashPaise - creditPaise };
    }

    function recordOrderCashUpiPendingSplit(next, command, key) {
      const payload = command.payload || {};
      const order = next.orders.find(item => item.id === payload.orderId);
      if (!order || !order.saleId || !['given', 'cancelled'].includes(order.fulfillment)) throw error('ORDER_NOT_GIVEN', 'Record split tender after handover and order completion/cancellation.');
      assertRevision(order, payload.expectedRevision);
      const sale = next.sales.find(item => item.id === order.saleId);
      if (sale.voided || sale.paymentState === 'upi_pending') throw error('PAYMENT_STATE_INVALID', 'Resolve the current payment state before recording a split collection.');
      if (order.paymentAttemptIds.some(id => next.payments.find(item => item.id === id)?.status === 'pending' && !next.payments.some(event => event.type === 'payment_attempt_cancelled' && event.relatedPaymentId === id))) throw error('PAYMENT_ATTEMPT_PENDING', 'Resolve the pending UPI attempt before recording a split collection.');
      const duePaise = sale.totalPaise - (sale.refundedPaise || 0) - sale.paidPaise - sale.creditPaise;
      const cashPaise = payload.cashPaise;
      if (!Number.isSafeInteger(cashPaise) || cashPaise <= 0 || cashPaise >= duePaise) throw error('PAYMENT_MISMATCH', 'Cash must be positive and less than the due so a UPI balance remains.');
      const upiPaise = duePaise - cashPaise;
      const now = command.occurredAt || new Date().toISOString();
      const actorId = command.actorId || 'demo-owner';
      sale.paidPaise += cashPaise; order.paidPaise += cashPaise;
      sale.paymentMethod = 'upi_pending'; sale.paymentState = 'upi_pending';
      next.payments.push({ id: `${key}-cash`, saleId: sale.id, orderId: order.id, amountPaise: cashPaise, collectedPaise: cashPaise, method: 'cash', status: 'staff_declared', evidence: 'staff_declaration', type: 'order_collection', actorId, at: now });
      const attemptId = `${key}-upi-attempt`;
      next.payments.push({ id: attemptId, saleId: sale.id, orderId: order.id, amountPaise: upiPaise, collectedPaise: 0, method: 'upi', status: 'pending', evidence: 'none', type: 'order_collection', actorId, at: now });
      order.paymentAttemptIds.push(attemptId); order.paymentStatus = 'upi_pending';
      order.revision = (order.revision || 0) + 1;
      next.events.push({ id: `${key}-split-recorded`, type: 'payment.cash_upi_pending_split_recorded', orderId: order.id, saleId: sale.id, cashPaise, upiPendingPaise: upiPaise, actorId, source: command.source || 'touch', at: now });
      return { status: 'payment_recorded', orderId: order.id, orderNumber: order.number, paymentState: sale.paymentState, cashPaise, upiPendingPaise: upiPaise, remainingPaise: duePaise - cashPaise };
    }

    function recordOrderUpiDirect(next, command, key) {
      const payload = command.payload || {};
      const order = next.orders.find(item => item.id === payload.orderId);
      if (!order || !order.saleId || !['given', 'cancelled'].includes(order.fulfillment)) throw error('ORDER_NOT_GIVEN', 'Record UPI receipt after handover and order completion/cancellation.');
      assertRevision(order, payload.expectedRevision);
      const sale = next.sales.find(item => item.id === order.saleId);
      if (sale.voided || sale.paymentState === 'upi_pending') throw error('UPI_ATTEMPT_PENDING', 'Resolve the existing pending UPI attempt through its receipt follow-up.');
      const duePaise = sale.totalPaise - (sale.refundedPaise || 0) - sale.paidPaise - sale.creditPaise;
      const amountPaise = payload.amountPaise === undefined ? duePaise : payload.amountPaise;
      if (!Number.isSafeInteger(amountPaise) || amountPaise <= 0 || amountPaise > duePaise) throw error('PAYMENT_MISMATCH', 'Staff-declared UPI receipt must be positive and cannot exceed the amount due.');
      const now = command.occurredAt || new Date().toISOString();
      const actorId = command.actorId || 'demo-owner';
      sale.paidPaise += amountPaise; order.paidPaise += amountPaise;
      sale.paymentMethod = sale.creditPaise > 0 ? 'split' : 'upi';
      sale.paymentState = paymentState(sale); order.paymentStatus = sale.paymentState;
      order.revision = (order.revision || 0) + 1;
      next.payments.push({ id: `${key}-payment`, saleId: sale.id, orderId: order.id, amountPaise, collectedPaise: amountPaise, method: 'upi', status: 'staff_declared', evidence: 'staff_declaration', type: 'order_collection', actorId, at: now });
      next.events.push({ id: `${key}-payment-recorded`, type: 'payment.staff_declared', orderId: order.id, saleId: sale.id, amountPaise, method: 'upi', evidence: 'staff_declaration', actorId, source: command.source || 'touch', at: now });
      return { status: 'payment_recorded', orderId: order.id, orderNumber: order.number, paymentState: sale.paymentState, amountPaise, remainingPaise: duePaise - amountPaise, verification: 'staff_declared_not_provider_verified' };
    }

    function recordOrderUpiReceived(next, command, key) {
      const payload = command.payload || {};
      const order = next.orders.find(item => item.id === payload.orderId);
      if (!order || !order.saleId || !['given', 'cancelled'].includes(order.fulfillment)) throw error('ORDER_NOT_GIVEN', 'Record UPI receipt after handover and order completion/cancellation.');
      assertRevision(order, payload.expectedRevision);
      const sale = next.sales.find(item => item.id === order.saleId);
      const attempt = [...next.payments].reverse().find(item => (item.orderId === order.id || item.saleId === sale.id) && item.method === 'upi' && item.status === 'pending' && !next.payments.some(event => event.type === 'payment_attempt_cancelled' && event.relatedPaymentId === item.id));
      if (!attempt || sale.paymentState !== 'upi_pending') throw error('UPI_ATTEMPT_NOT_PENDING', 'There is no active pending UPI attempt to record as received.');
      const remaining = sale.totalPaise - (sale.refundedPaise || 0) - sale.paidPaise - sale.creditPaise;
      const amountPaise = payload.amountPaise === undefined ? Math.min(remaining, attempt.amountPaise) : payload.amountPaise;
      if (!Number.isSafeInteger(amountPaise) || amountPaise <= 0 || amountPaise > remaining || amountPaise > attempt.amountPaise) throw error('PAYMENT_MISMATCH', 'Staff-declared UPI receipt must be positive and cannot exceed the pending attempt or amount due.');
      const now = command.occurredAt || new Date().toISOString();
      const actorId = command.actorId || 'demo-owner';
      next.payments.push({ id: `${key}-attempt-cancel`, orderId: order.id, saleId: sale.id, amountPaise: 0, method: 'upi', status: 'cancelled', evidence: 'staff_confirmation', type: 'payment_attempt_cancelled', relatedPaymentId: attempt.id, actorId, at: now });
      next.payments.push({ id: `${key}-payment`, orderId: order.id, saleId: sale.id, amountPaise, collectedPaise: amountPaise, method: 'upi', status: 'staff_declared', evidence: 'staff_declaration', type: 'order_collection', actorId, at: now });
      sale.paidPaise += amountPaise;
      sale.paymentMethod = sale.creditPaise > 0 ? 'split' : 'upi';
      sale.paymentState = paymentState(sale);
      order.paidPaise += amountPaise;
      order.paymentStatus = sale.paymentState;
      order.revision = (order.revision || 0) + 1;
      next.events.push({ id: `${key}-payment-recorded`, type: 'payment.staff_declared', orderId: order.id, saleId: sale.id, amountPaise, method: 'upi', evidence: 'staff_declaration', actorId, source: command.source || 'touch', at: now });
      return { status: 'payment_recorded', orderId: order.id, orderNumber: order.number, paymentState: sale.paymentState, amountPaise, remainingPaise: remaining - amountPaise, verification: 'staff_declared_not_provider_verified' };
    }

    function recordCustomerRepayment(next, command, key) {
      const payload = command.payload || {};
      const customer = next.customers[payload.customerId];
      if (!customer) throw error('CUSTOMER_NOT_FOUND', 'Choose a valid customer account.');
      if (payload.paymentMethod !== 'cash') throw error('UNSUPPORTED_REPAYMENT_METHOD', 'This prototype supports staff-declared cash repayments only.');
      const amountPaise = payload.amountPaise;
      if (!Number.isSafeInteger(amountPaise) || amountPaise <= 0 || amountPaise > customer.balancePaise) throw error('PAYMENT_MISMATCH', 'Repayment must be positive and cannot exceed this customer’s outstanding balance.');
      let remaining = amountPaise;
      const allocations = [];
      const entries = next.receivables.filter(entry => entry.customerId === customer.id && entry.type === 'credit_sale' && !next.sales.find(sale => sale.id === entry.saleId)?.voided).sort((a, b) => String(a.at).localeCompare(String(b.at)));
      for (const entry of entries) {
        if (remaining === 0) break;
        const outstanding = entry.amountPaise - (entry.settledPaise || 0) - (entry.reversedPaise || 0);
        if (outstanding <= 0) continue;
        const amount = Math.min(outstanding, remaining);
        entry.settledPaise = (entry.settledPaise || 0) + amount;
        remaining -= amount;
        allocations.push({ receivableId: entry.id, saleId: entry.saleId, amountPaise: amount });
        const sale = next.sales.find(item => item.id === entry.saleId);
        sale.creditPaise -= amount;
        sale.paidPaise += amount;
        sale.paymentState = paymentState(sale);
        const order = next.orders.find(item => item.id === sale.orderId);
        if (order) {
          order.creditPaise = Math.max(0, (order.creditPaise || 0) - amount);
          order.paidPaise = (order.paidPaise || 0) + amount;
          order.paymentStatus = sale.paymentState;
          order.revision = (order.revision || 0) + 1;
        }
      }
      if (remaining > 0) throw error('LEDGER_MISMATCH', 'The repayment does not match open receivable entries. No changes were saved.');
      customer.balancePaise -= amountPaise;
      const now = command.occurredAt || new Date().toISOString();
      const actorId = command.actorId || 'demo-owner';
      next.receivables.push({ id: `${key}-repayment`, customerId: customer.id, amountPaise: -amountPaise, type: 'repayment', allocations, actorId, at: now });
      next.payments.push({ id: `${key}-payment`, customerId: customer.id, amountPaise, method: 'cash', status: 'staff_declared', evidence: 'staff_declaration', type: 'customer_repayment', allocations, actorId, at: now });
      next.events.push({ id: `${key}-event`, type: 'customer.repayment_recorded', customerId: customer.id, amountPaise, allocations, actorId, source: command.source || 'touch', at: now });
      return { status: 'repayment_recorded', customerId: customer.id, amountPaise, balancePaise: customer.balancePaise, allocations };
    }

    function commitSale(next, command, key) {
      const payload = command.payload || {};
      const method = payload.paymentMethod;
      if (!['cash', 'upi_pending', 'udhar'].includes(method)) throw error('INVALID_PAYMENT_METHOD', 'Choose cash, UPI pending, or Udhar.');
      if (!Array.isArray(payload.lines) || payload.lines.length === 0) throw error('EMPTY_SALE', 'Add at least one product.');
      if (method === 'udhar' && !payload.customerId) throw error('CUSTOMER_REQUIRED', 'Udhar needs a customer account.');
      const customer = method === 'udhar' ? next.customers[payload.customerId] : null;
      if (method === 'udhar' && !customer) throw error('CUSTOMER_NOT_FOUND', 'Choose a valid customer account.');

      const lines = payload.lines.map(input => {
        const product = products.get(input.productId);
        if (!product) throw error('PRODUCT_NOT_FOUND', 'A product is no longer in the catalogue.');
        if (!Number.isSafeInteger(input.quantity) || input.quantity < 1) throw error('INVALID_QUANTITY', `Quantity for ${product.name} must be a positive whole number.`);
        const available = next.stock[product.id] ?? 0;
        if (!Number.isSafeInteger(available) || available < input.quantity) throw error('INSUFFICIENT_STOCK', `Not enough ${product.name} in the demo stock.`);
        if (!Number.isSafeInteger(product.price) || product.price < 0) throw error('INVALID_PRICE', `Price for ${product.name} is invalid.`);
        return {
          productId: product.id,
          name: product.name,
          quantity: input.quantity,
          unitPricePaise: product.price,
          amountPaise: product.price * input.quantity
        };
      });
      const totalPaise = lines.reduce((sum, line) => sum + line.amountPaise, 0);
      if (!Number.isSafeInteger(totalPaise)) throw error('AMOUNT_OVERFLOW', 'The sale total is outside the supported range.');

      const orderId = `order-${key}`;
      const saleId = `sale-${key}`;
      const now = command.occurredAt || new Date().toISOString();
      const paymentState = method === 'cash' ? 'staff_declared' : method === 'upi_pending' ? 'upi_pending' : 'credit';
      const sale = {
        id: saleId,
        orderId,
        createdAt: now,
        actorId: command.actorId || 'demo-owner',
        shopId: command.shopId || 'demo-shop',
        source: command.source || 'touch',
        lines: lines.map(line => ({ ...line, refundedQuantity: 0 })),
        totalPaise,
        refundedPaise: 0,
        cashRefundedPaise: 0,
        creditReversedPaise: 0,
        fulfillment: 'given',
        paymentMethod: method,
        paymentState,
        customerId: method === 'udhar' ? customer.id : null,
        customerName: method === 'udhar' ? customer.name : null,
        paidPaise: method === 'cash' ? totalPaise : 0,
        creditPaise: method === 'udhar' ? totalPaise : 0,
        voided: false,
        idempotencyKey: key
      };
      next.orders.unshift({
        id: orderId, number: next.nextOrderNumber++, shopId: sale.shopId, actorId: sale.actorId, source: sale.source,
        createdAt: now, lines: clone(lines).map(line => ({ ...line, fulfilledQuantity: line.quantity, refundedQuantity: 0 })), fulfillment: 'given', paymentStatus: paymentState, saleId,
        paidPaise: sale.paidPaise, creditPaise: sale.creditPaise, paymentAttemptIds: [], revision: 0
      });
      next.sales.unshift(sale);
      next.events.push({ id: `${key}-sale-recorded`, type: 'sale.recorded', saleId, orderId, actorId: sale.actorId, source: sale.source, at: now });

      lines.forEach((line, index) => {
        next.stock[line.productId] -= line.quantity;
        next.stockMovements.push({
          id: `${key}-stock-${index}`, productId: line.productId, quantity: -line.quantity,
          reason: 'sale', saleId, actorId: sale.actorId, at: now, source: sale.source
        });
      });

      if (method === 'cash') {
        next.payments.push({ id: `${key}-payment`, saleId, amountPaise: totalPaise, method: 'cash', status: 'staff_declared', evidence: 'staff_declaration', actorId: sale.actorId, at: now });
      } else if (method === 'upi_pending') {
        next.payments.push({ id: `${key}-payment-attempt`, saleId, amountPaise: totalPaise, collectedPaise: 0, method: 'upi', status: 'pending', evidence: 'none', actorId: sale.actorId, at: now });
      } else {
        customer.balancePaise += totalPaise;
        next.receivables.push({ id: `${key}-receivable`, customerId: customer.id, saleId, amountPaise: totalPaise, settledPaise: 0, type: 'credit_sale', actorId: sale.actorId, at: now });
      }

      // The committed sale and the cleared draft share one persisted snapshot.
      next.draft = initialDraft();

      return { status: 'committed_local', saleId, orderId, totalPaise, fulfillment: sale.fulfillment, paymentState, stockMovementCount: lines.length };
    }

    function refundSale(next, command, key, fromVoid) {
      const payload = command.payload || {};
      const sale = next.sales.find(item => item.id === payload.saleId || item.orderId === payload.orderId);
      if (!sale) throw error('SALE_NOT_FOUND', 'The sale could not be found.');
      const order = next.orders.find(item => item.id === sale.orderId);
      if (order) assertRevision(order, payload.expectedRevision);
      if (sale.voided) throw error('ALREADY_VOIDED', 'This sale has already been refunded or cancelled.');
      if (!order || !['given', 'cancelled'].includes(order.fulfillment)) throw error('ORDER_NOT_FINAL', 'Refunds require delivered items on a completed or cancelled order.');
      const reason = String(payload.reason || '').trim();
      if (!reason) throw error('REASON_REQUIRED', 'Enter a reason for the correction.');

      let returnedItems = [];
      if (fromVoid) {
        if (!['restock', 'not_restocked'].includes(payload.stockDisposition)) throw error('DISPOSITION_REQUIRED', 'Choose whether returned items can be restocked.');
        returnedItems = sale.lines.map(line => ({ productId: line.productId, quantity: line.quantity - (line.refundedQuantity || 0), stockDisposition: payload.stockDisposition })).filter(item => item.quantity > 0);
      } else if (command.type === 'sale.refund_items') {
        if (!Array.isArray(payload.items) || payload.items.length === 0) throw error('EMPTY_RETURN', 'Choose at least one returned item.');
        returnedItems = payload.items;
      }

      let amountPaise = command.type === 'sale.refund_amount' ? payload.amountPaise : fromVoid ? sale.totalPaise - (sale.refundedPaise || 0) : returnedItems.reduce((sum, item) => {
        if (!Number.isSafeInteger(item.quantity) || item.quantity <= 0) throw error('INVALID_QUANTITY', 'Returned quantities must be positive whole numbers.');
        if (!['restock', 'not_restocked'].includes(item.stockDisposition)) throw error('DISPOSITION_REQUIRED', 'Choose a stock disposition for each returned item.');
        const saleLine = sale.lines.find(line => line.productId === item.productId);
        const orderLine = order.lines.find(line => line.productId === item.productId);
        if (!saleLine || !orderLine || item.quantity > saleLine.quantity - (saleLine.refundedQuantity || 0) || item.quantity > orderLine.fulfilledQuantity - (orderLine.refundedQuantity || 0)) throw error('RETURN_EXCEEDS_DELIVERED', 'Return quantity exceeds delivered, unrefunded quantities.');
        const product = products.get(item.productId);
        if (item.stockDisposition === 'restock' && product?.returnableForRestock !== true) throw error('RESTOCK_NOT_ALLOWED', 'Shop product policy does not allow this item to be restocked.');
        return sum + saleLine.unitPricePaise * item.quantity;
      }, 0);
      if (!Number.isSafeInteger(amountPaise) || amountPaise <= 0 || amountPaise > sale.totalPaise - (sale.refundedPaise || 0)) throw error('REFUND_MISMATCH', 'Refund exceeds the delivered, unrefunded sale value.');

      const creditRefund = Math.min(amountPaise, sale.creditPaise || 0);
      const cashRefund = Math.min(amountPaise - creditRefund, sale.paidPaise || 0);
      const uncollectedReversal = amountPaise - creditRefund - cashRefund;
      if (cashRefund > 0 && payload.cashRefundDeclared !== true) throw error('REFUND_CONFIRMATION_REQUIRED', 'Confirm the staff-declared cash refund before recording it.');
      const customer = creditRefund > 0 ? next.customers[sale.customerId] : null;
      if (creditRefund > 0 && (!customer || customer.balancePaise < creditRefund)) throw error('LEDGER_MISMATCH', 'Customer balance does not support this credit reversal.');
      const receivables = next.receivables.filter(entry => entry.saleId === sale.id && entry.type === 'credit_sale');
      const openCredit = receivables.reduce((sum, entry) => sum + entry.amountPaise - (entry.settledPaise || 0) - (entry.reversedPaise || 0), 0);
      if (openCredit < creditRefund) throw error('LEDGER_MISMATCH', 'Open receivables do not support this credit reversal.');

      let creditLeft = creditRefund;
      for (const entry of receivables) {
        if (creditLeft <= 0) break;
        const available = entry.amountPaise - (entry.settledPaise || 0) - (entry.reversedPaise || 0);
        const allocated = Math.min(available, creditLeft);
        if (allocated > 0) { entry.reversedPaise = (entry.reversedPaise || 0) + allocated; creditLeft -= allocated; }
      }
      const now = command.occurredAt || new Date().toISOString();
      const actorId = command.actorId || 'demo-owner';
      if (creditRefund > 0) {
        customer.balancePaise -= creditRefund;
        next.receivables.push({ id: `${key}-credit-reversal`, customerId: customer.id, saleId: sale.id, amountPaise: -creditRefund, type: 'sale_refund', actorId, reason, at: now });
        next.payments.push({ id: `${key}-credit-payment`, saleId: sale.id, amountPaise: -creditRefund, method: 'udhar', status: 'reversed', evidence: 'ledger_adjustment', type: 'credit_reversal', actorId, reason, at: now });
      }
      if (cashRefund > 0) next.payments.push({ id: `${key}-cash-refund`, saleId: sale.id, amountPaise: -cashRefund, method: 'cash', status: 'staff_declared_refund', evidence: 'staff_declaration', type: 'cash_refund', actorId, reason, at: now });

      const pendingAttempts = next.payments.filter(item => item.saleId === sale.id && item.method === 'upi' && item.status === 'pending');
      pendingAttempts.forEach((attempt, index) => {
        next.payments.push({ id: `${key}-upi-cancel-${index}`, saleId: sale.id, amountPaise: 0, method: 'upi', status: 'cancelled', evidence: 'staff_cancellation', type: 'payment_attempt_cancelled', relatedPaymentId: attempt.id, actorId, reason, at: now });
        next.events.push({ id: `${key}-upi-cancel-event-${index}`, type: 'payment.attempt_cancelled', saleId: sale.id, paymentId: attempt.id, reason, actorId, at: now });
      });
      if (pendingAttempts.length) sale.paymentMethod = sale.creditPaise > 0 ? 'udhar' : sale.paidPaise > 0 ? 'cash' : 'unpaid';

      returnedItems.forEach((item, index) => {
        const saleLine = sale.lines.find(line => line.productId === item.productId);
        const orderLine = order.lines.find(line => line.productId === item.productId);
        saleLine.refundedQuantity = (saleLine.refundedQuantity || 0) + item.quantity;
        orderLine.refundedQuantity = (orderLine.refundedQuantity || 0) + item.quantity;
        if (item.stockDisposition === 'restock') {
          next.stock[item.productId] = (next.stock[item.productId] ?? 0) + item.quantity;
          next.stockMovements.push({ id: `${key}-restock-${index}`, productId: item.productId, quantity: item.quantity, reason: 'sale_refund_restock', saleId: sale.id, actorId, at: now, source: command.source || 'touch' });
        }
        next.events.push({ id: `${key}-item-${index}`, type: 'sale.item_refunded', saleId: sale.id, productId: item.productId, quantity: item.quantity, disposition: item.stockDisposition, actorId, reason, at: now });
      });

      sale.refundedPaise = (sale.refundedPaise || 0) + amountPaise;
      sale.cashRefundedPaise = (sale.cashRefundedPaise || 0) + cashRefund;
      sale.creditReversedPaise = (sale.creditReversedPaise || 0) + creditRefund;
      sale.paidPaise -= cashRefund;
      sale.creditPaise -= creditRefund;
      sale.voided = sale.refundedPaise >= sale.totalPaise;
      sale.paymentState = sale.voided ? (cashRefund + creditRefund > 0 ? 'refunded' : 'cancelled') : paymentState(sale);
      order.refundedPaise = sale.refundedPaise;
      order.cashRefundedPaise = sale.cashRefundedPaise;
      order.creditReversedPaise = sale.creditReversedPaise;
      order.paidPaise = sale.paidPaise;
      order.creditPaise = sale.creditPaise;
      order.voided = sale.voided;
      order.paymentStatus = sale.paymentState;
      order.revision = (order.revision || 0) + 1;
      if (sale.voided) {
        sale.voidedAt = now; sale.voidReason = reason; sale.voidCommandId = key;
        next.events.push({ id: `${key}-void`, type: 'sale.voided', saleId: sale.id, reason, actorId, at: now });
      }
      next.events.push({ id: `${key}-refund`, type: 'payment.refund_allocated', saleId: sale.id, amountPaise, cashRefundPaise: cashRefund, creditReversalPaise: creditRefund, uncollectedReversalPaise: uncollectedReversal, actorId, reason, at: now });
      return { status: 'committed_local', saleId: sale.id, refunded: true, voided: sale.voided, amountPaise, cashRefundPaise: cashRefund, creditReversalPaise: creditRefund, uncollectedReversalPaise: uncollectedReversal };
    }

    function voidSale(next, command, key) {
      const payload = command.payload || {};
      const sale = next.sales.find(item => item.id === payload.saleId);
      if (!sale) throw error('SALE_NOT_FOUND', 'The sale could not be found.');
      const remaining = sale.totalPaise - (sale.refundedPaise || 0);
      const fullVoid = { ...command, type: 'sale.void', payload: { ...payload, amountPaise: remaining } };
      return refundSale(next, fullVoid, key, true);
    }
    function saveDraft(draft) {
      const next = clone(state);
      next.draft = {
        lines: Array.isArray(draft.lines) ? draft.lines.map(([id, quantity]) => [id, quantity]) : [],
        method: draft.method || 'cash',
        customerId: draft.customerId || '',
        operationId: draft.operationId || makeId('cmd')
      };
      commit(next);
      return clone(state.draft);
    }

    function snapshot() { return clone(state); }
    function getStock(productId) { return state.stock[productId] ?? 0; }
    function reset() {
      const next = clone(blank);
      next.draft = initialDraft();
      commit(next);
      return snapshot();
    }

    return { execute, saveDraft, snapshot, getStock, reset };
  }

  function removeOrderLine(next, command, key) {
    const order = next.orders.find(item => item.id === command.payload.orderId);
    if (!order || !['taken', 'preparing', 'ready', 'partially_given'].includes(order.fulfillment)) throw error('ORDER_CLOSED', 'This order can no longer be edited.');
    assertRevision(order, command.payload.expectedRevision);
    const productId = command.payload.productId;
    const quantity = Number(command.payload.quantity || 1);
    if (!Number.isInteger(quantity) || quantity < 1) throw error('INVALID_QUANTITY', 'Quantity must be a positive whole number.');
    const line = order.lines.find(item => item.productId === productId && item.quantity - (item.fulfilledQuantity || 0) >= quantity);
    if (!line) throw error('LINE_NOT_FOUND', 'That item and quantity are not on this order.');
    line.quantity -= quantity;
    line.amountPaise = line.unitPricePaise * line.quantity;
    if (!line.quantity) order.lines.splice(order.lines.indexOf(line), 1);
    if (!order.lines.length) throw error('EMPTY_ORDER', 'An order cannot be left empty; cancel it instead.');
    order.totalPaise = order.lines.reduce((sum, item) => sum + item.amountPaise, 0);
    order.revision = (order.revision || 0) + 1;
    order.updatedAt = new Date().toISOString();
    next.events.push({ id: `${key}-event`, type: 'order.line_removed', orderId: order.id, productId, quantity, actorId: command.actorId || 'owner', at: order.updatedAt });
    return { orderId: order.id, orderNumber: order.number, totalPaise: order.totalPaise };
  }

  return { createTransactionEngine };
});
