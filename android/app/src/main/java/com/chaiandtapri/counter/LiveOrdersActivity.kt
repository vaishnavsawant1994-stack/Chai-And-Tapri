package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

/** CT-08/09/10 first slice: list persisted orders and perform validated fulfillment/tender actions. */
class LiveOrdersActivity : Activity() {
    private lateinit var model: CounterModel
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var filter: Spinner
    private var searchQuery = ""
    private var selectedFilter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireCounterAccess(this)) return
        model = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 24, 20, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(label("Live Orders", 25f))
        root.addView(label("Order fulfillment and payment are shown independently. Cash is staff-declared; UPI is pending unless provider evidence is integrated.", 14f))
        status = label("", 14f); root.addView(status)
        search = EditText(this).apply {
            hint = "Find order number, item or status"
            contentDescription = "Search live orders"
            setSingleLine(true)
            setText(searchQuery)
        }
        root.addView(search)
        filter = Spinner(this).apply {
            adapter = ArrayAdapter(this@LiveOrdersActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("All orders", "Needs preparation or handover", "Ready to hand over", "Payment due", "UPI pending"))
            contentDescription = "Filter live orders"
            setSelection(selectedFilter, false)
        }
        root.addView(filter)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        root.addView(button("Back to counter") { finish() })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                render()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        filter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedFilter = position
                render()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val orders = model.allOrders()
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        val filtered = orders.filter { order ->
            val matchesText = query.isEmpty() || listOf(order.number.toString(), order.items, order.fulfillment.name, order.payment.name)
                .any { it.lowercase(Locale.ROOT).contains(query) }
            val matchesFilter = when (selectedFilter) {
                1 -> order.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED)
                2 -> order.fulfillment in setOf(Fulfillment.READY, Fulfillment.PARTIALLY_GIVEN)
                3 -> order.duePaise > 0 && order.fulfillment in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED)
                4 -> order.payment == Payment.UPI_PENDING
                else -> true
            }
            matchesText && matchesFilter
        }
        if (filtered.isEmpty()) list.addView(label(if (orders.isEmpty()) "No recorded orders yet." else "No orders match this search and filter.", 17f))
        filtered.forEach { order ->
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
        val controls = remaining.map { line ->
            val available = line.quantity - line.deliveredQuantity
            val check = CheckBox(this).apply {
                text = "${line.name} · $available remaining"
                contentDescription = "Select ${line.name} for handover"
            }
            val count = EditText(this).apply {
                hint = if (remaining.size == 1) "Quantity to hand over" else "Quantity for ${line.name}"
                contentDescription = hint
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setText("1")
            }
            form.addView(check)
            form.addView(count)
            Triple(line, check, count)
        }
        form.addView(label("Select ready items and enter each handover quantity. Unselected items remain on the order.", 14f), 0)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Partial handover · Order #${order.number}")
            .setMessage("Selected quantities are recorded together as one atomic action. Only those items are marked given and deducted from confirmed stock. The sale is posted when all items have been handed over.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Record handover", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selections = linkedMapOf<String, Int>()
                for ((line, check, count) in controls) {
                    if (!check.isChecked) continue
                    val available = line.quantity - line.deliveredQuantity
                    val quantity = count.text.toString().trim().toIntOrNull()
                    if (quantity == null || quantity !in 1..available) {
                        count.error = "Enter a quantity from 1 to $available"
                        return@setOnClickListener
                    }
                    selections[line.productId] = quantity
                }
                if (selections.isEmpty()) {
                    status.text = "Select at least one item to hand over. No order changed."
                    return@setOnClickListener
                }
                val result = model.partialHandover(order.number, selections, "ui-partial-handover-${java.util.UUID.randomUUID()}", order.revision)
                dialog.dismiss()
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
