package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** CT-08/09/10 first slice: list persisted orders and perform validated fulfillment/tender actions. */
class LiveOrdersActivity : Activity() {
    private lateinit var model: CounterModel
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireCounterAccess(this)) return
        model = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 24, 20, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(label("Live Orders", 25f))
        root.addView(label("Order fulfillment and payment are shown independently. Cash is staff-declared; UPI is pending unless provider evidence is integrated.", 14f))
        status = label("", 14f); root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        root.addView(button("Back to counter") { finish() })
        render()
    }

    private fun render() {
        list.removeAllViews()
        val orders = model.allOrders()
        if (orders.isEmpty()) list.addView(label("No recorded orders yet.", 17f))
        orders.forEach { order ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 14, 12, 14) }
            card.addView(label("Order #${order.number} · ${order.fulfillment}", 19f))
            card.addView(label(order.items, 16f))
            card.addView(label("Sale ₹${money(order.salePaise)} · Payment ${order.payment} · Due ₹${money(order.duePaise)}", 15f))
            val revision = order.revision
            card.addView(button("View order details") {
                startActivity(android.content.Intent(this, OrderDetailsActivity::class.java).putExtra(OrderDetailsActivity.EXTRA_ORDER_NUMBER, order.number))
            })
            if (order.fulfillment in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) && (order.duePaise > 0 || order.payment == Payment.UPI_PENDING)) {
                card.addView(button("Payment options · CT-10") {
                    startActivity(android.content.Intent(this, PaymentActivity::class.java).putExtra(PaymentActivity.EXTRA_ORDER_NUMBER, order.number))
                })
            }
            fun run(type: String, description: String, amount: Int? = null, customer: String? = null) {
                val result = model.apply("ui-${java.util.UUID.randomUUID()}", ProposedCommand(type, order.number, description, amountPaise = amount, customerId = customer, expectedRevision = revision))
                status.text = result
                render()
            }
            if (order.fulfillment == Fulfillment.TAKEN) card.addView(button("Start preparing") { run("preparing", "Start preparing order #${order.number}") })
            if (order.fulfillment in setOf(Fulfillment.TAKEN, Fulfillment.PREPARING)) card.addView(button("Mark ready") { run("ready", "Mark order #${order.number} ready") })
            if (order.fulfillment in setOf(Fulfillment.READY, Fulfillment.PARTIALLY_GIVEN)) {
                if (order.lines.any { it.quantity > it.deliveredQuantity }) {
                    card.addView(button("Hand over selected items") { collectPartialHandover(order) })
                    card.addView(button("Hand over remaining items") { run("given", "Hand over order #${order.number}") })
                }
            }
            if (order.duePaise > 0 && order.fulfillment in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) && order.payment != Payment.UPI_PENDING) {
                card.addView(button("Record cash received · staff declared") { status.text = model.recordCash(order.number, null, "ui-cash-${java.util.UUID.randomUUID()}", revision); render() })
                card.addView(button("Mark UPI pending") { status.text = model.recordUpiPending(order.number, "ui-upi-${java.util.UUID.randomUUID()}", revision); render() })
                card.addView(button("Record partial cash") { askCash(order.number, order.duePaise, revision) })
                card.addView(button("Record Udhar") { askCustomer(order.number, revision) })
            }
            list.addView(card, matchWidth())
        }
    }

    private lateinit var status: TextView

    private fun collectPartialHandover(order: CounterOrder) {
        val remaining = order.lines.filter { it.quantity > it.deliveredQuantity }
        if (remaining.isEmpty()) {
            status.text = "No items remain to hand over."
            return
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 0) }
        val item = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@LiveOrdersActivity,
                android.R.layout.simple_spinner_dropdown_item,
                remaining.map { line -> "${line.name} · ${line.quantity - line.deliveredQuantity} remaining" }
            )
        }
        val count = EditText(this).apply {
            hint = "Quantity to hand over"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("1")
        }
        form.addView(label("Select the ready item", 14f))
        form.addView(item)
        form.addView(count)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Partial handover · Order #${order.number}")
            .setMessage("Only the selected quantity will be marked as given and deducted from confirmed stock. The order sale is recorded when all items have been handed over.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Record handover", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = remaining.getOrNull(item.selectedItemPosition)
                val available = selected?.let { it.quantity - it.deliveredQuantity } ?: 0
                val quantity = count.text.toString().trim().toIntOrNull()
                if (selected == null || quantity == null || quantity !in 1..available) {
                    count.error = "Enter a quantity from 1 to $available"
                    return@setOnClickListener
                }
                dialog.dismiss()
                val result = model.apply(
                    "ui-partial-handover-${java.util.UUID.randomUUID()}",
                    ProposedCommand(
                        type = "partial_given",
                        orderNumber = order.number,
                        description = "Hand over $quantity × ${selected.name} for order #${order.number}",
                        productId = selected.productId,
                        quantity = quantity,
                        expectedRevision = order.revision
                    )
                )
                status.text = result
                render()
            }
        }
        dialog.show()
    }

    private fun askCash(orderNumber: Int, duePaise: Int, revision: Int) {
        val amount = EditText(this).apply {
            hint = "Amount in rupees (remaining due ${money(duePaise)})"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Record partial cash")
            .setMessage("Staff-declared cash only; this is not externally verified.")
            .setView(amount).setNegativeButton("Cancel", null).setPositiveButton("Record cash") { _, _ ->
                val paise = parsePaise(amount.text.toString())
                if (paise == null) status.text = "Enter a valid positive rupee amount. No payment was recorded."
                else {
                    status.text = model.recordCash(orderNumber, paise, "ui-cash-${java.util.UUID.randomUUID()}", revision)
                    render()
                }
            }.show()
    }

    private fun askCustomer(orderNumber: Int, revision: Int) {
        CustomerAccountPicker.show(
            activity = this,
            model = model,
            title = "Select customer for Udhar",
            onSelect = { customerId ->
                val amount = EditText(this).apply {
                    hint = "Amount in rupees (blank for full due)"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setSingleLine(true)
                }
                AlertDialog.Builder(this).setTitle("Record Udhar for $customerId")
                    .setMessage("This creates a receivable, not a payment collection.")
                    .setView(amount).setNegativeButton("Cancel", null).setPositiveButton("Save credit") { _, _ ->
                        val raw = amount.text.toString().trim()
                        val paise = if (raw.isEmpty()) null else parsePaise(raw)
                        if (raw.isNotEmpty() && paise == null) status.text = "Enter a valid positive amount. No credit was recorded."
                        else {
                            status.text = model.recordUdhar(orderNumber, customerId, "ui-credit-${java.util.UUID.randomUUID()}", paise, revision)
                            render()
                        }
                    }.show()
            },
            onAddCustomer = { startActivity(android.content.Intent(this, CustomerCreditActivity::class.java)) }
        )
    }

    private fun parsePaise(raw: String): Int? = runCatching {
        raw.trim().toBigDecimal().movePointRight(2).intValueExact().takeIf { it > 0 }
    }.getOrNull()

    private fun money(paise: Int) = String.format(Locale.ROOT, "%.2f", paise / 100.0)
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 10, 0, 10) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onResume() { super.onResume(); if (::list.isInitialized) render() }
    override fun onDestroy() { if (::model.isInitialized) model.close(); super.onDestroy() }
}
