package com.chaiandtapri.counter

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** CT-12: audited cancellation, item return, and amount-correction workflows over the local transaction engine. */
class RefundCorrectionActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private var busy = false
    private val worker = RecoveryExecutor.executor
    private val requestedOrder: Int? get() = intent.getIntExtra(EXTRA_ORDER_NUMBER, -1).takeIf { it > 0 }

    private data class RefundableLine(val productId: String, val name: String, val remaining: Int, val unitPricePaise: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        loadOrders()
    }

    private fun loadOrders(message: String? = null) {
        content.removeAllViews()
        content.addView(label("Refund & Correction · CT-12", 25f))
        content.addView(label("Refunds and cancellations are audited. Cash refunds are staff-declared; no external payment is verified here.", 14f))
        content.addView(label(message ?: "Loading local encrypted records…", 14f))
        content.addView(button("Back") { finish() })
        worker.execute {
            val result = runCatching {
                CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true).use { it.allOrders() }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                content.removeAllViews()
                content.addView(label("Refund & Correction · CT-12", 25f))
                content.addView(label("Refunds and cancellations are audited. Cash refunds are staff-declared; no external payment is verified here.", 14f))
                result.fold({ orders ->
                    val visible = requestedOrder?.let { orderNumber -> orders.filter { it.number == orderNumber } } ?: orders
                    if (visible.isEmpty()) content.addView(label("No matching order is available. Refresh Sales History and try again.", 16f))
                    visible.forEach { order -> addOrderCard(order) }
                }, { error ->
                    content.addView(label("Local financial records could not be opened. No changes were made (${error.javaClass.simpleName}).", 15f))
                    content.addView(button("Retry") { loadOrders() })
                })
                content.addView(button("Back") { finish() })
            }
        }
    }

    private fun addOrderCard(order: CounterOrder) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 14, 12, 14) }
        card.addView(label("Order #${order.number} · ${order.items}", 18f))
        card.addView(label("${order.fulfillment} / ${order.payment} · revision ${order.revision}", 13f))
        card.addView(label("Delivered sale ${money(order.salePaise)} · paid ${money(order.paidPaise)} · Udhar ${money(order.creditPaise)} · refunded ${money(order.refundedPaise)} · due ${money(order.duePaise)}", 14f))
        if (!order.voided && order.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED)) {
            card.addView(button("Cancel open order #${order.number}") { confirmCancellation(order) })
        }
        val refundable = order.lines.mapNotNull { line ->
            val count = line.deliveredQuantity - line.refundedQuantity
            if (count > 0) RefundableLine(line.productId, line.name, count, line.unitPricePaise) else null
        }
        if (!order.voided && order.fulfillment in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) && refundable.isNotEmpty()) {
            card.addView(button("Return delivered item(s)") { collectItemRefund(order, refundable) })
        }
        if (!order.voided && order.salePaise > order.refundedPaise && order.fulfillment in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED)) {
            card.addView(button("Goodwill / amount correction") { collectAmountRefund(order) })
        }
        if (order.voided) card.addView(label("No remaining refundable value.", 13f))
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun confirmCancellation(order: CounterOrder) {
        val reason = EditText(this).apply { hint = "Required reason"; minLines = 2 }
        AlertDialog.Builder(this)
            .setTitle("Cancel order #${order.number}?")
            .setMessage("This applies only before handover. Handed-over orders must use the refund path.")
            .setView(reason)
            .setNegativeButton("Keep order", null)
            .setPositiveButton("Review cancellation", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val note = reason.text.toString().trim()
                        if (note.isBlank()) { reason.error = "Enter a correction reason"; return@setOnClickListener }
                        dialog.dismiss()
                        AlertDialog.Builder(this)
                            .setTitle("Confirm cancellation")
                            .setMessage("Cancel order #${order.number} with reason: $note")
                            .setNegativeButton("Go back", null)
                            .setPositiveButton("Record cancellation") { _, _ ->
                                mutate("Cancelling order…") { model -> model.cancelOrder(order.number, note, UUID.randomUUID().toString(), order.revision) }
                            }.show()
                    }
                }
            }.show()
    }

    private fun collectItemRefund(order: CounterOrder, lines: List<RefundableLine>) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 0) }
        val product = Spinner(this).apply {
            adapter = ArrayAdapter(this@RefundCorrectionActivity, android.R.layout.simple_spinner_dropdown_item,
                lines.map { "${it.name} · ${it.remaining} available · ${money(it.unitPricePaise)} each" })
        }
        val quantity = EditText(this).apply { hint = "Returned quantity"; inputType = InputType.TYPE_CLASS_NUMBER; setText("1") }
        val disposition = Spinner(this).apply {
            adapter = ArrayAdapter(this@RefundCorrectionActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Do not restock · product returnability policy is not configured"))
        }
        val reason = EditText(this).apply { hint = "Required return/correction reason"; minLines = 2 }
        val cashConfirmed = CheckBox(this).apply { text = "I confirm any cash refund was handed to the customer (staff-declared)" }
        form.addView(label("Returned item", 14f)); form.addView(product)
        form.addView(quantity); form.addView(label("Returned stock disposition", 14f)); form.addView(disposition)
        form.addView(reason); form.addView(cashConfirmed)
        val dialog = AlertDialog.Builder(this).setTitle("Review item return · Order #${order.number}")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Review", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = lines.getOrNull(product.selectedItemPosition) ?: return@setOnClickListener
                val count = quantity.text.toString().trim().toIntOrNull()
                val note = reason.text.toString().trim()
                if (count == null || count !in 1..selected.remaining) { quantity.error = "Enter 1–${selected.remaining}"; return@setOnClickListener }
                if (note.isBlank()) { reason.error = "Enter a return reason"; return@setOnClickListener }
                val dispositionValue = "not_restocked"
                val amount = count.toLong() * selected.unitPricePaise
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Confirm item return")
                    .setMessage("Order #${order.number}: ${selected.name} × $count · refund basis ${money(amount.toInt())}. The engine allocates against this order’s receivable and collected cash; pending UPI is not treated as collected. Returned stock will not be restocked because a product returnability policy is not configured. Any cash payout requires staff confirmation.")
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("Record audited refund") { _, _ ->
                        mutate("Recording item refund…") { model ->
                            model.refundItems(order.number, mapOf(selected.productId to count), note,
                                mapOf(selected.productId to dispositionValue), cashConfirmed.isChecked,
                                UUID.randomUUID().toString(), order.revision)
                        }
                    }.show()
            }
        }
        dialog.show()
    }

    private fun collectAmountRefund(order: CounterOrder) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 0) }
        val amount = EditText(this).apply { hint = "Amount in ₹ (up to 2 decimals)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val reason = EditText(this).apply { hint = "Required correction reason"; minLines = 2 }
        val cashConfirmed = CheckBox(this).apply { text = "I confirm any cash refund was handed to the customer (staff-declared)" }
        form.addView(amount); form.addView(reason); form.addView(cashConfirmed)
        val dialog = AlertDialog.Builder(this).setTitle("Amount correction · Order #${order.number}")
            .setMessage("Use only for an authorized goodwill or price correction. This does not return inventory.")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Review", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val paise = runCatching { BigDecimal(amount.text.toString().trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact() }.getOrNull()
                val note = reason.text.toString().trim()
                if (paise == null || paise <= 0) { amount.error = "Enter a positive amount with at most two decimals"; return@setOnClickListener }
                if (note.isBlank()) { reason.error = "Enter a correction reason"; return@setOnClickListener }
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Confirm amount correction")
                    .setMessage("Order #${order.number}: reduce the remaining sale by ${money(paise)}. The engine applies its validated allocation rules; any cash paid out is staff-declared. Inventory will not change.")
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("Record audited correction") { _, _ ->
                        mutate("Recording amount correction…") { model ->
                            model.refundAmount(order.number, paise, note, cashConfirmed.isChecked, UUID.randomUUID().toString(), order.revision)
                        }
                    }.show()
            }
        }
        dialog.show()
    }

    private fun mutate(progress: String, action: (CounterModel) -> String) {
        if (busy) return
        busy = true
        content.addView(label(progress, 14f), 2)
        worker.execute {
            val result = runCatching {
                CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true).use(action)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                val message = result.fold({ it }, { "No change was made (${it.javaClass.simpleName})." })
                loadOrders(message)
            }
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 60; setOnClickListener { if (!busy) action() } }
    private fun money(paise: Int) = "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

    companion object { const val EXTRA_ORDER_NUMBER = "order_number" }
}
