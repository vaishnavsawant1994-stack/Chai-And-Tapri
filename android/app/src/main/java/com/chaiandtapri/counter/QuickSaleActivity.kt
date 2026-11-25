package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import java.util.Locale

/** CT-06 native first slice: catalogue-backed cart and single atomic tender commit. */
class QuickSaleActivity : Activity() {
    private lateinit var model: CounterModel
    private lateinit var products: LinearLayout
    private lateinit var search: EditText
    private lateinit var scanInput: EditText
    private lateinit var categoryFilter: Spinner
    private lateinit var emptyNotice: TextView
    private lateinit var cart: TextView
    private lateinit var cartRowsContainer: LinearLayout
    private lateinit var receipt: LinearLayout
    private lateinit var status: TextView
    private val quantities = linkedMapOf<String, Int>()
    private var selectedCategory = "All products"
    private var categoryOptions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireCounterAccess(this)) return
        model = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 24, 20, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(label("Quick Sale", 25f))
        root.addView(label("Prices use the confirmed shop catalogue. One checkout records the order, stock and selected payment state together.", 14f))
        scanInput = EditText(this).apply {
            hint = "Scan barcode / QR with paired keyboard scanner"
            contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    addScannedProduct()
                    true
                } else false
            }
        }
        root.addView(scanInput)
        root.addView(button("Add scanned product") { addScannedProduct() })
        root.addView(button("Scan product with camera") {
            startActivityForResult(android.content.Intent(this, CameraBarcodeScannerActivity::class.java), CAMERA_SCAN_REQUEST)
        })
        search = EditText(this).apply {
            hint = "Search product, category or alias"
            contentDescription = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search)
        categoryFilter = Spinner(this).apply {
            contentDescription = "Filter products by category or favourites"
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedCategory = categoryOptions.getOrNull(position) ?: "All products"
                    if (::products.isInitialized) render()
                }
            }
        }
        root.addView(categoryFilter)
        emptyNotice = label("", 15f); root.addView(emptyNotice)
        root.addView(button("Configure products and confirm prices") { startActivity(android.content.Intent(this, ProductCatalogueActivity::class.java)) })
        products = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(products)
        root.addView(button("Repeat last completed sale") { stageRepeatLastSale() })
        root.addView(label("Current order", 20f))
        cart = label("No items", 16f); root.addView(cart)
        cartRowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(cartRowsContainer)
        root.addView(button("Clear order") { quantities.clear(); render() })
        status = label("", 15f); root.addView(status)
        root.addView(button("Checkout · choose payment") { checkout() })
        receipt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(12, 12, 12, 12) }
        root.addView(receipt)
        root.addView(button("Back") { finish() })
        refreshCategoryOptions()
        render()
    }

    private fun render() {
        products.removeAllViews()
        cartRowsContainer.removeAllViews()
        val levels = model.stockLevels()
        val sellable = model.products().filter { it.active && it.priceConfirmed }
        emptyNotice.text = if (sellable.isEmpty()) "No products have an owner-confirmed price yet. Configure the catalogue before selling." else ""
        val query = if (::search.isInitialized) search.text.toString().trim().lowercase(Locale.ROOT) else ""
        val visible = sellable.filter { product ->
            val categoryMatches = when (selectedCategory) {
                "All products" -> true
                "Favourites" -> product.favorite
                else -> product.category.equals(selectedCategory, ignoreCase = true)
            }
            val searchMatches = query.isEmpty() || sequenceOf(product.name, product.productId, product.category)
                .plus(product.aliases.asSequence()).any { it.lowercase(Locale.ROOT).contains(query) }
            categoryMatches && searchMatches
        }
        if (sellable.isNotEmpty() && visible.isEmpty()) emptyNotice.text = "No products match this search and filter."
        visible.forEach { product ->
            val available = levels[product.productId] ?: 0
            val countConfirmed = model.isStockConfirmed(product.productId)
            products.addView(button("${product.name} · ₹${money(product.unitPricePaise)} · ${if (countConfirmed) "stock $available ${product.unitLabel}" else "opening count needed"} · add") {
                if (!countConfirmed) status.text = "Physically count and confirm opening stock for ${product.name} before selling."
                else if (available <= (quantities[product.productId] ?: 0)) status.text = "Not enough recorded stock for ${product.name}. Receive/count stock first."
                else { quantities[product.productId] = (quantities[product.productId] ?: 0) + 1; status.text = ""; render() }
            })
        }
        val cartRows = quantities.mapNotNull { (id, qty) -> model.products().firstOrNull { it.productId == id }?.let { it to qty } }
        val total = cartRows.sumOf { (product, qty) -> product.unitPricePaise.toLong() * qty }
        cart.text = if (cartRows.isEmpty()) "No items" else cartRows.joinToString("\n") { (product, qty) -> "$qty ${product.unitLabel} × ${product.name} · ₹${money(product.unitPricePaise.toLong() * qty)}" } + "\nTotal · ₹${money(total)}"
        cartRows.forEach { (product, qty) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label("${product.name} · quantity $qty ${product.unitLabel}", 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("−") {
                val newQuantity = (quantities[product.productId] ?: 0) - 1
                if (newQuantity <= 0) quantities.remove(product.productId) else quantities[product.productId] = newQuantity
                status.text = ""
                render()
            }.apply { contentDescription = "Decrease quantity for ${product.name}" })
            row.addView(button("+") {
                val currentQuantity = quantities[product.productId] ?: 0
                val available = levels[product.productId] ?: 0
                val stockConfirmed = model.isStockConfirmed(product.productId)
                status.text = when {
                    !stockConfirmed -> "Physically count and confirm opening stock for ${product.name} before selling."
                    currentQuantity >= 99 -> "Maximum cart quantity is 99. No order changed."
                    currentQuantity >= available -> "Not enough recorded stock for ${product.name}. Receive/count stock first."
                    else -> ""
                }
                if (stockConfirmed && currentQuantity < 99 && currentQuantity < available) {
                    quantities[product.productId] = currentQuantity + 1
                    render()
                }
            }.apply { contentDescription = "Increase quantity for ${product.name}" })
            cartRowsContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun checkout() {
        if (quantities.isEmpty()) { status.text = "Add a product before checkout."; return }
        AlertDialog.Builder(this).setTitle("Record payment")
            .setItems(arrayOf("Cash received", "UPI pending · not verified", "Udhar · customer account", "Split cash + Udhar")) { _, which ->
                when (which) {
                    0 -> commit("cash")
                    1 -> commit("upi_pending")
                    2 -> askCustomer()
                    3 -> askSplitCashAmount()
                }
            }.show()
    }

    private fun askSplitCashAmount() {
        val cashAmount = EditText(this).apply {
            hint = "Cash amount in rupees"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Split cash + Udhar")
            .setMessage("Cash is staff-declared. The remaining balance becomes Udhar for a saved customer.")
            .setView(cashAmount).setNegativeButton("Cancel", null).setPositiveButton("Choose customer") { _, _ ->
                val cashPaise = runCatching { cashAmount.text.toString().trim().toBigDecimal().movePointRight(2).intValueExact().takeIf { it > 0 } }.getOrNull()
                val totalPaise = quantities.entries.sumOf { (id, quantity) ->
                    (model.products().firstOrNull { it.productId == id }?.unitPricePaise ?: 0).toLong() * quantity
                }
                if (cashPaise == null || cashPaise >= totalPaise) {
                    status.text = "Enter positive cash less than the sale total. No sale was recorded."
                    return@setPositiveButton
                }
                CustomerAccountPicker.show(
                    activity = this,
                    model = model,
                    title = "Select customer for remaining Udhar",
                    onSelect = { customerId -> commitSplit(cashPaise, customerId) },
                    onAddCustomer = { startActivity(android.content.Intent(this, CustomerCreditActivity::class.java)) }
                )
            }.show()
    }

    private fun askCustomer() {
        CustomerAccountPicker.show(
            activity = this,
            model = model,
            title = "Select customer for Udhar",
            onSelect = { customerId -> commit("udhar", customerId) },
            onAddCustomer = { startActivity(android.content.Intent(this, CustomerCreditActivity::class.java)) }
        )
    }

    private fun commit(tender: String, customerId: String? = null) {
        val result = model.quickSale(quantities.map { it.key to it.value }, tender, customerId)
        status.text = result
        if (result.startsWith("Quick sale")) {
            quantities.clear()
            render()
            showRecordedReceipt(committedOrder(result))
        }
    }

    private fun commitSplit(cashPaise: Int, customerId: String) {
        val result = model.quickSaleSplit(quantities.map { it.key to it.value }, cashPaise, customerId)
        status.text = result
        if (result.startsWith("Quick sale")) {
            quantities.clear()
            render()
            showRecordedReceipt(committedOrder(result))
        }
    }

    private fun committedOrder(result: String): CounterOrder? {
        val number = Regex("order #(\\d+)").find(result)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return model.allOrders().firstOrNull { it.number == number && it.salePosted }
    }

    private fun showRecordedReceipt(order: CounterOrder?) {
        receipt.removeAllViews()
        if (order == null) {
            receipt.addView(label("Sale recorded. Open Sales History to review its order details.", 14f))
            receipt.visibility = View.VISIBLE
            return
        }
        receipt.addView(label("Last sale · Order #${order.number}", 19f))
        val units = model.products().associate { it.productId to it.unitLabel }
        receipt.addView(label(order.lines.joinToString("\n") { "${it.quantity} ${units[it.productId] ?: "units"} × ${it.name} · ₹${money(it.amountPaise)}" }, 15f))
        receipt.addView(label("Sale total · ₹${money(order.salePaise)}", 17f))
        val payments = model.paymentHistory().filter { it.orderNumber == order.number }
        payments.forEach { entry ->
            val evidence = when {
                entry.method == "cash" && entry.status == "staff_declared" -> "Cash recorded · staff-declared"
                entry.method == "upi" && entry.status == "pending" -> "UPI pending · not verified"
                entry.method == "upi" && entry.status == "staff_declared" -> "UPI receipt · staff-declared, not provider-verified"
                entry.method == "udhar" && entry.status == "receivable" -> "Udhar receivable · not collected"
                else -> "${entry.method.uppercase(Locale.ROOT)} · ${entry.status}"
            }
            receipt.addView(label("$evidence · ₹${money(entry.amountPaise)}", 14f))
        }
        if (order.duePaise > 0) receipt.addView(label("Outstanding · ₹${money(order.duePaise)}", 15f))
        receipt.addView(button("Open order #${order.number}") {
            startActivity(android.content.Intent(this, OrderDetailsActivity::class.java)
                .putExtra(OrderDetailsActivity.EXTRA_ORDER_NUMBER, order.number))
        })
        receipt.visibility = View.VISIBLE
    }

    /** Copies a prior completed sale into the current draft only; checkout remains a separate explicit action. */
    @Suppress("SetTextI18n")
    private fun stageRepeatLastSale() {
        val previous = model.allOrders().firstOrNull {
            it.salePosted && !it.voided && it.fulfillment == Fulfillment.GIVEN &&
                it.lines.any { line -> line.deliveredQuantity > line.refundedQuantity }
        }
        if (previous == null) {
            status.text = "No completed, non-voided sale is available to repeat. No order changed."
            return
        }
        val repeatLines = previous.lines.mapNotNull { line ->
            (line.deliveredQuantity - line.refundedQuantity).takeIf { it > 0 }?.let { line.productId to it }
        }
        val issue = repeatDraftIssue(repeatLines)
        if (issue != null) {
            status.text = issue
            return
        }
        val description = repeatLines.joinToString(" + ") { (productId, quantity) ->
            val product = model.products().first { it.productId == productId }
            "$quantity × ${product.name}"
        }
        AlertDialog.Builder(this)
            .setTitle("Repeat completed sale #${previous.number}?")
            .setMessage("Stage $description as a draft at today's confirmed catalogue prices. Nothing is sold or charged until you review and check out.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add to current order") { _, _ ->
                val recheck = repeatDraftIssue(repeatLines)
                if (recheck != null) {
                    status.text = recheck
                    return@setPositiveButton
                }
                repeatLines.forEach { (productId, quantity) -> quantities[productId] = (quantities[productId] ?: 0) + quantity }
                status.text = "Previous sale staged as a draft. Review items and payment before checkout."
                render()
            }
            .show()
    }

    @Suppress("SetTextI18n")
    private fun repeatDraftIssue(lines: List<Pair<String, Int>>): String? {
        if (lines.isEmpty()) return "The previous sale has no remaining delivered items to repeat. No order changed."
        val productsById = model.products().associateBy { it.productId }
        for ((productId, quantity) in lines) {
            val product = productsById[productId] ?: return "A product from the previous sale is unavailable. No order changed."
            if (!product.active || !product.priceConfirmed || product.unitPricePaise <= 0) return "${product.name} is inactive or has no confirmed price. Review the catalogue first. No order changed."
            if (!model.isStockConfirmed(productId)) return "Physically count and confirm stock for ${product.name} before repeating this sale. No order changed."
            val available = model.stockLevels()[productId] ?: 0
            val existing = quantities[productId] ?: 0
            if (quantity > 99 - existing) return "Maximum cart quantity is 99 for ${product.name}. No order changed."
            if (existing + quantity > available) return "Not enough recorded stock for ${product.name}. No order changed."
        }
        return null
    }

    private fun addScannedProduct() {
        val code = scanInput.text.toString().trim()
        val product = ProductScanResolver.resolve(model.products(), code)
        status.text = when {
            code.isEmpty() -> "Scan or enter a product code first. No order changed."
            product == null -> "No unique active product matches this code. No order changed."
            !product.priceConfirmed || product.unitPricePaise <= 0 -> "${product.name} has no confirmed selling price. No order changed."
            !model.isStockConfirmed(product.productId) -> "${product.name} needs a confirmed physical stock count. No order changed."
            (model.stockLevels()[product.productId] ?: 0) <= (quantities[product.productId] ?: 0) -> "Not enough recorded stock for ${product.name}. No order changed."
            (quantities[product.productId] ?: 0) >= 99 -> "Maximum cart quantity is 99. No order changed."
            else -> {
                quantities[product.productId] = (quantities[product.productId] ?: 0) + 1
                "Scanned ${product.name}; added one item to the current order."
            }
        }
        scanInput.text.clear()
        render()
    }

    @Deprecated("Activity result API is retained here because QuickSaleActivity extends platform Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAMERA_SCAN_REQUEST) return
        handleCameraScanResult(resultCode, data)
    }

    internal fun handleCameraScanResult(resultCode: Int, data: android.content.Intent?) {
        if (resultCode == RESULT_OK) {
            val code = data?.getStringExtra(CameraBarcodeScannerActivity.EXTRA_PRODUCT_CODE).orEmpty()
            scanInput.setText(code)
            addScannedProduct()
        } else {
            status.text = data?.getStringExtra(CameraBarcodeScannerActivity.EXTRA_RESULT_MESSAGE)
                ?: "Camera scan was cancelled. Type a code or use a paired scanner instead. No order changed."
        }
    }

    private fun money(paise: Int) = money(paise.toLong())
    private fun money(paise: Long) = String.format(Locale.ROOT, "%.2f", paise / 100.0)
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 10, 0, 10) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized && ::products.isInitialized) { refreshCategoryOptions(); render() }
    }

    private fun refreshCategoryOptions() {
        if (!::categoryFilter.isInitialized || !::model.isInitialized) return
        val products = model.products().filter { it.active && it.priceConfirmed }
        val nextOptions = listOf("All products", "Favourites") + products.map { it.category }.filter(String::isNotBlank).distinct().sortedBy { it.lowercase(Locale.ROOT) }
        if (nextOptions == categoryOptions) return
        categoryOptions = nextOptions
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categoryOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        categoryFilter.adapter = adapter
        val position = categoryOptions.indexOf(selectedCategory).takeIf { it >= 0 } ?: 0
        categoryFilter.setSelection(position, false)
        selectedCategory = categoryOptions[position]
    }

    override fun onDestroy() { if (::model.isInitialized) model.close(); super.onDestroy() }

    companion object { internal const val CAMERA_SCAN_REQUEST = 6406 }
}
