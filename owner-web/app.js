(() => {
  const $ = (id) => document.getElementById(id);
  const loginPanel = $('login-panel');
  const dashboardPanel = $('dashboard-panel');
  const connection = $('connection-state');
  let session = null;
  let activeShopId = null;

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
    const overview = view === 'overview';
    const products = view === 'products';
    const inventory = view === 'inventory';
    const activity = view === 'activity';
    $('refresh-button').textContent = overview ? 'Refresh' : products ? 'Refresh products' : inventory ? 'Refresh inventory' : 'Refresh sales activity';
    $('overview-view').classList.toggle('hidden', !overview);
    $('products-view').classList.toggle('hidden', !products);
    $('inventory-view').classList.toggle('hidden', !inventory);
    $('activity-view').classList.toggle('hidden', !activity);
    $('overview-tab').classList.toggle('active', overview);
    $('overview-tab').setAttribute('aria-current', overview ? 'page' : 'false');
    $('products-tab').classList.toggle('active', products);
    $('products-tab').setAttribute('aria-current', products ? 'page' : 'false');
    $('inventory-tab').classList.toggle('active', inventory);
    $('inventory-tab').setAttribute('aria-current', inventory ? 'page' : 'false');
    $('activity-tab').classList.toggle('active', activity);
    $('activity-tab').setAttribute('aria-current', activity ? 'page' : 'false');
  }

  async function loadOverview() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
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
    } catch (error) {
      setStatus($('inventory-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally {
      $('refresh-button').disabled = false;
    }
  }

  async function loadActivity() {
    if (!session || !activeShopId) return;
    $('refresh-button').disabled = true;
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
    } catch (error) {
      setStatus($('activity-status'), showError(error), 'error');
      if (/unauthorized|shop_access_denied|owner_access_required/.test(showError(error))) signOutLocally();
    } finally {
      $('refresh-button').disabled = false;
    }
  }

  function signOutLocally() {
    session = null;
    activeShopId = null;
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
    else if (!$('activity-view').classList.contains('hidden')) loadActivity();
    else loadOverview();
  });
  $('overview-tab').addEventListener('click', () => setView('overview'));
  $('products-tab').addEventListener('click', () => { setView('products'); loadProducts(); });
  $('inventory-tab').addEventListener('click', () => { setView('inventory'); loadInventory(); });
  $('activity-tab').addEventListener('click', () => { setView('activity'); loadActivity(); });
  $('activity-range').addEventListener('change', () => { if (!$('activity-view').classList.contains('hidden')) loadActivity(); });
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
