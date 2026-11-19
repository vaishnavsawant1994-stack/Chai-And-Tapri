package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.UUID

/** CT-10: local order collections; provider verification is deliberately not represented. */
class PaymentActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var model: CounterModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireCounterAccess(this)) return
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() { super.onResume(); if (::root.isInitialized) render() }
    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }

    private fun render() {
        model?.close()
        root.removeAllViews()
        try {
            val current = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            model = current
            root.addView(label("Payments · CT-10", 25f))
            root.addView(label("Cash is staff-declared. UPI pending is not a collection. A later UPI receipt can only be recorded as staff-declared here; provider verification is not integrated.", 14f))
            status = label("", 14f); root.addView(status)
            val requestedOrder = intent.getIntExtra(EXTRA_ORDER_NUMBER, -1)
            val eligible = current.allOrders().filter { (requestedOrder < 0 || it.number == requestedOrder) && !it.voided && it.fulfillment in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) && (it.duePaise > 0 || it.payment == Payment.UPI_PENDING) }
            if (eligible.isEmpty()) root.addView(label("No completed orders need a collection or UPI follow-up.", 17f))
            eligible.forEach { order ->
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
                card.addView(label("Order #${order.number} · ${order.items}", 18f))
                card.addView(label("Sale ${money(order.salePaise)} · paid ${money(order.paidPaise)} · Udhar ${money(order.creditPaise)} · due ${money(order.duePaise)}", 14f))
                card.addView(label("${order.fulfillment} · ${order.payment}${if (order.payment == Payment.UPI_PENDING) " · UPI receipt not verified" else ""} · revision ${order.revision}", 13f))
                val revision = order.revision
                if (order.payment == Payment.UPI_PENDING) {
                    card.addView(button("Record UPI received · staff-declared") { confirmUpiReceipt(order.number, order.duePaise, revision) })
                } else if (order.duePaise > 0) {
                    card.addView(button("Record cash received · staff-declared") { amountPrompt("Record cash received", order.duePaise, "Cash was physically received; this entry is staff-declared.") { amount ->
                        mutate(current) { it.recordCash(order.number, amount, "payment-cash-${UUID.randomUUID()}", revision) }
                    } })
                    card.addView(button("Record UPI received · staff-declared") { directUpiReceiptPrompt(current, order.number, order.duePaise, revision) })
                    card.addView(button("Mark UPI payment pending") {
                        AlertDialog.Builder(this).setTitle("Mark UPI pending")
                            .setMessage("This records an expected payment attempt for ${money(order.duePaise)}. It does not record collected funds or provider verification.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Record pending attempt") { _, _ ->
                                mutate(current) { it.recordUpiPending(order.number, "payment-upi-${UUID.randomUUID()}", revision) }
                            }.show()
                    })
                    card.addView(button("Split cash + UPI pending") { splitCashUpiPendingPrompt(current, order.number, order.duePaise, revision) })
                    card.addView(button("Record Udhar for existing customer ID") { udharPrompt(current, order.number, order.duePaise, revision) })
                    card.addView(button("Split cash + Udhar") { splitCashUdharPrompt(current, order.number, order.duePaise, revision) })
                }
                card.addView(button("View order details") { startActivity(android.content.Intent(this, OrderDetailsActivity::class.java).putExtra(OrderDetailsActivity.EXTRA_ORDER_NUMBER, order.number)) })
                root.addView(card, matchWidth())
            }
            root.addView(button("Payment history") { showPaymentHistory(current, requestedOrder) })
            root.addView(button("Back") { finish() })
        } catch (failure: Exception) {
            root.addView(label("Payment records could not be opened. No payment was created. (${failure.javaClass.simpleName})", 16f))
            root.addView(button("Retry") { render() })
        }
    }

    private fun confirmUpiReceipt(orderNumber: Int, duePaise: Int, revision: Int) {
        amountPrompt("Record UPI received", duePaise, "Only continue if you observed the payment receipt. This creates a staff-declared collection, not provider verification.") { amount ->
            AlertDialog.Builder(this).setTitle("Confirm staff declaration")
                .setMessage("Record ${money(amount ?: duePaise)} as received by UPI according to staff observation? The system will retain the original pending attempt and append a cancellation event. No bank/provider verification is asserted.")
                .setNegativeButton("Go back", null).setPositiveButton("Record staff-declared receipt") { _, _ ->
                    val current = model ?: return@setPositiveButton
                    mutate(current) {
                        it.recordUpiReceived(orderNumber, amount, "payment-upi-received-${UUID.randomUUID()}", revision)
                    }
                }.show()
        }
    }

    private fun directUpiReceiptPrompt(current: CounterModel, orderNumber: Int, duePaise: Int, revision: Int) {
        amountPrompt(
            "Record UPI received",
            duePaise,
            "Use only if you observed the customer's UPI receipt. This records a staff-declared collection and does not verify a bank/provider transaction. If this order already has a UPI pending attempt, resolve that attempt instead."
        ) { amount ->
            val value = amount ?: duePaise
            AlertDialog.Builder(this)
                .setTitle("Confirm staff-declared UPI receipt")
                .setMessage("Record ${money(value)} as received by UPI according to staff observation? No external provider verification is asserted.")
                .setNegativeButton("Go back", null)
                .setPositiveButton("Record staff-declared receipt") { _, _ ->
                    mutate(current) { it.recordUpiDirect(orderNumber, value, "payment-upi-direct-${UUID.randomUUID()}", revision) }
                }
                .show()
        }
    }

    private fun amountPrompt(title: String, duePaise: Int, disclosure: String, onConfirm: (Int?) -> Unit) {
        val amount = EditText(this).apply {
            hint = "Amount in rupees · blank = full due (${money(duePaise)})"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        val message = TextView(this).apply { text = disclosure; textSize = 14f; setPadding(24, 8, 24, 12) }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(message); addView(amount) }
        AlertDialog.Builder(this).setTitle(title).setView(form).setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ ->
            val raw = amount.text.toString().trim()
            val parsed = if (raw.isEmpty()) null else runCatching { raw.toBigDecimal().movePointRight(2).intValueExact().takeIf { it > 0 && it <= duePaise } }.getOrNull()
            if (raw.isNotEmpty() && parsed == null) status.text = "Enter a valid positive amount no greater than the due. No payment was recorded."
            else onConfirm(parsed)
        }.show()
    }

    private fun showPaymentHistory(current: CounterModel, orderFilter: Int) {
        val entries = current.paymentHistory().filter { orderFilter < 0 || it.orderNumber == orderFilter }
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Payment history")
                .setMessage("No payment ledger entries are recorded${if (orderFilter >= 0) " for order #$orderFilter" else " yet"}.")
                .setPositiveButton("Close", null)
                .show()
            return
        }
        val history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        entries.asReversed().forEach { entry ->
            history.addView(label(paymentHistoryLine(entry), 15f))
        }
        AlertDialog.Builder(this)
            .setTitle(if (orderFilter >= 0) "Payment history · order #$orderFilter" else "Payment history")
            .setView(ScrollView(this).apply { addView(history) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun paymentHistoryLine(entry: PaymentEntry): String {
        val orderLabel = entry.orderNumber?.let { "Order #$it" } ?: "Customer account"
        val evidence = when {
            entry.kind == "payment_attempt_cancelled" -> "UPI attempt cancelled; no collection recorded"
            entry.status == "pending" -> "UPI pending; not collected or verified"
            entry.status == "receivable" -> "Udhar receivable; not collected"
            entry.status == "reversed" || entry.kind == "credit_reversal" -> "Credit reversal"
            entry.kind.contains("refund") -> "Refund; ${entry.status.replace('_', ' ')}"
            entry.method == "upi" && entry.status == "staff_declared" -> "UPI receipt; staff-declared, not provider-verified"
            entry.method == "cash" && entry.status == "staff_declared" -> "Cash; staff-declared"
            entry.kind == "customer_repayment" -> "Customer repayment; staff-declared"
            else -> "${entry.method.replace('_', ' ')}; ${entry.status.replace('_', ' ')}"
        }
        return "$orderLabel · ${money(entry.amountPaise)} · ${entry.kind.replace('_', ' ')}\n$evidence"
    }

    private fun udharPrompt(current: CounterModel, orderNumber: Int, duePaise: Int, revision: Int) {
        CustomerAccountPicker.show(
            activity = this,
            model = current,
            title = "Select customer for Udhar",
            onSelect = { customerId ->
                amountPrompt("Record Udhar for $customerId", duePaise, "This records a receivable, not collected money.") { amount ->
                    mutate(current) {
                        it.recordUdhar(orderNumber, customerId, "payment-udhar-${UUID.randomUUID()}", amount, revision)
                    }
                }
            },
            onAddCustomer = { startActivity(android.content.Intent(this, CustomerCreditActivity::class.java)) }
        )
    }

    private fun splitCashUdharPrompt(current: CounterModel, orderNumber: Int, duePaise: Int, revision: Int) {
        CustomerAccountPicker.show(
            activity = this,
            model = current,
            title = "Select customer for remaining Udhar",
            onSelect = { customerId ->
                val cashInput = EditText(this).apply {
                    hint = "Cash received in rupees"
                    contentDescription = "Cash received in rupees"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setSingleLine(true)
                }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Cash plus Udhar")
                    .setMessage("Order due ${money(duePaise)}. Enter the cash physically received; the remainder becomes a receivable for $customerId. Cash is staff-declared.")
                    .setView(cashInput)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Review split", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val cashPaise = runCatching { cashInput.text.toString().trim().toBigDecimal().movePointRight(2).intValueExact() }.getOrNull()
                        if (cashPaise == null || cashPaise <= 0 || cashPaise >= duePaise) {
                            status.text = "Cash must be positive and less than the due. No payment was recorded."
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        AlertDialog.Builder(this)
                            .setTitle("Confirm split collection")
                            .setMessage("Record ${money(cashPaise)} cash as staff-declared and ${money(duePaise - cashPaise)} as Udhar for $customerId? This does not verify a UPI or bank payment.")
                            .setNegativeButton("Go back", null)
                            .setPositiveButton("Record split") { _, _ ->
                                mutate(current) { it.recordCashUdharSplit(orderNumber, cashPaise, customerId, "payment-split-${UUID.randomUUID()}", revision) }
                            }
                            .show()
                    }
                }
                dialog.show()
            },
            onAddCustomer = { startActivity(android.content.Intent(this, CustomerCreditActivity::class.java)) }
        )
    }

    private fun splitCashUpiPendingPrompt(current: CounterModel, orderNumber: Int, duePaise: Int, revision: Int) {
        val cashInput = EditText(this).apply {
            hint = "Cash received in rupees"
            contentDescription = "Cash received in rupees"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Cash plus UPI pending")
            .setMessage("Order due ${money(duePaise)}. Enter only the cash physically received. The remaining ${money(duePaise)} minus cash becomes an unverified UPI payment attempt; it is not counted as collected.")
            .setView(cashInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Review split", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cashPaise = runCatching { cashInput.text.toString().trim().toBigDecimal().movePointRight(2).intValueExact() }.getOrNull()
                if (cashPaise == null || cashPaise <= 0 || cashPaise >= duePaise) {
                    status.text = "Cash must be positive and less than the due. No payment was recorded."
                    return@setOnClickListener
                }
                val upiPaise = duePaise - cashPaise
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Confirm split tender")
                    .setMessage("Record ${money(cashPaise)} cash as staff-declared and create a ${money(upiPaise)} UPI pending attempt? The UPI amount is not collected or verified.")
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("Record split") { _, _ ->
                        mutate(current) { it.recordCashUpiPendingSplit(orderNumber, cashPaise, "payment-cash-upi-${UUID.randomUUID()}", revision) }
                    }
                    .show()
            }
        }
        dialog.show()
    }

    private fun mutate(current: CounterModel, action: (CounterModel) -> String) {
        status.text = "Saving payment event…"
        val result = runCatching { action(current) }.getOrElse { "Payment was not recorded (${it.javaClass.simpleName})." }
        status.text = result
        render()
        status.text = result
    }

    private fun money(paise: Int) = String.format(Locale.ROOT, "₹%.2f", paise / 100.0)
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 9, 0, 9) }
    private fun button(text: String, action: () -> Unit) = android.widget.Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object { const val EXTRA_ORDER_NUMBER = "payment_order_number" }
}
