import { buildOwnerReportCsv } from './report-export.mjs';

(() => {
  const $ = (id) => document.getElementById(id);
  const loginPanel = $('login-panel');
  const dashboardPanel = $('dashboard-panel');
  const connection = $('connection-state');
  let session = null;
  let activeShopId = null;
  let activeView = 'overview';
  const reportSnapshots = new Map();

  const money = (paise) => new Intl.NumberFormat('en-IN', {
    style: 'currency', currency: 'INR', maximumFractionDigits: 2
  }).format(paise / 100);
  const setStatus = (element, message, state = 'neutral') => {
    element.textContent = message;
    element.dataset.state = state;
  };
  const request = async (url, options = {}) => {
    const response = await fetch(url, {
      cache: 'no-store', credentials: 'same-origin', redirect: 'error',
      ...options,
      headers: { 'content-type': 'application/json', ...(options.headers ?? {}) }
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error ?? `Request failed (${response.status})`);
    return body;
  };
  const metric = (title, value, note) => {
    const card = document.createElement('article');
    card.className = 'metric-card';
    const heading = document.createElement('h3'); heading.textContent = title;
    const figure = document.createElement('p'); figure.className = 'metric-value'; figure.textContent = value;
    const detail = document.createElement('p'); detail.className = 'metric-note'; detail.textContent = note;
    card.append(heading, figure, detail);
    return card;
  };
  const showError = (error) => error instanceof Error ? error.message : 'The request could not be completed.';

  function setView(view) {
    activeView = view;
    $('export-report-button').disabled = !reportSnapshots.has(view);
    const overview = view === 'overview';
    const products = view === 'products';
    const inventory = view === 'inventory';
    const suppliers = view === 'suppliers';
    const customers = view === 'customers';
    const activity = view === 'activity';
    const cashflow = view === 'cashflow';
    const profit = view === 'profit';
    const audit = view === 'audit';
    $('refresh-button').textContent = overview ? 'Refresh' : products ? 'Refresh products' : inventory ? 'Refresh inventory' : suppliers ? 'Refresh suppliers' : customers ? 'Refresh customer balances' : activity ? 'Refresh sales activity' : cashflow ? 'Refresh money movements' : profit ? 'Refresh profit readiness' : 'Refresh event history';
    $('overview-view').classList.toggle('hidden', !overview);
    $('products-view').classList.toggle('hidden', !products);
    $('inventory-view').classList.toggle('hidden', !inventory);
    $('suppliers-view').classList.toggle('hidden', !suppliers);
    $('customers-view').classList.toggle('hidden', !customers);
    $('activity-view').classList.toggle('hidden', !activity);
    $('cashflow-view').classList.toggle('hidden', !cashflow);
    $('profit-view').classList.toggle('hidden', !profit);
    $('audit-view').classList.toggle('hidden', !audit);
    $('overview-tab').classList.toggle('active', overview);
    $('overview-tab').setAttribute('aria-current', overview ? 'page' : 'false');
    $('products-tab').classList.toggle('active', products);
    $('products-tab').setAttribute('aria-current', products ? 'page' : 'false');
    $('inventory-tab').classList.toggle('active', inventory);
    $('inventory-tab').setAttribute('aria-current', inventory ? 'page' : 'false');
    $('suppliers-tab').classList.toggle('active', suppliers);
    $('suppliers-tab').setAttribute('aria-current', suppliers ? 'page' : 'false');
    $('customers-tab').classList.toggle('active', customers);
    $('customers-tab').setAttribute('aria-current', customers ? 'page' : 'false');
    $('activity-tab').classList.toggle('active', activity);
    $('activity-tab').setAttribute('aria-current', activity ? 'page' : 'false');
    $('cashflow-tab').classList.toggle('active', cashflow);
    $('cashflow-tab').setAttribute('aria-current', cashflow ? 'page' : 'false');
    $('profit-tab').classList.toggle('active', profit);
    $('profit-tab').setAttribute('aria-current', profit ? 'page' : 'false');
    $('audit-tab').classList.toggle('active', audit);
    $('audit-tab').setAttribute('aria-current', audit ? 'page' : 'false');
  }

  function captureReport(view, title, scopeId, metricsId, evidenceIds) {
    const container = $(`${view}-view`);
    const metrics = metricsId ? [...$(metricsId).querySelectorAll('.metric-card')].map((card) => ({
      title: card.querySelector('h3')?.textContent ?? '',
      value: card.querySelector('.metric-value')?.textContent ?? '',
      note: card.querySelector('.metric-note')?.textContent ?? ''
    })) : [];
    const sections = [...container.querySelectorAll('table')].map((table) => ({
      title: table.closest('.panel')?.querySelector('h3')?.textContent ?? title,
      headers: [...table.querySelectorAll('thead th')].map((cell) => cell.textContent.trim()),
      rows: [...table.querySelectorAll('tbody tr')].map((row) => {
        const values = [...row.cells].map((cell) => cell.textContent.trim());
        return values.length === 1 && row.cells[0].colSpan > 1
          ? [values[0], ...Array(sectionWidth(table) - 1).fill('')]
          : values;
      })
    })).filter((section) => section.headers.length);
    const evidence = evidenceIds.flatMap((id) => [...$(id).querySelectorAll('li')].map((item) => item.textContent.trim()));
    reportSnapshots.set(view, {
      title,
      shopName: $('shop-name').textContent.trim(),
      scope: $(scopeId).textContent.trim(),
      loadedAt: new Date().toISOString(),
      metrics,
      evidence,
      sections
    });
    $('export-report-button').disabled = activeView !== view;
  }

  function sectionWidth(table) {
    return table.querySelectorAll('thead th').length;
  }

  function invalidateReport(view) {
    reportSnapshots.delete(view);
    if (activeView === view) $('export-report-button').disabled = true;
  }

  async function loadOverview() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('overview');
    setStatus($('dashboard-status'), 'Loading accepted shop records…');
    try {
      const data = await request(`/v1/dashboard/overview?shopId=${encodeURIComponent(activeShopId)}`, {
        headers: { authorization: `Bearer ${session.token}` }
      });
      $('shop-name').textContent = data.shopName;
      $('data-scope').textContent = data.evidence.scope;
      const metrics = [
        metric('Recorded orders', String(data.recordedOrderCount), `${data.openOrderCount} currently open`),
        metric('Delivered item value', money(data.netDeliveredItemValuePaise), 'Calculated from accepted delivered quantities and line prices; not profit'),
        metric('Cash collected', money(data.cashCollectedPaise), 'Staff-declared amount'),
        metric('Cash refunds', money(data.cashRefundedPaise), 'Staff-declared outflow'),
        metric('UPI received', money(data.staffDeclaredUpiPaise), 'Staff-declared; provider not verified'),
        metric('UPI pending', money(data.upiPendingPaise), 'Pending attempts only'),
        metric('Tracked products', String(data.trackedProductCount), `${data.productsWithUnknownStockCount} have unknown quantity`),
        metric('Stock movements', String(data.stockMovementCount), 'Distinct accepted movement records')
      ];
      $('metrics').replaceChildren(...metrics);
      const evidence = Object.values(data.evidence).map((text) => {
        const item = document.createElement('li'); item.textContent = text; return item;
      });
      $('evidence-list').replaceChildren(...evidence);
      $('server-revision').textContent = String(data.serverRevision);
      $('accepted-events').textContent = String(data.acceptedEventCount);
      $('latest-event').textContent = data.lastAcceptedAt ? new Date(data.lastAcceptedAt).toLocaleString() : 'No accepted event yet';
      connection.textContent = 'Connected';
      connection.dataset.state = 'ready';
      setStatus($('dashboard-status'), 'Overview refreshed from accepted server events.', 'success');
      captureReport('overview', 'Shop overview', 'data-scope', 'metrics', ['evidence-list']);
    } catch (error) {
      setStatus($('dashboard-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally {
      $('refresh-button').disabled = false;
    }
  }

  async function loadProducts() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('products');
    setStatus($('products-status'), 'Loading latest accepted product recordsâ€¦');
    try {
      const data = await request(`/v1/dashboard/products?shopId=${encodeURIComponent(activeShopId)}`, {
        headers: { authorization: `Bearer ${session.token}` }
      });
      $('products-scope').textContent = data.evidence.scope;
      $('product-metrics').replaceChildren(
        metric('Products in accepted catalogue activity', String(data.productCount), 'Products with accepted stock or delivered-order records'),
        metric('Net delivered item value', money(data.netDeliveredItemValuePaise), 'Latest accepted order state; not profit')
      );
      const rows = data.products.map((product) => {
        const row = document.createElement('tr');
        const values = [
          product.productId,
          String(product.netDeliveredUnits),
          String(product.refundedUnits),
          money(product.netDeliveredItemValuePaise),
          String(product.orderCount),
          product.stockState === 'known' ? String(product.stockQuantity) : product.stockState === 'unknown' ? 'Unknown quantity' : 'Not reported'
        ];
        values.forEach((value) => { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); });
        return row;
      });
      if (!rows.length) {
        const row = document.createElement('tr'); const cell = document.createElement('td');
        cell.colSpan = 6; cell.className = 'empty-row'; cell.textContent = 'No accepted product activity is available yet.';
        row.append(cell); rows.push(row);
      }
      $('product-rows').replaceChildren(...rows);
      $('product-evidence-list').replaceChildren(...Object.values(data.evidence).map((text) => {
        const item = document.createElement('li'); item.textContent = text; return item;
      }));
      setStatus($('products-status'), `Product performance refreshed at server revision ${data.serverRevision}.`, 'success');
      captureReport('products', 'Product performance', 'products-scope', 'product-metrics', ['product-evidence-list']);
    } catch (error) {
      setStatus($('products-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally {
      $('refresh-button').disabled = false;
    }
  }

  async function loadInventory() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('inventory');
    setStatus($('inventory-status'), 'Loading latest accepted inventory records…');
    try {
      const data = await request(`/v1/dashboard/inventory?shopId=${encodeURIComponent(activeShopId)}`, {
        headers: { authorization: `Bearer ${session.token}` }
      });
      $('inventory-scope').textContent = data.evidence.scope;
      $('inventory-metrics').replaceChildren(
        metric('Products with accepted stock activity', String(data.productCount), 'Product rows with a snapshot or movement'),
        metric('Known quantities', String(data.knownQuantityCount), 'Latest accepted numeric snapshots'),
        metric('Unknown quantities', String(data.unknownQuantityCount), 'A snapshot exists but its quantity is unknown'),
        metric('Unreported quantities', String(data.unreportedQuantityCount), 'Movement exists without a stock snapshot'),
        metric('Distinct accepted movements', String(data.movementCount), 'Deduplicated by movement ID')
      );
      const formatTime = (value) => value ? new Date(value).toLocaleString() : 'Not recorded';
      const productRows = data.products.map((product) => {
        const row = document.createElement('tr');
        const values = [product.productId, product.quantityState === 'known' ? String(product.quantity) : '—', product.quantityState,
          product.lastReportedRevision === null ? '—' : String(product.lastReportedRevision), formatTime(product.lastReportedAt)];
        values.forEach((value) => { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); });
        return row;
      });
      if (!productRows.length) {
        const row = document.createElement('tr'); const cell = document.createElement('td');
        cell.colSpan = 5; cell.className = 'empty-row'; cell.textContent = 'No accepted stock snapshots or movements are available yet.';
        row.append(cell); productRows.push(row);
      }
      $('inventory-rows').replaceChildren(...productRows);
      const movementRows = data.recentMovements.map((movement) => {
        const row = document.createElement('tr');
        const values = [movement.movementId, movement.productId, `${movement.quantityDelta > 0 ? '+' : ''}${movement.quantityDelta}`,
          movement.orderNumber === null ? '—' : `#${movement.orderNumber}`, String(movement.serverRevision), formatTime(movement.acceptedAt)];
        values.forEach((value) => { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); });
        return row;
      });
      if (!movementRows.length) {
        const row = document.createElement('tr'); const cell = document.createElement('td');
        cell.colSpan = 6; cell.className = 'empty-row'; cell.textContent = 'No accepted stock movement records are available yet.';
        row.append(cell); movementRows.push(row);
      }
      $('inventory-movement-rows').replaceChildren(...movementRows);
      $('inventory-evidence-list').replaceChildren(...Object.values(data.evidence).map((text) => {
        const item = document.createElement('li'); item.textContent = text; return item;
      }));
      setStatus($('inventory-status'), `Inventory refreshed at server revision ${data.serverRevision}.`, 'success');
      captureReport('inventory', 'Inventory snapshot', 'inventory-scope', 'inventory-metrics', ['inventory-evidence-list']);
    } catch (error) {
      setStatus($('inventory-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally {
      $('refresh-button').disabled = false;
    }
  }

  async function loadSuppliers() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('suppliers');
    setStatus($('suppliers-status'), 'Loading accepted supplier finance events…');
    try {
      const data = await request(`/v1/dashboard/suppliers?shopId=${encodeURIComponent(activeShopId)}`, { headers: { authorization: `Bearer ${session.token}` } });
      $('suppliers-scope').textContent = `Server revision ${data.serverRevision}. ${data.evidence.scope}`;
      $('supplier-metrics').replaceChildren(
        metric('Suppliers with recorded activity', String(data.supplierCount), 'Opaque IDs only'),
        metric('Recorded receipts', money(data.totalReceiptsPaise), 'Accepted purchase receipt values'),
        metric('Staff-declared payments', money(data.totalPaymentsPaise), 'Not bank-verified'),
        metric('Recorded net payable', money(data.netPayablePaise), 'May include supplier credits/advances')
      );
      const rows = data.suppliers.map((supplier) => {
        const row = document.createElement('tr');
        for (const value of [supplier.supplierId, money(supplier.receiptsPaise), money(supplier.paymentsPaise), money(supplier.returnsPaise), money(supplier.outstandingPaise)]) {
          const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
        }
        return row;
      });
      if (!rows.length) { const row = document.createElement('tr'); const cell = document.createElement('td'); cell.colSpan = 5; cell.className = 'empty-row'; cell.textContent = 'No accepted supplier finance records are available.'; row.append(cell); rows.push(row); }
      $('supplier-rows').replaceChildren(...rows);
      $('supplier-evidence-list').replaceChildren(...Object.values(data.evidence).map((text) => { const item = document.createElement('li'); item.textContent = text; return item; }));
      setStatus($('suppliers-status'), `${data.supplierCount} supplier balances loaded through server revision ${data.serverRevision}.`, 'success');
      captureReport('suppliers', 'Supplier balances', 'suppliers-scope', 'supplier-metrics', ['supplier-evidence-list']);
    } catch (error) {
      setStatus($('suppliers-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally { $('refresh-button').disabled = false; }
  }

  async function loadCustomers() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('customers');
    setStatus($('customers-status'), 'Loading accepted customer credit events…');
    try {
      const data = await request(`/v1/dashboard/customers?shopId=${encodeURIComponent(activeShopId)}`, { headers: { authorization: `Bearer ${session.token}` } });
      $('customers-scope').textContent = `Server revision ${data.serverRevision}. ${data.evidence.scope}`;
      $('customer-metrics').replaceChildren(
        metric('Customers with recorded activity', String(data.customerCount), 'Opaque IDs only'),
        metric('Credit extended', money(data.totalExtendedPaise), 'Latest accepted credit states'),
        metric('Repayments recorded', money(data.totalRepaymentsPaise), 'Not independently provider-verified'),
        metric('Recorded net receivable', money(data.netReceivablePaise), 'Accepted credit less repayments and reversals')
      );
      const rows = data.customers.map((customer) => {
        const row = document.createElement('tr');
        for (const value of [customer.customerId, String(customer.creditCount), money(customer.extendedPaise), money(customer.repaidPaise), money(customer.repaymentsPaise), money(customer.reversedPaise), money(customer.outstandingPaise)]) {
          const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
        }
        return row;
      });
      if (!rows.length) { const row = document.createElement('tr'); const cell = document.createElement('td'); cell.colSpan = 7; cell.className = 'empty-row'; cell.textContent = 'No accepted customer credit records are available.'; row.append(cell); rows.push(row); }
      $('customer-rows').replaceChildren(...rows);
      $('customer-evidence-list').replaceChildren(...Object.values(data.evidence).map((text) => { const item = document.createElement('li'); item.textContent = text; return item; }));
      setStatus($('customers-status'), `${data.customerCount} customer balances loaded through server revision ${data.serverRevision}.`, 'success');
      captureReport('customers', 'Customer balances', 'customers-scope', 'customer-metrics', ['customer-evidence-list']);
    } catch (error) {
      setStatus($('customers-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally { $('refresh-button').disabled = false; }
  }

  async function loadActivity() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('activity');
    setStatus($('activity-status'), 'Loading server-accepted sales activity…');
    try {
      const days = $('activity-range').value;
      const data = await request(`/v1/dashboard/activity?shopId=${encodeURIComponent(activeShopId)}&days=${encodeURIComponent(days)}`, {
        headers: { authorization: `Bearer ${session.token}` }
      });
      $('activity-scope').textContent = `${data.evidence.scope} Range ${data.range.fromDate} to ${data.range.throughDate} (${data.range.timezone}).`;
      const totals = data.totals;
      $('activity-metrics').replaceChildren(
        metric('Orders with sale started', String(totals.ordersWithSaleStarted), 'First accepted handover transition in the selected range'),
        metric('Net delivered item value change', money(totals.netDeliveredItemValueChangePaise), 'Includes later delivery/return state changes; not profit'),
        metric('Cash recorded', money(totals.staffDeclaredCashPaise), 'Staff-declared collections'),
        metric('Cash refunds recorded', money(totals.staffDeclaredCashRefundPaise), 'Staff-declared outflows'),
        metric('UPI recorded', money(totals.staffDeclaredUpiPaise), 'Staff-declared; provider not verified'),
        metric('UPI pending attempts started', money(totals.upiPendingAttemptsStartedPaise), 'Attempts entered during range; not current outstanding balance'),
        metric('Expenses recorded', money(totals.staffDeclaredExpensesPaise), 'Accepted staff-declared expenses; not profit')
      );
      const rows = data.daily.map((day) => {
        const row = document.createElement('tr');
        const values = [day.date, String(day.eventsAccepted), String(day.ordersWithSaleStarted), money(day.netDeliveredItemValueChangePaise),
          money(day.staffDeclaredCashPaise), money(day.staffDeclaredCashRefundPaise), money(day.staffDeclaredUpiPaise),
          money(day.upiPendingAttemptsStartedPaise), money(day.staffDeclaredExpensesPaise)];
        values.forEach((value) => { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); });
        return row;
      });
      $('activity-rows').replaceChildren(...rows);
      const expenseRows = data.expenseCategories.map((expense) => {
        const item = document.createElement('li');
        item.textContent = `${expense.category}: ${money(expense.amountPaise)} · ${expense.count} record${expense.count === 1 ? '' : 's'}`;
        return item;
      });
      if (expenseRows.length === 0) {
        const item = document.createElement('li'); item.textContent = 'No accepted expenses in this range.'; expenseRows.push(item);
      }
      $('activity-expense-categories').replaceChildren(...expenseRows);
      $('activity-evidence-list').replaceChildren(...Object.values(data.evidence).map((text) => {
        const item = document.createElement('li'); item.textContent = text; return item;
      }));
      setStatus($('activity-status'), `Sales activity refreshed at server revision ${data.serverRevision}.`, 'success');
      captureReport('activity', 'Sales activity', 'activity-scope', 'activity-metrics', ['activity-expense-categories', 'activity-evidence-list']);
    } catch (error) {
      setStatus($('activity-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally {
      $('refresh-button').disabled = false;
    }
  }

  async function loadCashflow() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('cashflow');
    setStatus($('cashflow-status'), 'Loading accepted money movement records…');
    try {
      const days = $('cashflow-range').value;
      const data = await request(`/v1/dashboard/cashflow?shopId=${encodeURIComponent(activeShopId)}&days=${encodeURIComponent(days)}`, {
        headers: { authorization: `Bearer ${session.token}` }
      });
      const totals = data.totals;
      $('cashflow-scope').textContent = `${data.evidence.scope} Range ${data.range.fromDate} to ${data.range.throughDate} (${data.range.timezone}).`;
      $('cashflow-metrics').replaceChildren(
        metric('Cash collections declared', money(totals.declaredCashInPaise), 'Staff-declared records'),
        metric('UPI receipts declared', money(totals.declaredUpiInPaise), 'Staff-declared; not provider-verified'),
        metric('Recorded refunds and expenses', money(totals.recordedOutflowPaise), 'Only accepted declared outflow records'),
        metric('Net recorded movement*', money(totals.netRecordedMovementPaise), 'Partial event total; not a reconciled cash balance')
      );
      $('cashflow-rows').replaceChildren(...data.daily.map((day) => {
        const row = document.createElement('tr');
        [day.date, money(day.declaredCashInPaise), money(day.declaredUpiInPaise),
          money(day.recordedCashRefundOutPaise), money(day.recordedExpensesOutPaise), money(day.netRecordedMovementPaise)]
          .forEach((value) => { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); });
        return row;
      }));
      const caveats = [
        data.evidence.net,
        data.evidence.inflows,
        data.evidence.outflows,
        data.evidence.exclusions,
        data.evidence.dates
      ];
      $('cashflow-evidence-list').replaceChildren(...caveats.map((text) => {
        const item = document.createElement('li'); item.textContent = text; return item;
      }));
      setStatus($('cashflow-status'), `Recorded money movements refreshed at server revision ${data.serverRevision}.`, 'success');
      captureReport('cashflow', 'Recorded money movements', 'cashflow-scope', 'cashflow-metrics', ['cashflow-evidence-list']);
    } catch (error) {
      setStatus($('cashflow-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally {
      $('refresh-button').disabled = false;
    }
  }

  async function loadProfitReadiness() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('profit');
    setStatus($('profit-status'), 'Checking accepted sales and cost-data coverage…');
    try {
      const days = $('profit-range').value;
      const data = await request(`/v1/dashboard/profit-readiness?shopId=${encodeURIComponent(activeShopId)}&days=${encodeURIComponent(days)}`, {
        headers: { authorization: `Bearer ${session.token}` }
      });
      $('profit-scope').textContent = `${data.evidence.scope} Range ${data.range.fromDate} to ${data.range.throughDate} (${data.range.timezone}).`;
      const figures = data.figures;
      const coverage = data.costCoverage;
      $('profit-metrics').replaceChildren(
        metric('Delivered item value change', money(figures.netDeliveredItemValueChangePaise), 'Accepted order-state changes after recorded returns; not profit'),
        metric('Expenses recorded', money(figures.staffDeclaredExpensesPaise), 'Accepted staff-declared expenses only'),
        metric('Estimated-cost coverage', `${coverage.costedNetDeliveredSaleLines} / ${coverage.netDeliveredSaleLines} sale lines`, 'Latest accepted sale lines with net delivery across all available history'),
        metric('Gross profit', figures.grossProfitPaise === null ? 'Unavailable' : money(figures.grossProfitPaise), 'Cost coverage is insufficient'),
        metric('Operating profit', figures.operatingProfitPaise === null ? 'Unavailable' : money(figures.operatingProfitPaise), 'Not calculated from incomplete records')
      );
      const appendEvidence = (id, values) => $(id).replaceChildren(...values.map((text) => {
        const item = document.createElement('li'); item.textContent = text; return item;
      }));
      appendEvidence('profit-missing-list', data.missingInputs);
      appendEvidence('profit-evidence-list', [data.evidence.sales, data.evidence.expenses, data.evidence.result]);
      setStatus($('profit-status'), `Profit readiness refreshed at server revision ${data.serverRevision}; calculation remains unavailable.`, 'success');
      captureReport('profit', 'Profit readiness', 'profit-scope', 'profit-metrics', ['profit-missing-list', 'profit-evidence-list']);
    } catch (error) {
      setStatus($('profit-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally {
      $('refresh-button').disabled = false;
    }
  }

  async function loadAuditHistory() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
    invalidateReport('audit');
    setStatus($('audit-status'), 'Loading accepted event metadata…');
    try {
      const limit = $('audit-limit').value;
      const data = await request(`/v1/dashboard/audit?shopId=${encodeURIComponent(activeShopId)}&limit=${encodeURIComponent(limit)}`, {
        headers: { authorization: `Bearer ${session.token}` }
      });
      $('audit-scope').textContent = `${data.evidence.scope} ${data.evidence.source}`;
      const rows = data.events.map((event) => {
        const row = document.createElement('tr');
        const other = event.changeCounts.customerCredits + event.changeCounts.repayments + event.changeCounts.expenses + event.changeCounts.purchaseReceipts + event.changeCounts.supplierPayments + event.changeCounts.purchaseReturns;
        const values = [String(event.serverRevision), event.receivedAt ? new Date(event.receivedAt).toLocaleString() : 'Timestamp unavailable', event.eventId, String(event.changeCounts.orders), String(event.changeCounts.stockMovements + event.changeCounts.stock), String(event.changeCounts.paymentEntries), String(other)];
        for (const value of values) { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); }
        return row;
      });
      if (!rows.length) { const row = document.createElement('tr'); const cell = document.createElement('td'); cell.colSpan = 7; cell.className = 'empty-row'; cell.textContent = 'No server-accepted events are available yet.'; row.append(cell); rows.push(row); }
      $('audit-rows').replaceChildren(...rows);
      $('audit-evidence-list').replaceChildren(...Object.values(data.evidence).map((text) => { const item = document.createElement('li'); item.textContent = text; return item; }));
      setStatus($('audit-status'), `${data.returnedEventCount} accepted events loaded through server revision ${data.serverRevision}.`, 'success');
      captureReport('audit', 'Accepted event history', 'audit-scope', null, ['audit-evidence-list']);
    } catch (error) {
      setStatus($('audit-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally { $('refresh-button').disabled = false; }
  }

  function signOutLocally() {
    session = null;
    activeShopId = null;
    reportSnapshots.clear();
    $('export-report-button').disabled = true;
    setView('overview');
    dashboardPanel.classList.add('hidden');
    loginPanel.classList.remove('hidden');
    $('login-form').reset();
    connection.textContent = 'Not connected';
    connection.dataset.state = 'offline';
  }

  $('login-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const button = event.currentTarget.querySelector('button[type="submit"]');
    button.disabled = true;
    setStatus($('login-status'), 'Checking owner credentials…');
    const credentials = {
      email: $('email').value.trim(), password: $('password').value,
      shopId: $('shop-id').value.trim(), deviceId: $('device-id').value.trim()
    };
    try {
      const result = await request('/v1/login', { method: 'POST', body: JSON.stringify(credentials) });
      $('password').value = '';
      if (result.role !== 'owner') {
        if (result.token) {
          try { await request('/v1/logout', { method: 'POST', headers: { authorization: `Bearer ${result.token}` } }); } catch { /* keep the owner-only denial visible */ }
        }
        throw new Error('This dashboard is limited to the shop owner account.');
      }
      session = result;
      activeShopId = result.shopId;
      setView('overview');
      loginPanel.classList.add('hidden');
      dashboardPanel.classList.remove('hidden');
      await loadOverview();
    } catch (error) {
      $('password').value = '';
      setStatus($('login-status'), showError(error), 'error');
    } finally {
      button.disabled = false;
    }
  });
  $('refresh-button').addEventListener('click', () => {
    if (!$('products-view').classList.contains('hidden')) loadProducts();
    else if (!$('inventory-view').classList.contains('hidden')) loadInventory();
    else if (!$('suppliers-view').classList.contains('hidden')) loadSuppliers();
    else if (!$('customers-view').classList.contains('hidden')) loadCustomers();
    else if (!$('activity-view').classList.contains('hidden')) loadActivity();
    else if (!$('cashflow-view').classList.contains('hidden')) loadCashflow();
    else if (!$('profit-view').classList.contains('hidden')) loadProfitReadiness();
    else if (!$('audit-view').classList.contains('hidden')) loadAuditHistory();
    else loadOverview();
  });
  $('export-report-button').addEventListener('click', () => {
    const report = reportSnapshots.get(activeView);
    if (!report || !session) return;
    const csv = buildOwnerReportCsv(report);
    const blobUrl = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = `chai-and-tapri-${activeView}-${new Date(report.loadedAt).toISOString().replaceAll(':', '-')}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(blobUrl), 0);
    const statusId = activeView === 'overview' ? 'dashboard-status' : `${activeView}-status`;
    setStatus($(statusId), 'Exported the currently displayed accepted-server report as CSV.', 'success');
  });
  $('overview-tab').addEventListener('click', () => setView('overview'));
  $('products-tab').addEventListener('click', () => { setView('products'); loadProducts(); });
  $('inventory-tab').addEventListener('click', () => { setView('inventory'); loadInventory(); });
  $('suppliers-tab').addEventListener('click', () => { setView('suppliers'); loadSuppliers(); });
  $('customers-tab').addEventListener('click', () => { setView('customers'); loadCustomers(); });
  $('activity-tab').addEventListener('click', () => { setView('activity'); loadActivity(); });
  $('cashflow-tab').addEventListener('click', () => { setView('cashflow'); loadCashflow(); });
  $('profit-tab').addEventListener('click', () => { setView('profit'); loadProfitReadiness(); });
  $('audit-tab').addEventListener('click', () => { setView('audit'); loadAuditHistory(); });
  $('activity-range').addEventListener('change', () => { if (!$('activity-view').classList.contains('hidden')) loadActivity(); });
  $('cashflow-range').addEventListener('change', () => { if (!$('cashflow-view').classList.contains('hidden')) loadCashflow(); });
  $('profit-range').addEventListener('change', () => { if (!$('profit-view').classList.contains('hidden')) loadProfitReadiness(); });
  $('audit-limit').addEventListener('change', () => { if (!$('audit-view').classList.contains('hidden')) loadAuditHistory(); });
  $('logout-button').addEventListener('click', async () => {
    const current = session;
    signOutLocally();
    try {
      if (current?.token) await request('/v1/logout', { method: 'POST', headers: { authorization: `Bearer ${current.token}` } });
      setStatus($('login-status'), 'Signed out; server session revocation confirmed.', 'success');
    } catch {
      setStatus($('login-status'), 'Signed out locally. Server revocation could not be confirmed; the session will also expire automatically.', 'error');
    }
  });
})();
