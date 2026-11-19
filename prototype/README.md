# CT-06 Quick Sale — Design Prototype

This is a responsive, touch-first product prototype for the Chai And Tapri OS counter screen. It is intentionally buildless: open `index.html` in a modern browser or serve the `prototype` directory over HTTP. There are no package dependencies or remote image/font requirements. The deterministic command module is `transaction-engine.js`.

## Design system

- **Palette:** deep leaf green for the primary action and navigation (`#173e34`), warm paper/cream surfaces (`#fff`, `#f6f6f1`), restrained chai-orange accent (`#de7549`), quiet grey-green text, and pale green saved/selected states.
- **Type:** platform sans-serif stack with strong, compact headings and readable labels; rupee values use bold emphasis.
- **Shape and spacing:** 8–14px rounded surfaces, 4px spacing rhythm, clear separation between product discovery and order/payment work.
- **Touch/accessibility:** large product controls, keyboard-accessible buttons and fields, focus states, visible selected payment method, accessible labels, reduced-motion support, and a five-item mobile navigation bar.
- **Responsive layout:** desktop navigation rail and two-column workbench; compact widths switch to a single column with fixed bottom navigation and safe-area padding.
- **Language direction:** prototype copy is English with familiar shop words such as *Udhar* and *hisaab*. Language switching and localization are not implemented yet.

## Try it

1. Tap product cards to add them to the current order; use `+`, `−`, and `×` to adjust the draft.
2. Select **Cash**, **UPI pending**, or **Udhar**. Cash is explicitly staff-declared; UPI stays pending; Udhar requires choosing a demo account.
3. Press **Record sale**. It sends a `sale.commit` command through the shared transaction engine. The engine calculates paise totals from the catalogue, atomically saves the sale/payment/stock events and clears the draft in one local snapshot. It does not make a verified payment claim.
4. Open **Recorded today** to view the local register.
5. Tap the online/offline chip to preview the status change. Both modes save in browser local storage; this does not simulate a real sync service.
6. Choose **Use your voice** for a typed B1 command or press **Speak** for one browser-recognized utterance. Select Hindi/Hinglish, Marathi, or English (India); review the transcript, preview the parsed items, and add them to the draft. In B2, speech only fills the command field; inspect it before pressing **Run command**. The parser handles a limited set of Devanagari counter phrases and numerals. Browser support and language accuracy vary. This is not continuous recognition and does not access Bluetooth.
7. Search and category filters work. **Scan** accepts a product name/SKU using a demo prompt.
8. The order menu clears an unrecorded draft; when no draft is open, it offers a reset of local demo sales and stock.
9. In sales history, **Correct** records a reasoned void event. Cash voids require a staff-declared refund; stock is returned only when the operator explicitly chooses `restock`.
10. In the B1 preview, open **Continuous Bluetooth Counter** for the B2 queue preview, where typed commands or one-shot speech transcripts use the shared parser. Try “Naya order, do cutting chai, ek samosa”, then use the displayed order number to mark it ready, hand it over, and record cash or UPI pending.

Run the engine, parser and speech-adapter tests from the project root with `node --test prototype/speech-input.test.cjs prototype/transaction-engine.test.cjs prototype/voice-counter.test.cjs`.

## Prototype boundaries

There is no Android application, authentication, encrypted storage, backend, real offline synchronization, continuous microphone/Bluetooth integration, payment-provider connection, legally reviewed tax logic, or production business accounting here. Browser local storage is demo data and must not be used for a real shop. The engine demonstrates idempotent local commands, auditable sale voids, partial handover, partial cash collection, customer cash repayment, and typed multi-order state transitions; these commands are not all exposed in the UI. Supplier purchasing, stock counts, full returns/refunds, reports, and staff screens remain planned follow-on work.

The prototype deliberately uses demo product names and prices. Use the order menu while the cart is empty to reset the local demo data before a new walkthrough.
