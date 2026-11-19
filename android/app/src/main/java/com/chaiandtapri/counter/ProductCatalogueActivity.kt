package com.chaiandtapri.counter

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** CT-13/14: catalogue and explicit owner-confirmation of actual selling prices. */
class ProductCatalogueActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private var products = emptyList<TapriProduct>()
    private var stock = emptyMap<String, Int>()
    private var confirmedStock = emptySet<String>()
    @Volatile private var destroyed = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireOwner(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        content.addView(label("Products & Prices", 25f))
        content.addView(label("Starter prices are examples. Review each price and explicitly confirm it before the counter can sell that item. New items start with zero stock.", 15f))
        content.addView(button("Add product") { editProduct(null) })
        status = label("Opening encrypted catalogue…", 14f); content.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        content.addView(button("Back to counter") { finish() })
        RecoveryExecutor.executor.execute {
            val opened = runCatching {
                CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true).let { store -> store to readSnapshot(store) }
            }
            runOnUiThread {
                if (destroyed || isFinishing || isDestroyed) { opened.getOrNull()?.first?.close(); return@runOnUiThread }
                opened.fold({ (store, state) -> model = store; applySnapshot(state); status.text = ""; render() },
                    { error -> status.text = "Catalogue could not be opened (${error.javaClass.simpleName}). No data was changed." })
            }
        }
    }

    private data class Snapshot(val products: List<TapriProduct>, val stock: Map<String, Int>, val confirmed: Set<String>)

    private fun readSnapshot(store: CounterModel) = Snapshot(store.products(), store.stockLevels(), store.products()
        .filter { store.isStockConfirmed(it.productId) }.map { it.productId }.toSet())

    private fun applySnapshot(value: Snapshot) { products = value.products; stock = value.stock; confirmedStock = value.confirmed }

    private fun render() {
        list.removeAllViews()
        products.forEach { product ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
            card.addView(label("${product.name} · ${product.category}", 18f))
            val price = if (product.priceConfirmed) "₹${rupees(product.unitPricePaise)} · Owner confirmed" else "₹${rupees(product.unitPricePaise)} · Example price, not confirmed"
            card.addView(label("$price · ${if (product.active) "Active" else "Inactive"}${if (product.favorite) " · Favourite" else ""}", 14f))
            val countConfirmed = product.productId in confirmedStock
            val stockText = if (countConfirmed) "Opening count confirmed · ${stock[product.productId] ?: 0} units" else "Opening count not confirmed · existing value hidden"
            card.addView(label(stockText, 14f))
            card.addView(label(product.reorderThreshold?.let { "Low-stock alert at ${it} units or fewer" } ?: "Low-stock alert not configured", 13f))
            if (product.active) card.addView(button(if (countConfirmed) "Update physical stock count" else "Confirm physical opening stock") { confirmOpeningStock(product) })
            card.addView(button("Edit ${product.name}") { editProduct(product) })
            list.addView(card, matchWidth())
        }
    }

    private fun confirmOpeningStock(product: TapriProduct) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        form.addView(label("Count the actual sellable units on hand. This replaces the local balance through an audited stock movement; it does not create a purchase.", 14f))
        val quantity = EditText(this).apply {
            hint = "Physical quantity counted (0 to 100000)"
            inputType = InputType.TYPE_CLASS_NUMBER
            if (product.productId in confirmedStock) setText((stock[product.productId] ?: 0).toString())
        }
        val reason = EditText(this).apply { hint = "Count note / reason (at least 4 characters)" }
        form.addView(quantity); form.addView(reason)
        android.app.AlertDialog.Builder(this).setTitle("Confirm ${product.name} stock").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save physical count") { _, _ ->
                val count = quantity.text.toString().toIntOrNull()
                status.text = if (count == null) "Enter a whole-number physical count. No change made."
                else {
                    performMutation("Saving physical count…") { store -> store.confirmOpeningStock(product.productId, count, reason.text.toString()) }
                    return@setPositiveButton
                }
                render()
            }.show()
    }

    private fun editProduct(existing: TapriProduct?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        fun field(hint: String, initial: String = "", numeric: Boolean = false, integer: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint; setText(initial); setSingleLine(true)
            if (integer) inputType = InputType.TYPE_CLASS_NUMBER else if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }.also { form.addView(it) }
        val id = if (existing == null) field("Stable product ID, e.g. bun_maska") else null
        val name = field("Product name", existing?.name.orEmpty())
        val category = field("Category", existing?.category.orEmpty())
        val price = field("Actual selling price in ₹", existing?.let { rupees(it.unitPricePaise) }.orEmpty(), true)
        val aliases = field("Voice/search aliases, comma separated", existing?.aliases?.joinToString(", ").orEmpty())
        val reorderThreshold = field("Low-stock alert threshold in units (optional, 1–100000)", existing?.reorderThreshold?.toString().orEmpty(), integer = true)
        val active = CheckBox(this).apply { text = "Active for sale"; isChecked = existing?.active ?: true }; form.addView(active)
        val favorite = CheckBox(this).apply { text = "Favourite shortcut"; isChecked = existing?.favorite ?: false }; form.addView(favorite)
        val confirmed = CheckBox(this).apply { text = "I confirm this is the shop's actual price"; isChecked = existing?.priceConfirmed ?: false }; form.addView(confirmed)
        val scroll = ScrollView(this).apply { addView(form) }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New product" else "Edit product")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val rupees = price.text.toString().trim().toBigDecimalOrNull()
                val paise = runCatching { rupees?.movePointRight(2)?.intValueExact() }.getOrNull()
                val product = TapriProduct(
                    productId = existing?.productId ?: id?.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                    name = name.text.toString(), category = category.text.toString(), unitPricePaise = paise ?: -1,
                    active = active.isChecked, favorite = favorite.isChecked, priceConfirmed = confirmed.isChecked,
                    aliases = aliases.text.toString().split(',').map(String::trim).filter(String::isNotBlank),
                    reorderThreshold = reorderThreshold.text.toString().trim().takeIf(String::isNotEmpty)?.toIntOrNull() ?: if (reorderThreshold.text.isNullOrBlank()) null else -1
                )
                performMutation("Saving product…") { store -> store.saveProduct(product) }
            }.show()
    }

    private fun rupees(paise: Int) = String.format(Locale.ROOT, "%.2f", paise / 100.0)

    private fun performMutation(progress: String, mutation: (CounterModel) -> String) {
        val store = model ?: return
        if (busy) return
        busy = true
        setButtonsEnabled(false)
        status.text = progress
        RecoveryExecutor.executor.execute {
            val result = runCatching { mutation(store) }
            val refreshed = result.map { message -> message to readSnapshot(store) }
            runOnUiThread {
                busy = false
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                setButtonsEnabled(true)
                refreshed.fold({ (message, state) -> applySnapshot(state); status.text = message; render() },
                    { error -> status.text = "Save failed; no success was recorded (${error.javaClass.simpleName})." })
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        fun visit(group: android.view.ViewGroup) { for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is Button) child.isEnabled = enabled
            if (child is android.view.ViewGroup) visit(child)
        } }
        visit(content)
    }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 10, 0, 10) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() { destroyed = true; model?.let { store -> RecoveryExecutor.executor.execute { store.close() } }; model = null; super.onDestroy() }
}
