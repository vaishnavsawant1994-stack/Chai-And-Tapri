(() => {
  'use strict';

  const products = [
    { id: 'cutting', name: 'Cutting Chai', category: 'Chai', price: 1000, stock: 34, icon: '🍵', visual: 'visual-chai', aliases: ['cutting', 'cutting chai', 'cutting tea'] },
    { id: 'regular-chai', name: 'Regular Chai', category: 'Chai', price: 1500, stock: 26, icon: '☕', visual: 'visual-chai', aliases: ['chai', 'tea', 'regular chai'] },
    { id: 'masala-chai', name: 'Masala Chai', category: 'Chai', price: 2000, stock: 18, icon: '🫖', visual: 'visual-chai', aliases: ['masala', 'masala chai'] },
    { id: 'coffee', name: 'Hot Coffee', category: 'Beverages', price: 2500, stock: 14, icon: '☕', visual: 'visual-coffee', aliases: ['coffee', 'hot coffee'] },
    { id: 'samosa', name: 'Samosa', category: 'Snacks', price: 2000, stock: 12, icon: '🥟', visual: 'visual-snack', aliases: ['samosa', 'samosas'] },
    { id: 'water', name: 'Water Bottle', category: 'Beverages', price: 2000, stock: 20, icon: '🧴', visual: 'visual-water', aliases: ['water', 'water bottle', 'bottle'] },
    { id: 'bun-maska', name: 'Bun Maska', category: 'Bakery', price: 3000, stock: 9, icon: '🥯', visual: 'visual-bun', aliases: ['bun maska', 'bun'] },
    { id: 'poha', name: 'Poha', category: 'Breakfast', price: 2500, stock: 11, icon: '🥣', visual: 'visual-poha', aliases: ['poha'] },
    { id: 'biscuit', name: 'Biscuit', category: 'Snacks', price: 1000, stock: 30, icon: '🍪', visual: 'visual-biscuit', aliases: ['biscuit', 'biscuits'] },
    { id: 'lemon-soda', name: 'Lemon Soda', category: 'Beverages', price: 2500, stock: 8, icon: '🍋', visual: 'visual-juice', aliases: ['lemon soda', 'soda'] },
    { id: 'toast', name: 'Butter Toast', category: 'Bakery', price: 2000, stock: 7, icon: '🍞', visual: 'visual-bun', aliases: ['toast', 'butter toast'] },
    { id: 'vada-pav', name: 'Vada Pav', category: 'Snacks', price: 2500, stock: 6, icon: '🍔', visual: 'visual-snack', aliases: ['vada pav', 'vadapav'] }
  ];

  const $ = id => document.getElementById(id);
  const money = paise => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(Math.round(paise / 100));
  const numberWords = { ek: 1, eka: 1, one: 1, a: 1, '1': 1, do: 2, dono: 2, two: 2, '2': 2, teen: 3, three: 3, '3': 3, chaar: 4, char: 4, four: 4, '4': 4, paanch: 5, panch: 5, five: 5, '5': 5 };

  function load(key, fallback) {
    try { const value = localStorage.getItem(key); return value ? JSON.parse(value) : fallback; }
    catch { return fallback; }
  }
  function storedState() {
    const current = load('ct-store-v1', null);
    if (current) return current;
    const legacyDraft = load('ct-draft', null);
    const customerMap = { 'Ramesh Patil': 'ramesh', 'Neha Sharma': 'neha', 'Office order · Mehta & Co.': 'office' };
    return {
      sales: load('ct-sales', []),
      stock: load('ct-stock', Object.fromEntries(products.map(product => [product.id, product.stock]))),
      draft: legacyDraft ? { lines: legacyDraft.lines || [], method: legacyDraft.method || 'cash', customerId: customerMap[legacyDraft.customer] || '', operationId: '' } : undefined
    };
  }
  const engine = window.TapriTransactions.createTransactionEngine({
    catalog: products,
    initialState: storedState(),
    persist: next => localStorage.setItem('ct-store-v1', JSON.stringify(next))
  });
  const initialStore = engine.snapshot();
  const state = {
    cart: new Map(initialStore.draft.lines || []),
    method: initialStore.draft.method || 'cash',
    operationId: initialStore.draft.operationId || '',
    category: 'Popular',
    search: '',
    online: true,
    customer: initialStore.draft.customerId || ''
  };
  function persistDraft() {
    const saved = engine.saveDraft({ lines: [...state.cart.entries()], method: state.method, customerId: state.customer, operationId: '' });
    state.operationId = saved.operationId;
  }
  function refreshDraftFromEngine() {
    const draft = engine.snapshot().draft;
    state.cart = new Map(draft.lines || []);
    state.method = draft.method || 'cash';
    state.customer = draft.customerId || '';
    state.operationId = draft.operationId || '';
  }
  function runDraftCommand(type, payload, source = 'touch') {
    const result = engine.execute({ type, idempotencyKey: makeCommandId(), source, actorId: 'demo-owner', payload });
    refreshDraftFromEngine();
    return result;
  }
  function getSales() { return engine.snapshot().sales; }
  function productById(id) { return products.find(product => product.id === id); }
  function totalItems() { return [...state.cart.values()].reduce((sum, qty) => sum + qty, 0); }
  function subtotal() { return [...state.cart.entries()].reduce((sum, [id, qty]) => sum + productById(id).price * qty, 0); }

  function visibleProducts() {
    const query = state.search.trim().toLocaleLowerCase();
    return products.filter(product => {
      const categoryMatch = state.category === 'Popular' || product.category === state.category;
      const queryMatch = !query || product.name.toLocaleLowerCase().includes(query) || product.aliases.some(alias => alias.includes(query));
      return categoryMatch && queryMatch;
    });
  }

  function renderCatalog() {
    const grid = $('product-grid');
    grid.replaceChildren();
    const items = visibleProducts();
    $('visible-count').textContent = String(items.length).padStart(2, '0');
    items.forEach(product => {
      const qty = state.cart.get(product.id) || 0;
      const stock = engine.getStock(product.id);
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `product-card${qty ? ' in-cart' : ''}${stock === 0 ? ' unavailable' : ''}`;
      button.setAttribute('aria-label', `${product.name}, ${money(product.price)}${stock === 0 ? ', out of stock' : ''}${qty ? `, ${qty} in current order` : ''}`);
      button.disabled = stock === 0;
      button.innerHTML = `<span class="add-hint">${qty || '+'}</span><span class="product-visual ${product.visual}" aria-hidden="true">${product.icon}</span><span class="product-name">${product.name}</span><span class="product-bottom"><strong class="product-price">${money(product.price)}</strong><small class="product-stock${stock < 6 ? ' low' : ''}">${stock === 0 ? 'Sold out' : `${stock} left`}</small></span>`;
      button.addEventListener('click', () => addProduct(product.id));
      grid.append(button);
    });
    if (items.length === 0) grid.innerHTML = '<div class="no-results"><span>⌕</span><strong>No products found</strong><small>Try another name or category.</small></div>';
    document.querySelectorAll('.category-pill').forEach(button => button.classList.toggle('selected', button.dataset.category === state.category));
  }

  function renderCategories() {
    const categories = ['Popular', 'Chai', 'Snacks', 'Beverages', 'Bakery', 'Breakfast'];
    $('category-row').innerHTML = categories.map(category => `<button class="category-pill${category === state.category ? ' selected' : ''}" role="tab" aria-selected="${category === state.category}" data-category="${category}">${category}</button>`).join('');
    $('category-row').addEventListener('click', event => {
      const button = event.target.closest('[data-category]');
      if (!button) return;
      state.category = button.dataset.category;
      renderCatalog();
    });
  }

  function renderCart() {
    const content = $('cart-content');
    content.replaceChildren();
    $('empty-cart').hidden = state.cart.size > 0;
    $('cart-bottom').hidden = state.cart.size === 0;
    state.cart.forEach((qty, id) => {
      const product = productById(id);
      const line = document.createElement('div');
      line.className = 'cart-line';
      line.innerHTML = `<div class="line-main"><span class="line-name">${product.name}</span><small class="line-meta">${money(product.price)} each</small></div><strong class="line-total">${money(product.price * qty)}</strong><div class="quantity-control" aria-label="Quantity for ${product.name}"><button type="button" data-quantity="minus" data-id="${id}" aria-label="Decrease ${product.name}">−</button><span>${qty}</span><button type="button" data-quantity="plus" data-id="${id}" aria-label="Increase ${product.name}">+</button></div><button class="remove-line" type="button" data-quantity="remove" data-id="${id}" aria-label="Remove ${product.name}">×</button>`;
      content.append(line);
    });
    const amount = subtotal();
    $('subtotal').textContent = money(amount);
    $('total').textContent = money(amount);
    $('button-total').textContent = money(amount);
    $('complete-sale').disabled = state.cart.size === 0;
    $('draft-badge').textContent = state.cart.size ? `${totalItems()} ITEM${totalItems() === 1 ? '' : 'S'}` : 'DRAFT';
    $('credit-picker').hidden = state.method !== 'udhar';
    document.querySelectorAll('.payment-option').forEach(button => button.classList.toggle('selected', button.dataset.method === state.method));
    const store = engine.snapshot();
    $('customer-label').textContent = state.customer ? store.customers[state.customer]?.name || 'Khata customer' : 'Walk-in customer';
    $('credit-customer').value = state.customer || '';
    renderCatalog();
    updateSummary();
  }

  function addProduct(id, quantity = 1, source = 'touch') {
    try { runDraftCommand('order.add_item', { productId: id, quantity }, source); }
    catch (commandError) { toast(commandError.code === 'INSUFFICIENT_STOCK' ? 'Not enough stock for this quantity' : 'Product was not added', commandError.message); return false; }
    $('order-time').textContent = 'Just now';
    renderCart();
    return true;
  }

  function updateQuantity(id, action) {
    const qty = state.cart.get(id) || 0;
    let quantity = qty;
    if (action === 'remove' || (action === 'minus' && qty <= 1)) quantity = 0;
    else if (action === 'minus') quantity = qty - 1;
    else if (action === 'plus') { addProduct(id); return; }
    try { runDraftCommand('order.set_item_quantity', { productId: id, quantity }); }
    catch (commandError) { toast('Could not update the draft', commandError.message); }
    renderCart();
  }

  function selectMethod(method) {
    const previousMethod = state.method;
    const previousCustomer = state.customer;
    try { runDraftCommand('order.set_payment_intent', { paymentMethod: method }); }
    catch (commandError) { state.method = previousMethod; state.customer = previousCustomer; toast('Could not save the payment choice', commandError.message); }
    renderCart();
  }

  function commitSale() {
    if (state.cart.size === 0) return;
    const customerId = $('credit-customer').value;
    if (state.method === 'udhar' && !customerId) {
      toast('Choose a khata customer first', 'Credit sales need a customer account.');
      $('credit-customer').focus();
      return;
    }
    const commandId = state.operationId || makeCommandId();
    try {
      const result = engine.execute({
        type: 'sale.commit', idempotencyKey: commandId, actorId: 'demo-owner', shopId: 'demo-shop', source: 'touch',
        payload: {
          lines: [...state.cart.entries()].map(([productId, quantity]) => ({ productId, quantity })),
          paymentMethod: state.method === 'upi' ? 'upi_pending' : state.method,
          customerId: state.method === 'udhar' ? customerId : ''
        }
      });
      state.cart.clear(); state.customer = ''; state.method = 'cash'; state.operationId = '';
      renderCart();
      toast('Sale recorded on this device', `${money(result.totalPaise)} · ${paymentLabel(result.paymentState)}`);
    } catch (commandError) {
      toast(commandError.code === 'INSUFFICIENT_STOCK' ? 'Not enough stock' : 'Sale was not recorded', commandError.message);
    }
  }

  function makeCommandId() {
    return `cmd-${typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`}`;
  }
  function paymentLabel(status) {
    if (status === 'staff_declared') return 'Cash · staff-declared';
    if (status === 'upi_pending' || status === 'pending') return 'UPI · pending';
    if (status === 'credit') return 'Udhar · receivable';
    if (status === 'credit_reversed') return 'Udhar reversed';
    if (status === 'refunded') return 'Refunded';
    if (status === 'partially_paid') return 'Partially paid';
    if (status === 'settled') return 'Settled';
    if (status === 'unpaid') return 'Unpaid';
    return 'Payment status';
  }

  function renderB2Queue() {
    const snapshot = engine.snapshot();
    const queue = snapshot.orders.filter(order => !order.voided && order.fulfillment !== 'cancelled' && (order.fulfillment !== 'given' || ['unpaid', 'upi_pending', 'pending', 'partially_paid'].includes(order.paymentStatus)));
    $('b2-order-count').textContent = String(queue.length);
    const list = $('b2-order-list');
    list.replaceChildren();
    if (!queue.length) { list.innerHTML = '<div class="b2-queue-empty">No open orders. Try “naya order, do cutting chai, ek samosa”.</div>'; return; }
    queue.slice(0, 12).forEach(order => {
      const card = document.createElement('div');
      card.className = 'b2-order-card';
      const summary = order.lines.map(line => `${line.quantity} × ${line.name}`).join(' · ');
      const total = order.lines.reduce((sum, line) => sum + line.amountPaise, 0);
      const paymentClass = ['upi_pending', 'pending'].includes(order.paymentStatus) ? 'pending' : '';
      card.innerHTML = `<span class="b2-order-main"><strong>Order #${order.number}</strong><small>${escapeHTML(summary)}</small></span><strong class="b2-order-total">${money(total)}<small>${escapeHTML(paymentLabel(order.paymentStatus))}</small></strong><span class="order-state-chip"><span class="${order.fulfillment === 'given' ? 'given' : ''}">${escapeHTML(order.fulfillment)}</span><span class="${paymentClass}">${escapeHTML(paymentLabel(order.paymentStatus))}</span></span>`;
      list.append(card);
    });
  }

  function runB2Command(rawText = $('b2-command').value) {
    const snapshot = engine.snapshot();
    const interpreted = window.TapriVoiceCommands.interpret(rawText, { catalog: products, orders: snapshot.orders, customers: snapshot.customers });
    const feedback = $('b2-feedback');
    if (!interpreted.ok) {
      feedback.textContent = interpreted.message;
      feedback.className = 'b2-feedback error';
      return;
    }
    try {
      const result = engine.execute({ ...interpreted.command, idempotencyKey: makeCommandId(), actorId: 'demo-owner', shopId: 'demo-shop', source: 'voice_text_preview' });
      feedback.textContent = result.orderNumber ? `${interpreted.message} · Order #${result.orderNumber}` : interpreted.message;
      feedback.className = 'b2-feedback success';
      $('b2-command').value = '';
      renderB2Queue();
      toast('Counter command recorded', interpreted.message);
    } catch (commandError) {
      feedback.textContent = commandError.message;
      feedback.className = 'b2-feedback error';
    }
  }

  function updateSummary() {
    const today = new Date();
    const todayKey = `${today.getFullYear()}-${today.getMonth()}-${today.getDate()}`;
    const todaySales = getSales().filter(sale => {
      const date = new Date(sale.createdAt);
      return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}` === todayKey;
    });
    const total = todaySales.filter(sale => !sale.voided).reduce((sum, sale) => sum + (sale.totalPaise ?? sale.total ?? 0), 0);
    $('today-total').textContent = money(total);
    $('open-order-count').textContent = String(state.cart.size ? 1 : 0);
  }

  function toast(title, detail = '') {
    const node = document.createElement('div');
    node.className = 'toast';
    node.innerHTML = `<span class="toast-icon">✓</span><span>${escapeHTML(title)}${detail ? `<small>${escapeHTML(detail)}</small>` : ''}</span>`;
    $('toast-region').append(node);
    window.setTimeout(() => node.remove(), 3400);
  }
  function escapeHTML(text) { return String(text).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]); }

  function showMessage(title, copy) {
    $('message-title').textContent = title;
    $('message-copy').textContent = copy;
    $('message-modal').showModal();
  }

  function openHistory() {
    const list = $('history-list');
    const sales = getSales();
    list.replaceChildren();
    $('history-count').textContent = `${sales.length} sale${sales.length === 1 ? '' : 's'}`;
    if (!sales.length) {
      list.innerHTML = '<div class="history-empty">No sales recorded yet. Your completed orders will appear here.</div>';
    } else {
      sales.slice(0, 30).forEach(sale => {
        const item = document.createElement('div');
        item.className = 'history-item';
        const description = sale.lines.map(line => `${line.quantity ?? line.qty} × ${line.name}`).join(' · ');
        const time = new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit' }).format(new Date(sale.createdAt));
        const amount = sale.totalPaise ?? sale.total ?? 0;
        const method = sale.payment || paymentLabel(sale.paymentState);
        const stateLabel = sale.voided ? `Voided · ${sale.voidReason || 'corrected'}` : method;
        item.innerHTML = `<span class="history-mark">${sale.voided ? '↶' : '✓'}</span><span class="history-item-main"><strong>${escapeHTML(description)}</strong><small>${time} · ${escapeHTML(sale.id)}</small></span><strong class="history-item-total">${money(amount)}<small class="history-state">${escapeHTML(stateLabel)}</small></strong>${sale.voided || sale.fulfillment !== 'given' || !sale.paymentMethod ? '' : `<button class="void-sale-button" type="button" data-void-sale="${escapeHTML(sale.id)}">Correct</button>`}`;
        list.append(item);
      });
    }
    $('history-modal').showModal();
  }

  function parseCommand() {
    const input = $('voice-input').value.trim().toLowerCase();
    const results = $('voice-results');
    results.hidden = false;
    results.replaceChildren();
    if (!input) { results.innerHTML = '<p>Type a short command to preview the product matches.</p>'; return; }
    const matches = products.map(product => {
      let foundAlias = product.aliases.sort((a, b) => b.length - a.length).find(alias => input.includes(alias));
      if (!foundAlias) return null;
      const prefix = input.slice(0, input.indexOf(foundAlias)).trim().split(/[\s,]+/).filter(Boolean).pop();
      const quantity = numberWords[prefix] || 1;
      return { product, quantity };
    }).filter(Boolean);
    if (!matches.length) { results.innerHTML = '<p>No catalogue match. Nothing was added. Try “do cutting chai, ek samosa”.</p>'; return; }
    const heading = document.createElement('p');
    heading.textContent = 'Review these matches, then add them to the current draft:';
    results.append(heading);
    matches.forEach(({ product, quantity }) => {
      const row = document.createElement('div');
      row.className = 'voice-match';
      row.innerHTML = `<span>${quantity} × ${escapeHTML(product.name)} · ${money(product.price * quantity)}</span><button type="button" data-voice-add="${product.id}" data-qty="${quantity}">Add to order</button>`;
      results.append(row);
    });
  }

  function toggleNetwork() {
    state.online = !state.online;
    $('network-label').textContent = state.online ? 'Online · demo' : 'Offline · local';
    $('network-toggle').classList.toggle('offline', !state.online);
    toast(state.online ? 'Network demo: online' : 'Network demo: offline', 'Sale records remain in browser storage; no cloud is connected.');
  }

  function init() {
    try { state.operationId = engine.saveDraft(initialStore.draft).operationId; } catch { /* Keep the in-memory draft visible if storage is unavailable. */ }
    renderCategories();
    renderCart();
    $('product-search').addEventListener('input', event => { state.search = event.target.value; renderCatalog(); });
    $('product-search').addEventListener('keydown', event => { if (event.key === 'Escape') { event.currentTarget.value = ''; state.search = ''; renderCatalog(); } });
    $('cart-content').addEventListener('click', event => {
      const button = event.target.closest('[data-quantity]');
      if (button) updateQuantity(button.dataset.id, button.dataset.quantity);
    });
    document.querySelectorAll('.payment-option').forEach(button => button.addEventListener('click', () => selectMethod(button.dataset.method)));
    $('complete-sale').addEventListener('click', commitSale);
    $('credit-customer').addEventListener('change', event => {
      const previousCustomer = state.customer;
      try { runDraftCommand('order.set_customer', { customerId: event.target.value }); }
      catch { state.customer = previousCustomer; toast('Could not save the customer choice', 'The previous draft is still available.'); }
      renderCart();
    });
    $('history-button').addEventListener('click', openHistory);
    $('notification-button')?.addEventListener('click', () => showMessage('Notifications', 'Low-stock and synchronization alerts are planned. No live notification service is connected in this prototype.'));
    $('profile-button')?.addEventListener('click', () => showMessage('Owner profile', 'Staff identity and permissions are demo-only. Sign-in and role enforcement are not implemented.'));
    $('history-list').addEventListener('click', event => {
      const button = event.target.closest('[data-void-sale]');
      if (!button) return;
      const sale = getSales().find(item => item.id === button.dataset.voidSale);
      if (!sale || !sale.paymentMethod) { showMessage('Correction unavailable', 'This older demo record has no transaction-engine evidence, so it cannot be safely reversed.'); return; }
      const reason = window.prompt('Why are you correcting this sale?');
      if (!reason || !reason.trim()) return;
      const stockDisposition = window.prompt('Can the returned items be sold again? Type “restock” or “not_restocked”.', 'restock');
      if (!['restock', 'not_restocked'].includes((stockDisposition || '').trim().toLowerCase())) { toast('Correction cancelled', 'Choose restock or not_restocked.'); return; }
      const cashRefundDeclared = sale.paymentMethod !== 'cash' || window.confirm(`Confirm that ${money(sale.totalPaise)} cash was returned to the customer.`);
      if (!cashRefundDeclared) return;
      try {
        engine.execute({ type: 'sale.void', idempotencyKey: makeCommandId(), actorId: 'demo-owner', source: 'touch', payload: { saleId: sale.id, reason: reason.trim(), stockDisposition: stockDisposition.trim().toLowerCase(), cashRefundDeclared } });
        openHistory();
        toast('Correction recorded', 'Original sale remains in the audit history.');
      } catch (correctionError) { toast('Correction was not recorded', correctionError.message); }
    });
    $('history-modal').addEventListener('click', event => { if (event.target === $('history-modal')) $('history-modal').close(); });
    const speechInput = window.TapriSpeechInput.createSpeechInput();
    function bindSpeechButton(buttonId, inputId, languageId, statusId) {
      const button = $(buttonId);
      if (!speechInput.supported()) button.title = 'Speech recognition is not available in this browser; use the text field.';
      button.addEventListener('click', () => {
        speechInput.stop();
        speechInput.setHandlers({ onTranscript: transcript => { $(inputId).value = transcript; $(inputId).focus(); }, onStatus: (message, stateName) => {
          const status = $(statusId);
          status.textContent = message;
          status.dataset.state = stateName;
          button.textContent = stateName === 'listening' ? 'Listening…' : 'Speak';
        } });
        speechInput.start({ lang: $(languageId).value });
      });
    }
    bindSpeechButton('listen-voice', 'voice-input', 'voice-language', 'voice-speech-status');
    bindSpeechButton('listen-b2', 'b2-command', 'b2-language', 'b2-speech-status');
    $('voice-modal').addEventListener('close', () => speechInput.stop());
    $('b2-modal').addEventListener('close', () => speechInput.stop());
    $('voice-button').addEventListener('click', () => $('voice-modal').showModal());
    $('open-b2').addEventListener('click', () => { $('voice-modal').close(); renderB2Queue(); $('b2-modal').showModal(); });
    $('end-b2').addEventListener('click', () => { $('b2-modal').close(); toast('Counter preview ended', 'No continuous listening or Bluetooth session was running.'); });
    $('run-b2-command').addEventListener('click', () => runB2Command());
    $('b2-command').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); runB2Command(); } });
    $('b2-modal').addEventListener('click', event => { if (event.target === $('b2-modal')) $('b2-modal').close(); });
    document.querySelectorAll('[data-b2-sample]').forEach(button => button.addEventListener('click', () => { $('b2-command').value = button.dataset.b2Sample; $('b2-command').focus(); }));
    $('parse-voice').addEventListener('click', parseCommand);
    $('voice-input').addEventListener('keydown', event => { if (event.key === 'Enter') parseCommand(); });
    $('voice-results').addEventListener('click', event => {
      const button = event.target.closest('[data-voice-add]');
      if (button && addProduct(button.dataset.voiceAdd, Number(button.dataset.qty), 'voice_text_preview')) toast('Added to current order', 'Review the draft before recording the sale.');
    });
    $('network-toggle').addEventListener('click', toggleNetwork);
    $('offline-info').addEventListener('click', () => showMessage('Saved on this device first', 'This browser prototype stores demo orders locally. It has no server, account security, encrypted database, or real synchronization. A production app must add these before shop use.'));
    $('customer-button').addEventListener('click', () => {
      if (state.method !== 'udhar') selectMethod('udhar');
      $('credit-picker').hidden = false;
      $('credit-customer').focus();
    });
    $('scan-button').addEventListener('click', () => {
      const value = window.prompt('Prototype scan: enter a product name or demo SKU (e.g. samosa)');
      if (!value) return;
      const match = products.find(product => product.name.toLowerCase() === value.trim().toLowerCase() || product.aliases.includes(value.trim().toLowerCase()));
      if (match) { addProduct(match.id, 1, 'scan_demo'); toast('Product added', `${match.name} · ${money(match.price)}`); }
      else toast('No matching product', 'Nothing was added. Check the catalogue name.');
    });
    $('stock-link').addEventListener('click', () => showMessage('Stock overview', 'Stock counts in this prototype update after a recorded sale. Stock receiving, counting, and audit adjustments are not connected yet.'));
    document.querySelectorAll('[data-nav]').forEach(button => button.addEventListener('click', () => {
      if (button.dataset.nav === 'sell') window.scrollTo({ top: 0, behavior: 'smooth' });
      else if (button.dataset.nav === 'history' || button.dataset.nav === 'orders') openHistory();
      else showMessage(button.textContent.trim(), 'This screen is part of the product plan and has not been built in this prototype.');
    }));
    $('edit-favourites').addEventListener('click', () => showMessage('Popular products', 'Favourites are configured demo products in this prototype. Owner-managed shortcuts will be part of product setup.'));
    $('order-more').addEventListener('click', () => {
      if (state.cart.size) {
        if (!window.confirm('Clear this unrecorded draft?')) return;
        const previous = { cart: new Map(state.cart), customer: state.customer, method: state.method };
        state.cart.clear(); state.customer = ''; state.method = 'cash';
        try { persistDraft(); } catch { state.cart = previous.cart; state.customer = previous.customer; state.method = previous.method; toast('Could not clear the saved draft', 'No changes were made.'); renderCart(); return; }
        renderCart(); toast('Draft cleared', 'No sale was recorded.');
        return;
      }
      if (!window.confirm('Reset all demo sales and stock stored in this browser?')) return;
      engine.reset(); state.cart = new Map(); state.operationId = ''; state.customer = ''; state.method = 'cash';
      renderCart(); toast('Prototype data reset', 'Demo sales and stock returned to their starting values.');
    });
    $('discount-button').addEventListener('click', () => showMessage('Discounts', 'Discount workflows are not enabled in this CT-06 prototype.'));
    document.addEventListener('keydown', event => { if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); $('product-search').focus(); } });
  }

  init();
})();
