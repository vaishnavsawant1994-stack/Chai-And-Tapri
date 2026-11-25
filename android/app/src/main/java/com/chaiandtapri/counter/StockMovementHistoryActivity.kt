package com.chaiandtapri.counter

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isNotEmpty

/** CT-15 read-only movement drilldown over the local append-only inventory ledger. */
class StockMovementHistoryActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var search: EditText
    private lateinit var productFilter: Spinner
    private lateinit var reasonFilter: Spinner
    private lateinit var resultSummary: TextView
    private var products: List<TapriProduct> = emptyList()
    private var movements: List<StockMovement> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized && content.isNotEmpty()) render() }

    private fun render(message: String? = null) {
        model?.close()
        content.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            model = current
            products = current.products()
            movements = current.stockMovements()

            content.addView(label("Stock Movement History · CT-15", 24f))
            content.addView(label("Read-only local ledger. Entries are shown newest first; recorded event time is not available in this prototype.", 14f))
            if (message != null) content.addView(label(message, 14f))

            search = EditText(this).apply {
                hint = "Search product, reason, order or movement ID"
                contentDescription = "Search stock movements"
                setSingleLine(true)
            }
            content.addView(search)

            val productNames = products.associate { it.productId to it.name }
            productFilter = Spinner(this).apply {
                contentDescription = "Filter stock movements by product"
                adapter = ArrayAdapter(this@StockMovementHistoryActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("All products") + products.map { "${it.name} (${it.productId})" })
            }
            content.addView(label("Product", 14f)); content.addView(productFilter)

            val reasons = movements.map { it.reason }.distinct().sorted()
            reasonFilter = Spinner(this).apply {
                contentDescription = "Filter stock movements by type"
                adapter = ArrayAdapter(this@StockMovementHistoryActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("All movement types") + reasons)
            }
            content.addView(label("Movement type", 14f)); content.addView(reasonFilter)
            content.addView(button("Clear filters") {
                search.setText("")
                productFilter.setSelection(0)
                reasonFilter.setSelection(0)
            })

            resultSummary = label("", 14f)
            content.addView(resultSummary)
            rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(rows, matchWidth())
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateRows(productNames)
                override fun afterTextChanged(s: Editable?) = Unit
            })
            val filterChanged = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = updateRows(productNames)
            }
            productFilter.onItemSelectedListener = filterChanged
            reasonFilter.onItemSelectedListener = filterChanged
            content.addView(label("Stock counts, sales, receipts, returns and refunds are distinct ledger events. This screen does not edit balances.", 13f))
            content.addView(button("Back to inventory") { finish() })
            updateRows(productNames)
        } catch (error: Exception) {
            content.addView(label("Stock movement history could not be opened. No data was changed.", 18f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun updateRows(productNames: Map<String, String>) {
        if (!::rows.isInitialized || !::search.isInitialized || !::productFilter.isInitialized || !::reasonFilter.isInitialized) return
        val query = search.text.toString().trim().lowercase()
        val selectedProduct = productFilter.selectedItemPosition.takeIf { it > 0 }?.let { products.getOrNull(it - 1)?.productId }
        val selectedReason = reasonFilter.selectedItem?.toString()?.takeIf { reasonFilter.selectedItemPosition > 0 }
        val filtered = movements.filter { movement ->
            (selectedProduct == null || movement.productId == selectedProduct) &&
                (selectedReason == null || movement.reason == selectedReason) &&
                (query.isEmpty() || listOf(movement.productId, productNames[movement.productId].orEmpty(), movement.reason,
                    movement.orderNumber?.toString().orEmpty(), movement.movementId).any { it.lowercase().contains(query) })
        }
        val displayed = filtered.asReversed()
        rows.removeAllViews()
        resultSummary.text = getString(R.string.stock_movement_results, displayed.size, movements.size)
        if (displayed.isEmpty()) rows.addView(label("No stock movements match these filters.", 15f))
        displayed.forEach { movement ->
            val delta = if (movement.quantityDelta > 0) "+${movement.quantityDelta}" else movement.quantityDelta.toString()
            val order = movement.orderNumber?.let { "Order $it" }
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
            card.addView(label(productNames[movement.productId] ?: movement.productId, 17f))
            val unit = products.firstOrNull { it.productId == movement.productId }?.unitLabel ?: "units"
            card.addView(label("$delta $unit · ${movement.reason}", 14f))
            if (order != null) card.addView(label(order, 13f))
            card.addView(label("Movement ${movement.movementId}", 12f))
            rows.addView(card, matchWidth())
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
