package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/** CT-09 read-only detail view assembled from the encrypted local transaction ledgers. */
class OrderDetailsActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireCounterAccess(this)) return
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
            val number = intent.getIntExtra(EXTRA_ORDER_NUMBER, -1)
            val order = current.allOrders().firstOrNull { it.number == number }
            if (order == null) {
                content.addView(label("Order not found", 22f))
                content.addView(label("It may have been removed from this local dataset. No changes were made.", 14f))
                content.addView(button("Back") { finish() })
                return
            }

            content.addView(label("Order #${order.number}", 25f))
            message?.let { content.addView(label(it, 14f)) }
            content.addView(label("Fulfillment: ${order.fulfillment} · Payment: ${order.payment}", 16f))
            content.addView(label("Revision ${order.revision} · ${if (order.voided) "Cancelled" else if (order.salePosted) "Sale posted" else "Not yet handed over"}", 14f))

            content.addView(label("Items", 19f))
            if (!order.voided && order.fulfillment in setOf(Fulfillment.TAKEN, Fulfillment.PREPARING, Fulfillment.READY)) {
                content.addView(button("Add item to order") {
                    chooseProductToAdd(order.number, order.revision, order.totalPaise)
                })
            }
            if (LocalSessionState.isManager && !order.voided && order.fulfillment in setOf(Fulfillment.TAKEN, Fulfillment.PREPARING, Fulfillment.READY, Fulfillment.PARTIALLY_GIVEN)) {
                content.addView(button("Cancel unhanded remainder") {
                    confirmCancelUnhandedRemainder(order.number, order.revision, order.fulfillment == Fulfillment.PARTIALLY_GIVEN)
                })
            }
            val priceEditable = LocalSessionState.isManager && !order.voided &&
                order.fulfillment in setOf(Fulfillment.TAKEN, Fulfillment.PREPARING, Fulfillment.READY) &&
                order.lines.all { it.deliveredQuantity == 0 } && order.paidPaise == 0 && order.creditPaise == 0 &&
                order.refundedPaise == 0 && current.paymentHistory().none { it.orderNumber == order.number }
            order.lines.forEach { line ->
                val unit = current.products().firstOrNull { it.productId == line.productId }?.unitLabel ?: "units"
                content.addView(label("${line.name} · ordered ${line.quantity} $unit · handed over ${line.deliveredQuantity} · returned ${line.refundedQuantity}", 15f))
                content.addView(label("Unit ${money(line.unitPricePaise)} · delivered value ${money(line.deliveredQuantity * line.unitPricePaise)}", 13f))
                if (priceEditable) content.addView(button("Adjust price for ${line.name}") {
                    confirmUnitPriceChange(order.number, line.productId, line.name, line.unitPricePaise, order.revision)
                })
                val remaining = line.quantity - line.deliveredQuantity
                if (LocalSessionState.isManager && !order.voided && remaining > 0 && order.fulfillment in setOf(Fulfillment.TAKEN, Fulfillment.PREPARING, Fulfillment.READY, Fulfillment.PARTIALLY_GIVEN)) {
                    content.addView(button("Correct unhanded quantity · ${line.name}") {
                        confirmRemoveUnhanded(order.number, line.productId, line.name, remaining, order.revision)
                    })
                }
            }

            content.addView(label("Financial summary", 19f))
            content.addView(label("Delivered sale: ${money(order.salePaise)}", 15f))
            content.addView(label("Collections recorded: ${money(order.paidPaise)}", 15f))
            content.addView(label("Customer credit: ${money(order.creditPaise)} · credit reversed: ${money(order.creditReversedPaise)}", 15f))
            content.addView(label("Refunded: ${money(order.refundedPaise)} · cash refund recorded: ${money(order.cashRefundedPaise)}", 15f))
            content.addView(label("Outstanding due: ${money(order.duePaise)}", 17f))
            order.cancelReason?.let { content.addView(label("Correction / cancellation reason: $it", 14f)) }

            content.addView(label("Payment and credit events", 19f))
            val payments = current.paymentHistory().filter { it.orderNumber == number }
            if (payments.isEmpty()) content.addView(label("No payment events recorded.", 14f))
            payments.forEach { payment ->
                content.addView(label("${payment.kind} · ${payment.method.uppercase(Locale.ROOT)} · ${money(payment.amountPaise)} · ${payment.status}${payment.relatedEntryId?.let { " · related $it" }.orEmpty()}", 14f))
            }
            val credits = current.customerCredits().filter { it.orderNumber == number }
            credits.forEach { credit -> content.addView(label("Customer ${credit.customerId} · outstanding ${money(credit.outstandingPaise)}", 14f)) }

            content.addView(label("Inventory movements", 19f))
            val movements = current.stockMovements().filter { it.orderNumber == number }
            if (movements.isEmpty()) content.addView(label("No order-linked stock movements recorded.", 14f))
            movements.forEach { movement ->
                val unit = current.products().firstOrNull { it.productId == movement.productId }?.unitLabel ?: "units"
                content.addView(label("${movement.productId} · ${if (movement.quantityDelta > 0) "+" else ""}${movement.quantityDelta} $unit · ${movement.reason}", 14f))
            }

            content.addView(label("Audit events", 19f))
            val orderMarker = Regex("(?<![0-9])#${Regex.escape(number.toString())}(?![0-9])")
            val audit = current.auditRecords().filter { orderMarker.containsMatchIn(it.event) }
            if (audit.isEmpty()) content.addView(label("No matching order audit events were found.", 14f))
            val localTime = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
            audit.forEach { record ->
                val timestamp = record.createdAtEpochMs.takeIf { it > 0L }?.let { localTime.format(Date(it)) } ?: "Time not recorded"
                content.addView(label("Local time: $timestamp · ${record.event}", 13f))
            }
            content.addView(label("Times use this device's clock and may be inaccurate if its clock was wrong. They are local audit timestamps, not server-verified times.", 12f))
            content.addView(button("Back to live orders") { finish() })
        } catch (error: Exception) {
            content.addView(label("Order details could not be opened. No replacement data was created.", 18f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun money(paise: Int) = "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

    private fun chooseProductToAdd(orderNumber: Int, expectedRevision: Int, currentTotalPaise: Int) {
        if (!LocalSessionState.authenticated) {
            render("Sign in before changing an order. No change was made.")
            return
        }
        val sellableProducts = model?.products()?.filter { it.active && it.priceConfirmed && it.unitPricePaise > 0 }
            ?.sortedWith(compareBy<TapriProduct> { it.category.lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) })
            .orEmpty()
        if (sellableProducts.isEmpty()) {
            render("There are no active products with owner-confirmed prices. No change was made.")
            return
        }
        AlertDialog.Builder(this)
            // AlertDialog has a single content panel: setting both a message and
            // a list hides the list on Android. Keep the order context in the
            // title so the configured products remain selectable.
            .setTitle("Add item to order #$orderNumber · current ${money(currentTotalPaise)}")
            .setItems(sellableProducts.map { "${it.name} · ${money(it.unitPricePaise)}" }.toTypedArray()) { _, index ->
                val selected = sellableProducts[index]
                confirmAddItem(orderNumber, selected, expectedRevision)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmAddItem(orderNumber: Int, product: TapriProduct, expectedRevision: Int) {
        if (!LocalSessionState.authenticated) {
            render("Sign in before changing an order. No change was made.")
            return
        }
        val picker = NumberPicker(this).apply { minValue = 1; maxValue = 100; value = 1; wrapSelectorWheel = false }
        val pickerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(label("Quantity · ${product.name} at ${money(product.unitPricePaise)} each", 14f))
            addView(picker)
        }
        AlertDialog.Builder(this)
            .setTitle("Confirm item for order #$orderNumber")
            .setMessage("The item is not handed over and no payment is recorded by this action.")
            .setView(pickerContainer)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add item") { _, _ ->
                if (!LocalSessionState.authenticated) {
                    render("Sign in before changing an order. No change was made.")
                    return@setPositiveButton
                }
                val result = model?.apply(
                    "order-line-add-${java.util.UUID.randomUUID()}",
                    ProposedCommand("add", orderNumber, "Add item from order details", product.productId, picker.value, expectedRevision = expectedRevision)
                ) ?: "Order details are unavailable. No change was made."
                render(result)
            }
            .show()
    }

    private fun confirmRemoveUnhanded(orderNumber: Int, productId: String, productName: String, remaining: Int, expectedRevision: Int) {
        if (!LocalSessionState.isManager) {
            render("Manager access is required to correct an order. No change was made.")
            return
        }
        val picker = NumberPicker(this).apply { minValue = 1; maxValue = remaining; value = remaining; wrapSelectorWheel = false }
        val pickerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(label("Quantity to remove (not yet handed over)", 14f))
            addView(picker)
        }
        val reason = EditText(this).apply {
            hint = "Reason for correction (3-120 characters)"
            contentDescription = "Correction reason"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        pickerContainer.addView(reason)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Correct order #$orderNumber")
            .setMessage("Remove unhanded $productName? Already handed-over items, recorded payments and stock movements will not be changed. Enter a short reason for the audit record. Do not include personal information.")
            .setView(pickerContainer)
            .setNegativeButton("Keep order", null)
            .setPositiveButton("Remove quantity", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val auditReason = reason.text.toString().trim()
                if (auditReason.length !in 3..120 || auditReason.any(Char::isISOControl)) {
                    reason.error = "Enter a short reason (3-120 characters)"
                    return@setOnClickListener
                }
                if (!LocalSessionState.isManager) {
                    render("Manager access is required to correct an order. No change was made.")
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val result = model?.apply(
                    "order-line-correction-${java.util.UUID.randomUUID()}",
                    ProposedCommand("remove", orderNumber, "Remove unhanded order quantity", productId, picker.value,
                        expectedRevision = expectedRevision, auditReason = auditReason)
                ) ?: "Order details are unavailable. No change was made."
                dialog.dismiss()
                render(result)
            }
        }
        dialog.show()
    }

    private fun confirmCancelUnhandedRemainder(orderNumber: Int, expectedRevision: Int, hasDeliveredItems: Boolean) {
        if (!LocalSessionState.isManager) {
            render("Manager access is required to cancel an order. No change was made.")
            return
        }
        val reason = EditText(this).apply {
            hint = "Reason for cancellation (3-120 characters)"
            contentDescription = "Cancellation reason"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        val deliveredPolicy = if (hasDeliveredItems) "Previously handed-over items remain sold; their payments, credit and stock movements are unchanged." else "No item has been handed over, so this records no sale, payment or stock reversal."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Cancel unhanded remainder · Order #$orderNumber")
            .setMessage("This stops the remaining items from being handed over. $deliveredPolicy No refund is issued by this action. Enter a short reason for the audit record; do not include personal information.")
            .setView(reason)
            .setNegativeButton("Keep order", null)
            .setPositiveButton("Review cancellation", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = reason.text.toString().trim()
                if (value.length !in 3..120 || value.any(Char::isISOControl)) {
                    reason.error = "Enter a short reason (3-120 characters)"
                    return@setOnClickListener
                }
                if (!LocalSessionState.isManager) {
                    dialog.dismiss()
                    render("Manager access is required to cancel an order. No change was made.")
                    return@setOnClickListener
                }
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Confirm order cancellation")
                    .setMessage("Cancel only the unhanded remainder of order #$orderNumber? $deliveredPolicy")
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("Record cancellation") { _, _ ->
                        if (!LocalSessionState.isManager) {
                            render("Manager access is required to cancel an order. No change was made.")
                            return@setPositiveButton
                        }
                        val result = model?.cancelOrder(orderNumber, value, "order-cancel-${java.util.UUID.randomUUID()}", expectedRevision)
                            ?: "Order details are unavailable. No change was made."
                        render(result)
                    }
                    .show()
            }
        }
        dialog.show()
    }

    private fun confirmUnitPriceChange(orderNumber: Int, productId: String, productName: String, currentPricePaise: Int, expectedRevision: Int) {
        if (!LocalSessionState.isManager) {
            render("Manager access is required to adjust an order price. No change made.")
            return
        }
        val price = EditText(this).apply {
            hint = "New unit price in rupees"
            contentDescription = "New unit price"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(String.format(Locale.ROOT, "%.2f", currentPricePaise / 100.0))
        }
        val reason = EditText(this).apply {
            hint = "Reason for price correction (3-120 characters)"
            contentDescription = "Price correction reason"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(label("Current ${money(currentPricePaise)} each; order #$orderNumber has not been handed over or paid", 14f))
            addView(price)
            addView(reason)
        }
        val review = AlertDialog.Builder(this)
            .setTitle("Adjust $productName")
            .setMessage("This changes the full unhanded order line and recalculates its total. It does not change the catalogue, stock, payments, or customer credit.")
            .setView(form)
            .setNegativeButton("Keep current price", null)
            .setPositiveButton("Review price", null)
            .create()
        review.setOnShowListener {
            review.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!LocalSessionState.isManager) {
                    review.dismiss()
                    render("Manager access is required to adjust an order price. No change made.")
                    return@setOnClickListener
                }
                val parsedPrice = runCatching {
                    java.math.BigDecimal(price.text.toString().trim())
                        .setScale(2, java.math.RoundingMode.UNNECESSARY)
                        .movePointRight(2)
                        .intValueExact()
                }.getOrNull()
                if (parsedPrice == null || parsedPrice !in 1..10_000_000) {
                    price.error = "Enter a positive price with up to two decimal places"
                    return@setOnClickListener
                }
                val note = reason.text.toString().trim()
                if (note.length !in 3..120 || note.any(Char::isISOControl)) {
                    reason.error = "Enter a short reason (3-120 characters)"
                    return@setOnClickListener
                }
                review.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Confirm price change")
                    .setMessage("$productName: ${money(currentPricePaise)} to ${money(parsedPrice)} each. The full order total will be recalculated. Reason: $note")
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("Record price change") { _, _ ->
                        if (!LocalSessionState.isManager) {
                            render("Manager access is required to adjust an order price. No change was made.")
                            return@setPositiveButton
                        }
                        val result = model?.adjustUnhandedUnitPrice(
                            orderNumber, productId, parsedPrice, note,
                            "order-price-${java.util.UUID.randomUUID()}", expectedRevision
                        ) ?: "Order details are unavailable. No change was made."
                        render(result)
                    }
                    .show()
            }
        }
        review.show()
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }

    companion object { const val EXTRA_ORDER_NUMBER = "order_number" }
}
