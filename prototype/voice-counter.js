/* Shared normalized text-command parser for typed and speech transcripts. */
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.TapriVoiceCommands = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const normalizeWords = {
    'ऑर्डर':'order','आर्डर':'order','नया':'naya','नवीन':'nava','बनाओ':'banao',
    'दो':'do','दोन':'don','दोनो':'dono','एक':'ek','एका':'eka','तीन':'teen','चार':'chaar','पांच':'paanch','पाँच':'paanch',
    'कटिंग':'cutting','चाय':'chai','चहा':'chai','चाय्':'chai','समोसा':'samosa','बिस्किट':'biscuit',
    'तैयार':'ready','तयार':'ready','है':'hai','आहे':'hai','दिया':'de diya','दिला':'de diya','दी':'de di','दीया':'de diya',
    'कॅश':'cash','कैश':'cash','नकद':'cash','नगदी':'cash','रुपये':'rupees','रुपया':'rupee','मिले':'mila','मिळाले':'mila','मिला':'mila',
    'यूपीआई':'upi','युपीआई':'upi','पेमेंट':'payment','बाकी':'pending','रद्द':'radd','कैंसल':'cancel','काढून':'remove','टाक':'remove','हटाओ':'remove','hatao':'remove','निकाल':'remove',
    'बारा':'12','बारह':'12','१२':'12','पन्नास':'50','पचास':'50','५०':'50'
  };
  const quantityWords = { ek:1, eka:1, one:1, a:1, '1':1, do:2, don:2, dono:2, two:2, '2':2, teen:3, three:3, '3':3, chaar:4, char:4, four:4, '4':4, paanch:5, panch:5, five:5, '5':5 };
  const devanagariDigits = { '०':'0','१':'1','२':'2','३':'3','४':'4','५':'5','६':'6','७':'7','८':'8','९':'9' };
  const escapeRegExp = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

  function normalize(text) {
    return String(text || '').toLowerCase().replace(/[०-९]/g, digit => devanagariDigits[digit])
      .replace(/[।,;]+/g, ' ').replace(/[^\p{L}\p{M}\p{N}#\s]/gu, ' ')
      .split(/\s+/).filter(Boolean).map(word => normalizeWords[word] || word).join(' ').replace(/\s+/g, ' ').trim();
  }

  function extractItems(text, catalog) {
    const candidates = [];
    (catalog || []).forEach(product => (product.aliases || [product.name]).forEach(alias => {
      const normalizedAlias = normalize(alias);
      if (!normalizedAlias || /[^a-z0-9 ]/.test(normalizedAlias)) return;
      const pattern = new RegExp(`(^|[^a-z0-9])(${escapeRegExp(normalizedAlias)})(?=$|[^a-z0-9])`, 'g');
      let match;
      while ((match = pattern.exec(text)) !== null) {
        const start = match.index + match[1].length;
        candidates.push({ start, end: start + match[2].length, product });
      }
    }));
    candidates.sort((a,b) => a.start-b.start || (b.end-b.start)-(a.end-a.start));
    const chosen = [];
    candidates.forEach(candidate => { if (!chosen.some(item => candidate.start < item.end && candidate.end > item.start)) chosen.push(candidate); });
    return chosen.sort((a,b) => a.start-b.start).map(item => {
      const prefix = text.slice(0,item.start).replace(/[^a-z0-9\s]/g,' ').trim().split(/\s+/).filter(Boolean).pop();
      return { productId:item.product.id, name:item.product.name, quantity:quantityWords[prefix] || 1 };
    });
  }

  function interpret(originalText, { catalog, orders, customers = {} }) {
    const normalized = normalize(originalText);
    if (!normalized) return { ok:false, originalText:String(originalText || ''), message:'Say a command or enter text to preview.' };
    const items = extractItems(normalized, catalog);
    const startsOrder = /\b(new order|naya order|nava order|order naya|order banao)\b/.test(normalized);
    if (startsOrder) {
      if (!items.length) return { ok:false, originalText, message:'I could not match an item. Nothing was recorded.' };
      return { ok:true, originalText, normalizedText:normalized, command:{type:'order.create',payload:{lines:items.map(({productId,quantity})=>({productId,quantity}))}}, message:`New order · ${items.map(item=>`${item.quantity} × ${item.name}`).join(', ')}` };
    }
    const numberMatch = normalized.match(/\border\s*#?\s*(\d+)\b/);
    if (!numberMatch) return { ok:false, originalText, normalizedText:normalized, message:'Name the order number so I can update the right customer.' };
    const order = (orders || []).find(item => item.number === Number(numberMatch[1]));
    if (!order) return { ok:false, originalText, normalizedText:normalized, message:`Order ${numberMatch[1]} is not in the live queue.` };

    if (/\b(cancel|cancelled|cancel karo|cancel kar|radd)\b/.test(normalized) && !/\b(remove)\b/.test(normalized)) {
      return { ok:true, originalText, command:{type:'order.set_fulfillment',payload:{orderId:order.id,status:'cancelled',reason:'Cancelled by staff through voice command'}}, message:`Cancel order ${order.number}` };
    }
    if (/\b(remove)\b/.test(normalized)) {
      if (!items.length) return { ok:false, originalText, message:'Name the item to remove. Nothing was changed.' };
      return { ok:true, originalText, normalizedText:normalized, command:{type:'order.remove_line',payload:{orderId:order.id,productId:items[0].productId,quantity:items[0].quantity}}, message:`Remove ${items[0].quantity} × ${items[0].name} from order ${order.number}` };
    }
    if (items.length && /\b(de diya|de di|given|handed over|customer le gaya)\b/.test(normalized)) {
      return { ok:true, originalText, normalizedText:normalized, command:{type:'order.handover_partial',payload:{orderId:order.id,lines:items.map(({productId,quantity})=>({productId,quantity}))}}, message:`Hand over ${items.map(item=>`${item.quantity} × ${item.name}`).join(', ')} from order ${order.number}` };
    }
    if (/\b(de diya|de di|given|handed over|customer le gaya)\b/.test(normalized)) return { ok:true, originalText, command:{type:'order.set_fulfillment',payload:{orderId:order.id,status:'given'}}, message:`Mark order ${order.number} given` };
    if (/\b(ready|taiyar|tayyar)\b/.test(normalized)) return { ok:true, originalText, command:{type:'order.set_fulfillment',payload:{orderId:order.id,status:'ready'}}, message:`Mark order ${order.number} ready` };
    if (/\b(preparing|ban raha|bana rahe|making)\b/.test(normalized)) return { ok:true, originalText, command:{type:'order.set_fulfillment',payload:{orderId:order.id,status:'preparing'}}, message:`Mark order ${order.number} preparing` };
    if (/\b(upi verified|upi confirm|payment verified)\b/.test(normalized)) return { ok:false, originalText, message:'This prototype has no provider evidence, so it cannot verify UPI. Use “UPI pending”.' };
    if (/\b(upi|gpay|phonepe|paytm)\b/.test(normalized)) return { ok:true, originalText, command:{type:'order.record_payment',payload:{orderId:order.id,paymentMethod:'upi_pending'}}, message:`Record UPI pending for order ${order.number}` };
    if (/\b(cash|nagdi|cash mila|cash received)\b/.test(normalized)) {
      const amountText = normalized.replace(/\border\s*#?\s*\d+\b/, '');
      const amount = amountText.match(/\b(\d+)\s*(?:rupees?|rs)?\s*(?:cash|mila|received)\b/);
      const payload = {orderId:order.id,paymentMethod:'cash'};
      if (amount) payload.amountPaise = Number(amount[1]) * 100;
      return { ok:true, originalText, command:{type:'order.record_payment',payload}, message:`Record cash declared for order ${order.number}` };
    }
    if (/\budhar\b|\bkhata\b/.test(normalized)) {
      const customer = Object.values(customers).find(item => normalized.includes(item.name.toLowerCase().split(' ')[0]));
      if (!customer) return { ok:false, originalText, message:'Choose the customer account before recording Udhar.' };
      return { ok:true, originalText, command:{type:'order.record_payment',payload:{orderId:order.id,paymentMethod:'udhar',customerId:customer.id}}, message:`Record Udhar for ${customer.name}` };
    }
    if (items.length) return { ok:true, originalText, command:{type:'order.add_line',payload:{orderId:order.id,productId:items[0].productId,quantity:items[0].quantity}}, message:`Add ${items[0].quantity} × ${items[0].name} to order ${order.number}` };
    return { ok:false, originalText, message:'I could not match a supported order action. No change was made.' };
  }
  return { interpret, normalize, extractItems };
});
