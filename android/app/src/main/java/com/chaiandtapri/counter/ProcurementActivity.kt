package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import java.util.Locale

/** CT-17/18/19/20 first slice: supplier records, direct receipt, stock and payable updates. */
class ProcurementActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (content.childCount > 0) render() }

    private fun render(message: String? = null) {
        model?.close()
        content.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            model = current
            content.addView(label("Suppliers & Stock Receiving · CT-17/20", 24f))
            content.addView(label("Direct receipts update stock and supplier payable atomically. Physical stock baselines must be confirmed first. Cash and UPI settlements are staff-declared; no payment is externally verified.", 14f))
            status = label("", 14f); content.addView(status)
            message?.let { status.text = it }
            content.addView(button("Add or update supplier") { askSupplier() })
            content.addView(button("Create purchase order draft") { askPurchaseOrderDraft() })
            content.addView(button("Record direct stock receipt") { askReceipt() })
            content.addView(button("Record supplier payment") { askPayment() })
            content.addView(label("Supplier balances", 19f))
            val suppliers = current.suppliers()
            if (suppliers.isEmpty()) content.addView(label("No suppliers recorded.", 15f))
            val supplierSearch = EditText(this).apply {
                hint = "Search suppliers by name or ID"
                contentDescription = "Search suppliers by name or ID"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            content.addView(supplierSearch, matchWidth())
            val supplierCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val supplierCardEntries = mutableListOf<Pair<String, ViewGroup>>()
            suppliers.forEach { supplier ->
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
                card.addView(label(supplier.name, 17f))
                card.addView(label("Account ${supplier.supplierId} · payable ${money(current.supplierPayablePaise(supplier.supplierId))}", 14f))
                card.addView(button("View supplier details") {
                    startActivity(android.content.Intent(this, SupplierDetailsActivity::class.java).putExtra(SupplierDetailsActivity.EXTRA_SUPPLIER_ID, supplier.supplierId))
                })
                supplierCards.addView(card, matchWidth())
                supplierCardEntries += ("${supplier.name} ${supplier.supplierId}".lowercase(Locale.ROOT) to card)
            }
            val noSupplierMatches = label("No matching suppliers.", 14f).apply { visibility = android.view.View.GONE }
            supplierCards.addView(noSupplierMatches)
            supplierSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    var visible = 0
                    supplierCardEntries.forEach { (text, card) ->
                        card.visibility = if (query.isEmpty() || text.contains(query)) android.view.View.VISIBLE else android.view.View.GONE
                        if (card.visibility == android.view.View.VISIBLE) visible++
                    }
                    noSupplierMatches.visibility = if (visible == 0) android.view.View.VISIBLE else android.view.View.GONE
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            content.addView(supplierCards, matchWidth())
            content.addView(label("Purchase orders", 19f))
            val purchaseOrders = current.purchaseOrderDrafts()
            if (purchaseOrders.isEmpty()) content.addView(label("No purchase orders recorded.", 14f))
            purchaseOrders.forEach { purchaseOrder ->
                val supplier = suppliers.firstOrNull { it.supplierId == purchaseOrder.supplierId }?.name ?: purchaseOrder.supplierId
                val received = current.purchaseOrderReceivedQuantities(purchaseOrder.purchaseOrderId)
                val catalogue = current.products().associateBy { it.productId }
                val lines = purchaseOrder.lines.joinToString { line ->
                    val count = received[line.productId] ?: 0
                    val product = catalogue[line.productId]
                    "${line.productName}: ${quantitySummary(line.orderedQuantity, product)} ordered; ${quantitySummary(count, product)} received @ ${money(line.unitCostPaise.toLong())}/${product?.unitLabel ?: "unit"}"
                }
                content.addView(label("$supplier · ${purchaseOrder.purchaseOrderId} · ${current.purchaseOrderState(purchaseOrder)} · estimated ${money(purchaseOrder.totalPaise)}\n$lines", 14f))
                if (purchaseOrder.status == PurchaseOrderStatus.DRAFT) {
                    content.addView(button("Mark ${purchaseOrder.purchaseOrderId} sent") { markPurchaseOrderSent(purchaseOrder) })
                } else if (purchaseOrder.status == PurchaseOrderStatus.SENT && current.purchaseOrderState(purchaseOrder) != "RECEIVED") {
                    content.addView(button("Receive delivery for ${purchaseOrder.purchaseOrderId}") { askReceivePurchaseOrder(purchaseOrder) })
                }
                if (purchaseOrder.status != PurchaseOrderStatus.CANCELLED && current.purchaseOrderReceivedQuantities(purchaseOrder.purchaseOrderId).values.sum() == 0) {
                    content.addView(button("Cancel ${purchaseOrder.purchaseOrderId}") { askCancelPurchaseOrder(purchaseOrder) })
                }
            }
            content.addView(label("Purchase receipts", 19f))
            val receipts = current.purchaseHistory().asReversed()
            if (receipts.isEmpty()) content.addView(label("No received purchases recorded.", 14f))
            receipts.forEach { receipt ->
                val supplierName = suppliers.firstOrNull { it.supplierId == receipt.supplierId }?.name ?: receipt.supplierId
                val lineSummary = receipt.lines.joinToString { "${it.quantity} × ${it.productName} @ ${money(it.unitCostPaise.toLong())}" }
                val source = receipt.purchaseOrderId?.let { " · order $it" }.orEmpty()
                val invoice = receipt.supplierInvoiceReference?.let { " · supplier invoice $it" }.orEmpty()
                content.addView(label("$supplierName · $lineSummary · payable ${money(receipt.totalPaise)}$source$invoice", 14f))
            }
            receipts.forEach { receipt ->
                content.addView(button("Return unused stock from receipt ${receipt.receiptId}") { askReturn(receipt) })
            }
            content.addView(label("Purchase returns", 19f))
            val returns = current.purchaseReturnHistory().asReversed()
            if (returns.isEmpty()) content.addView(label("No purchase returns recorded.", 14f))
            returns.forEach { purchaseReturn ->
                val supplierName = suppliers.firstOrNull { it.supplierId == purchaseReturn.supplierId }?.name ?: purchaseReturn.supplierId
                val returnedLines = purchaseReturn.lines.joinToString { "${it.quantity} x ${it.productName}" }
                content.addView(label("$supplierName | receipt ${purchaseReturn.receiptId} | $returnedLines | payable reduction ${money(purchaseReturn.totalPaise)}", 14f))
            }
            content.addView(label("Recent supplier payments", 19f))
            current.supplierPaymentHistory().asReversed().take(30).forEach { payment ->
                val supplierName = suppliers.firstOrNull { it.supplierId == payment.supplierId }?.name ?: payment.supplierId
                content.addView(label("$supplierName · ${money(payment.amountPaise.toLong())} · ${payment.method} · ${payment.status}", 14f))
            }
            content.addView(label("Order drafts do not affect stock or payables. Mark sent only after the order is actually sent outside this app. Partial receipts are recorded against sent orders; returns use aggregate stock and are not lot-tracked.", 13f))
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Supplier and purchase records could not be opened. No replacement data was created.", 18f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun askSupplier() {
        val form = fields("Stable ID (e.g. local_dealer)", "Supplier / dealer name", "Phone (optional)")
        AlertDialog.Builder(this).setTitle("Add supplier")
            .setView(form.root).setNegativeButton("Cancel", null).setPositiveButton("Save supplier") { _, _ ->
                val result = model?.saveSupplier(form.values[0].text.toString(), form.values[1].text.toString(), phone = form.values[2].text.toString()) ?: "Storage is unavailable."
                render(result)
            }.show()
    }

    private fun askPurchaseOrderDraft() {
        val current = model ?: run { status.text = "Storage is unavailable."; return }
        val suppliers = current.suppliers()
        val products = current.products().filter { it.active }
        if (suppliers.isEmpty() || products.isEmpty()) {
            status.text = "Add a supplier and active catalogue products before creating an order draft."
            return
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        val supplierPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@ProcurementActivity, android.R.layout.simple_spinner_dropdown_item, suppliers.map { "${it.name} (${it.supplierId})" })
        }
        root.addView(label("Supplier", 15f)); root.addView(supplierPicker)
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(lines)
        val rows = mutableListOf<ReceiptRow>()
        fun addLine() {
            if (rows.size >= 10) { status.text = "An order draft can contain up to 10 lines in this screen."; return }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
            row.addView(label("Order line ${rows.size + 1}", 14f))
            val productPicker = Spinner(this).apply {
                adapter = ArrayAdapter(this@ProcurementActivity, android.R.layout.simple_spinner_dropdown_item, products.map { "${it.name} (${it.productId})" })
            }
            val conversion = label("", 13f)
            val quantity = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
            val unitCost = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true) }
            fun updateUnits(position: Int) {
                val product = products.getOrNull(position) ?: return
                quantity.hint = "Ordered quantity in ${product.purchaseUnitLabel}"
                unitCost.hint = "Estimated cost per ${product.unitLabel} in rupees"
                conversion.text = "1 ${product.purchaseUnitLabel} = ${product.baseUnitsPerPurchaseUnit} ${product.unitLabel}; draft quantities are stored in ${product.unitLabel}."
            }
            productPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = updateUnits(position)
            }
            row.addView(productPicker); row.addView(conversion); row.addView(quantity); row.addView(unitCost)
            lines.addView(row); rows += ReceiptRow(productPicker, quantity, unitCost)
        }
        addLine()
        root.addView(button("Add product line") { addLine() })
        val dialog = AlertDialog.Builder(this).setTitle("Draft purchase order")
            .setMessage("Enter requested quantities in each product's purchase unit and estimated cost per base unit. Draft quantities convert to the base unit. Estimated costs are planning values; saving changes no stock or supplier payable.")
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save draft", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val quantities = linkedMapOf<String, Int>(); val costs = linkedMapOf<String, Int>(); var invalid = false
                rows.forEach { row ->
                    val quantityText = row.quantity.text.toString().trim(); val costText = row.unitCost.text.toString().trim()
                    if (quantityText.isEmpty() && costText.isEmpty()) return@forEach
                    val productId = products[row.product.selectedItemPosition].productId
                    val quantity = quantityText.toIntOrNull(); val cost = parsePaise(costText)
                    if (quantity == null || cost == null || productId in quantities) invalid = true
                    else { quantities[productId] = quantity; costs[productId] = cost }
                }
                if (invalid || quantities.isEmpty()) { status.text = "Complete each used line with a distinct product, quantity and positive estimated cost. No draft saved."; return@setOnClickListener }
                val result = model?.createPurchaseOrderDraftInPurchaseUnits(suppliers[supplierPicker.selectedItemPosition].supplierId, quantities, costs) ?: "Storage is unavailable."
                if (result.startsWith("Purchase order draft")) { dialog.dismiss(); render(result) }
                else status.text = result
            }
        }
        dialog.show()
    }

    private fun markPurchaseOrderSent(order: PurchaseOrderDraft) {
        AlertDialog.Builder(this).setTitle("Mark order sent?")
            .setMessage("This records your confirmation only. The app will not send an order to the supplier.")
            .setNegativeButton("Cancel", null).setPositiveButton("Mark sent") { _, _ ->
                val result = model?.markPurchaseOrderSent(order.purchaseOrderId, order.revision) ?: "Storage is unavailable."
                render(result)
            }.show()
    }

    private fun askReceivePurchaseOrder(order: PurchaseOrderDraft) {
        val current = model ?: run { status.text = "Storage is unavailable."; return }
        val received = current.purchaseOrderReceivedQuantities(order.purchaseOrderId)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        val invoiceReference = EditText(this).apply { hint = "Supplier invoice reference (optional)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true) }
        root.addView(invoiceReference)
        val fields = order.lines.mapNotNull { line ->
            val remaining = line.orderedQuantity - (received[line.productId] ?: 0)
            if (remaining <= 0) null else {
                val product = current.products().firstOrNull { it.productId == line.productId }
                val quantity = EditText(this).apply {
                    hint = "${line.productName}: received quantity in ${product?.unitLabel ?: "base units"} (remaining $remaining ${product?.unitLabel ?: "units"})"
                    inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true)
                }
                root.addView(quantity)
                line.productId to quantity
            }
        }
        val dialog = AlertDialog.Builder(this).setTitle("Receive purchase order")
            .setMessage("Enter only quantities physically received now. Blank lines are left outstanding. Stock and supplier payable update only after this receipt is posted.")
            .setView(ScrollView(this).apply { addView(root) }).setNegativeButton("Cancel", null).setPositiveButton("Post received stock", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val quantities = linkedMapOf<String, Int>(); var invalid = false
                fields.forEach { (productId, field) ->
                    val raw = field.text.toString().trim()
                    if (raw.isEmpty()) return@forEach
                    val quantity = raw.toIntOrNull()
                    if (quantity == null || quantity <= 0) invalid = true else quantities[productId] = quantity
                }
                if (invalid || quantities.isEmpty()) { status.text = "Enter at least one positive received quantity. No receipt posted."; return@setOnClickListener }
                val result = model?.receivePurchaseOrder(order.purchaseOrderId, quantities, supplierInvoiceReference = invoiceReference.text.toString()) ?: "Storage is unavailable."
                if (result.startsWith("Received ")) { dialog.dismiss(); render(result) } else status.text = result
            }
        }
        dialog.show()
    }

    private fun askCancelPurchaseOrder(order: PurchaseOrderDraft) {
        val reason = EditText(this).apply { hint = "Cancellation reason"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Cancel purchase order")
            .setMessage("Only an order with no recorded deliveries can be cancelled here.")
            .setView(reason).setNegativeButton("Keep order", null).setPositiveButton("Cancel order") { _, _ ->
                val result = model?.cancelPurchaseOrder(order.purchaseOrderId, order.revision, reason.text.toString()) ?: "Storage is unavailable."
                render(result)
            }.show()
    }

    private fun askReceipt() {
        val current = model ?: run { status.text = "Storage is unavailable."; return }
        val suppliers = current.suppliers()
        val products = current.products().filter { it.active }
        if (suppliers.isEmpty() || products.isEmpty()) {
            status.text = "Add a supplier and active catalogue products before receiving stock."
            return
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        val supplierPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@ProcurementActivity, android.R.layout.simple_spinner_dropdown_item, suppliers.map { "${it.name} (${it.supplierId})" })
        }
        root.addView(label("Supplier", 15f)); root.addView(supplierPicker)
        val invoiceReference = EditText(this).apply { hint = "Supplier invoice reference (optional)"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true) }
        root.addView(invoiceReference)
        val lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(lines)
        val rows = mutableListOf<ReceiptRow>()
        fun addLine() {
            if (rows.size >= 10) { status.text = "A receipt can contain up to 10 lines in this screen."; return }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
            row.addView(label("Product ${rows.size + 1}", 14f))
            val productPicker = Spinner(this).apply {
                adapter = ArrayAdapter(this@ProcurementActivity, android.R.layout.simple_spinner_dropdown_item, products.map { "${it.name} (${it.productId})" })
            }
            val unitInfo = label("", 13f)
            val quantity = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
            val unitCost = EditText(this).apply { hint = "Equivalent cost per ${products.first().unitLabel} in ₹"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(true) }
            fun showConversion(index: Int) {
                val product = products[index.coerceIn(products.indices)]
                quantity.hint = "Received quantity in ${product.purchaseUnitLabel}"
                unitCost.hint = "Cost per ${product.unitLabel} in ₹ (integer paise)"
                unitInfo.text = "1 ${product.purchaseUnitLabel} = ${product.baseUnitsPerPurchaseUnit} ${product.unitLabel}. Enter cost per sell/count unit; stock is recorded in ${product.unitLabel}."
            }
            productPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = showConversion(position)
            }
            showConversion(0)
            row.addView(productPicker); row.addView(unitInfo); row.addView(quantity); row.addView(unitCost)
            lines.addView(row)
            rows += ReceiptRow(productPicker, quantity, unitCost)
        }
        addLine()
        val dialog = AlertDialog.Builder(this).setTitle("Receive purchase")
            .setMessage("Record quantities actually received. Each product needs a confirmed physical stock baseline. Blank extra lines are ignored.")
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton("Cancel", null).setPositiveButton("Post receipt", null).create()
        root.addView(button("Add product line") { addLine() })
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val quantities = linkedMapOf<String, Int>()
                val costs = linkedMapOf<String, Int>()
                var invalid = false
                rows.forEach { row ->
                    val quantityText = row.quantity.text.toString().trim()
                    val costText = row.unitCost.text.toString().trim()
                    if (quantityText.isEmpty() && costText.isEmpty()) return@forEach
                    val productId = products[row.product.selectedItemPosition].productId
                    val quantity = quantityText.toIntOrNull()
                    val cost = parsePaise(costText)
                    if (quantity == null || cost == null || productId in quantities) invalid = true
                    else { quantities[productId] = quantity; costs[productId] = cost }
                }
                if (invalid || quantities.isEmpty()) {
                    status.text = "Complete each used line, avoid duplicate products, and enter a positive exact cost. No receipt recorded."
                    return@setOnClickListener
                }
                val supplierId = suppliers[supplierPicker.selectedItemPosition].supplierId
                val result = model?.receivePurchaseInPurchaseUnits(supplierId, quantities, costs, supplierInvoiceReference = invoiceReference.text.toString()) ?: "Storage is unavailable."
                if (result.startsWith("Received ")) { dialog.dismiss(); render(result) } else status.text = result
            }
        }
        dialog.show()
    }

    private data class ReceiptRow(val product: Spinner, val quantity: EditText, val unitCost: EditText)

    private fun askPayment() {
        val form = fields("Existing supplier ID", "Cash amount in rupees")
        val method = Spinner(this).apply { adapter = ArrayAdapter(this@ProcurementActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Cash · staff declared", "UPI · staff declared")) }
        form.root.addView(label("Payment method", 14f)); form.root.addView(method)
        AlertDialog.Builder(this).setTitle("Record supplier payment")
            .setMessage("This settles a supplier payable; it is not a new purchase or expense. UPI is recorded as staff-declared and is not provider-verified.")
            .setView(form.root).setNegativeButton("Cancel", null).setPositiveButton("Record payment") { _, _ ->
                val amount = parsePaise(form.values[1].text.toString())
                val result = if (amount == null) "Enter a positive exact-paise amount. No payment recorded."
                else model?.recordSupplierPayment(form.values[0].text.toString().trim(), amount, if (method.selectedItemPosition == 0) "cash" else "upi") ?: "Storage is unavailable."
                render(result)
            }.show()
    }

    private fun askReturn(receipt: PurchaseReceipt) {
        val current = model ?: run { status.text = "Storage is unavailable."; return }
        val priorReturns = current.purchaseReturnHistory().filter { it.receiptId == receipt.receiptId }
            .flatMap { it.lines }.groupBy { it.productId }.mapValues { (_, rows) -> rows.sumOf { it.quantity } }
        val stock = current.stockLevels()
        val returnableLines = receipt.lines.mapNotNull { line ->
            val unreturned = (line.quantity - (priorReturns[line.productId] ?: 0)).coerceAtLeast(0)
            val available = (stock[line.productId] ?: 0).coerceAtLeast(0)
            val maximum = minOf(unreturned, available)
            if (maximum <= 0) null else line to maximum
        }
        if (returnableLines.isEmpty()) {
            status.text = "This receipt has no unreturned units in available stock. No return recorded."
            return
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        val selector = Spinner(this).apply {
            adapter = ArrayAdapter(this@ProcurementActivity, android.R.layout.simple_spinner_dropdown_item,
                returnableLines.map { (line, maximum) -> "${line.productName} · ${line.productId} (up to $maximum available)" })
        }
        root.addView(label("Product from this receipt", 14f)); root.addView(selector)
        val quantity = EditText(this).apply { hint = "Return quantity"; inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
        val reason = EditText(this).apply { hint = "Reason for return"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true) }
        root.addView(quantity); root.addView(reason)
        AlertDialog.Builder(this).setTitle("Return unused stock")
            .setMessage("Choose a product from this receipt. The limit reflects unreturned receipt quantity and current available stock. The return reduces unpaid supplier payable; final validation checks the balance.")
            .setView(root).setNegativeButton("Cancel", null).setPositiveButton("Review return") { _, _ ->
                val selected = returnableLines.getOrNull(selector.selectedItemPosition)
                val enteredQuantity = quantity.text.toString().trim().toIntOrNull()
                val note = reason.text.toString().trim()
                val result = when {
                    selected == null || enteredQuantity == null || enteredQuantity !in 1..selected.second -> "Enter a return quantity within the available limit. No return recorded."
                    note.length !in 3..120 -> "Enter a reason between 3 and 120 characters. No return recorded."
                    else -> ""
                }
                if (result.isNotEmpty()) { render(result); return@setPositiveButton }
                val (line, _) = selected!!
                val confirmed = AlertDialog.Builder(this).setTitle("Confirm supplier return")
                    .setMessage("Return $enteredQuantity × ${line.productName} to ${receipt.supplierId} for ${money(line.unitCostPaise.toLong() * enteredQuantity!!)}. This reduces stock and supplier payable only after the business engine accepts it. Continue?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Confirm return") { _, _ ->
                val saved = model?.returnPurchase(receipt.supplierId, receipt.receiptId, mapOf(line.productId to enteredQuantity), note) ?: "Storage is unavailable."
                render(saved)
                    }.create()
                confirmed.show()
            }.show()
    }

    private data class Form(val root: LinearLayout, val values: List<EditText>)
    private fun fields(vararg hints: String): Form {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        val values = hints.map { hint -> EditText(this).apply {
            this.hint = hint; setSingleLine(true)
            if (hint.contains("quantity", true)) inputType = InputType.TYPE_CLASS_NUMBER
            else if (hint.contains("rupees", true)) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else inputType = InputType.TYPE_CLASS_TEXT
            root.addView(this)
        } }
        return Form(root, values)
    }

    private fun parsePaise(raw: String): Int? = runCatching {
        raw.trim().toBigDecimal().movePointRight(2).intValueExact().takeIf { it > 0 }
    }.getOrNull()
    private fun money(paise: Long) = String.format(Locale.ROOT, "₹%d.%02d", paise / 100, paise % 100)
    private fun quantitySummary(baseQuantity: Int, product: TapriProduct?): String {
        if (product == null || product.baseUnitsPerPurchaseUnit <= 1 || baseQuantity % product.baseUnitsPerPurchaseUnit != 0) {
            return "$baseQuantity ${product?.unitLabel ?: "units"}"
        }
        return "${baseQuantity / product.baseUnitsPerPurchaseUnit} ${product.purchaseUnitLabel} ($baseQuantity ${product.unitLabel})"
    }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
