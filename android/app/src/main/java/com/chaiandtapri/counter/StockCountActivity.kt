package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/** CT-16 multi-product physical count; one validated command updates all selected balances. */
class StockCountActivity : Activity() {
    private data class CountRow(val product: TapriProduct, val selected: CheckBox, val quantity: EditText)
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout
    private lateinit var reason: EditText
    private lateinit var status: TextView
    private val rows = mutableListOf<CountRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized && content.childCount > 0) render() }

    private fun render(message: String? = null) {
        model?.close()
        content.removeAllViews()
        rows.clear()
        try {
            val current = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            model = current
            val stock = current.stockLevels()
            val products = current.products().filter { it.active }
            content.addView(label("Physical Stock Count · CT-16", 24f))
            content.addView(label("Select only products physically counted. Submitting updates all selected products in one local transaction. It does not create purchases, sales, or supplier payables.", 14f))
            message?.let { content.addView(label(it, 15f)) }
            status = label("", 14f)
            content.addView(status)
            if (products.isEmpty()) content.addView(label("No active catalogue products are available to count.", 16f))
            products.forEach { product ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 12, 8, 12) }
                val selected = CheckBox(this).apply {
                    text = product.name
                    contentDescription = "Count ${product.name}"
                }
                val quantity = EditText(this).apply {
                    hint = "${product.name} physical quantity"
                    contentDescription = "${product.name} physical quantity"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    isEnabled = false
                }
                selected.setOnCheckedChangeListener { _, checked -> quantity.isEnabled = checked }
                val confirmed = current.isStockConfirmed(product.productId)
            row.addView(label(if (confirmed) "Last recorded balance: ${stock[product.productId] ?: 0} ${product.unitLabel} · verify by counting" else "No verified baseline · enter an actual physical count in ${product.unitLabel}", 13f))
                row.addView(selected)
                row.addView(quantity)
                content.addView(row, matchWidth())
                rows += CountRow(product, selected, quantity)
            }
            reason = EditText(this).apply { hint = "Count reason (e.g. evening shelf count)"; setSingleLine(true) }
            content.addView(reason)
            content.addView(button("Save selected counts atomically") { save(current) })
            content.addView(button("Review stock count history") { startActivity(Intent(this, StockCountHistoryActivity::class.java)) })
            content.addView(button("Back") { finish() })
        } catch (failure: Exception) {
            content.addView(label("Stock count could not be opened. No count was created. (${failure.javaClass.simpleName})", 16f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun save(current: CounterModel) {
        val selectedRows = rows.filter { it.selected.isChecked }
        if (selectedRows.isEmpty()) { status.text = "Select at least one product to count. No changes were made."; return }
        if (reason.text.toString().trim().length !in 4..120) {
            status.text = "Enter a count reason between 4 and 120 characters. No changes were made."
            return
        }
        val counts = linkedMapOf<String, Int>()
        for (row in selectedRows) {
            val value = row.quantity.text.toString().trim().toIntOrNull()
            if (value == null || value !in 0..100_000) {
                status.text = "Enter a whole-number physical count from 0 to 100,000 for every selected product. No changes were made."
                return
            }
            counts[row.product.productId] = value
        }
        val preview = runCatching {
            StockCountReview.build(current.products(), counts, current.stockLevels(),
                current.products().filter { current.isStockConfirmed(it.productId) }.map { it.productId }.toSet())
        }.getOrElse { error ->
            status.text = "Count review could not be prepared (${error.javaClass.simpleName}). No changes were made."
            return
        }
        val previewText = buildString {
            append("Nothing changes until you confirm. Verify each physical count below.\n\n")
            preview.forEach { row ->
                append(row.productName).append(": ")
                val variance = row.variance
                if (variance == null) {
                    append("counted ").append(row.countedQuantity).append(' ').append(row.unitLabel).append(" · no confirmed prior baseline; this establishes opening stock")
                } else {
                    append("prior ").append(row.previousQuantity).append(", counted ").append(row.countedQuantity)
                    append(", variance ").append(if (variance > 0) "+$variance" else variance.toString()).append(' ').append(row.unitLabel)
                }
                append('\n')
            }
        }.trimEnd()
        AlertDialog.Builder(this)
            .setTitle("Review physical stock count")
            .setMessage(previewText)
            .setNegativeButton("Edit counts", null)
            .setPositiveButton("Confirm and save counts") { _, _ ->
                val result = current.confirmPhysicalStockCounts(counts, reason.text.toString(), "physical-count-${UUID.randomUUID()}")
                if (result.startsWith("Physical count saved")) render(result) else status.text = result
            }
            .show()
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
