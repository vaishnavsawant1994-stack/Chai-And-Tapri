package com.chaiandtapri.counter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** CT-15 local inventory view. Only owner-confirmed balances are displayed as quantities. */
class InventoryOverviewActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); if (content.childCount > 0) render() }

    private fun render() {
        model?.close()
        content.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            model = current
            content.addView(label("Inventory Overview · CT-15", 24f))
            content.addView(label("Local stock ledger. Unconfirmed balances are not presented as verified. Purchase receipts and supplier payables are recorded separately.", 14f))
            val products = current.products()
            if (products.isEmpty()) content.addView(label("No products in the catalogue.", 16f))
            products.forEach { product ->
                val confirmed = current.isStockConfirmed(product.productId)
                val quantity = current.stockLevels()[product.productId]
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
                card.addView(label(product.name, 18f))
                card.addView(label(if (confirmed) "Confirmed stock: ${quantity ?: 0} ${if (quantity == 1) "unit" else "units"}" else "Physical count required · balance not verified", 15f))
                card.addView(label(if (product.active) "Active catalogue item" else "Inactive catalogue item", 13f))
                content.addView(card, matchWidth())
            }

            content.addView(label("Recent stock movements", 19f))
            val names = products.associate { it.productId to it.name }
            val movements = current.stockMovements().asReversed().take(30)
            if (movements.isEmpty()) content.addView(label("No stock movements recorded.", 14f))
            movements.forEach { movement ->
                val delta = if (movement.quantityDelta > 0) "+${movement.quantityDelta}" else movement.quantityDelta.toString()
                val order = movement.orderNumber?.let { " · Order $it" }.orEmpty()
                content.addView(label("${names[movement.productId] ?: movement.productId}: $delta units · ${movement.reason}$order", 14f))
            }
            content.addView(label("Stock counts and adjustments affect inventory only; they do not create purchases or supplier payables.", 13f))
            content.addView(button("Count multiple products · CT-16") { startActivity(Intent(this, StockCountActivity::class.java)) })
            content.addView(button("Catalogue and single-product count") { startActivity(Intent(this, ProductCatalogueActivity::class.java)) })
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Inventory could not be opened. No replacement data was created.", 18f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 9, 0, 9) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }
}
