package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** CT-20 supplier profile assembled from the existing encrypted purchase/payable ledgers. */
class SupplierDetailsActivity : Activity() {
    private var model: CounterModel? = null
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    private fun render() {
        model?.close()
        content.removeAllViews()
        val current = runCatching { CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true) }.getOrElse {
            content.addView(label("Supplier records could not be opened. Existing data was preserved.", 18f))
            content.addView(button("Back") { finish() })
            return
        }
        model = current
        val supplierId = intent.getStringExtra(EXTRA_SUPPLIER_ID).orEmpty()
        val supplier = current.suppliers().firstOrNull { it.supplierId == supplierId }
        if (supplier == null) {
            content.addView(label("Supplier not found in this shop.", 20f))
            content.addView(button("Back") { finish() })
            return
        }

        val receipts = current.purchaseHistory().filter { it.supplierId == supplierId }.asReversed()
        val payments = current.supplierPaymentHistory().filter { it.supplierId == supplierId }.asReversed()
        val returns = current.purchaseReturnHistory().filter { it.supplierId == supplierId }.asReversed()
        val orders = current.purchaseOrderDrafts().filter { it.supplierId == supplierId }

        content.addView(label(supplier.name, 26f))
        content.addView(label("Supplier ID: ${supplier.supplierId}", 14f))
        content.addView(label("Business contact: ${supplier.phone ?: "Not provided"}", 16f))
        content.addView(button("Edit supplier profile") { editSupplier(supplier.supplierId, supplier.name, supplier.phone) })
        content.addView(label("Current payable: ${money(current.supplierPayablePaise(supplierId))}", 22f))
        content.addView(label("This balance is calculated from locally recorded receipts, returns, and staff-declared payments.", 14f))

        content.addView(label("Purchase orders", 19f))
        if (orders.isEmpty()) content.addView(label("No purchase orders recorded.", 14f))
        orders.forEach { order ->
            val received = current.purchaseOrderReceivedQuantities(order.purchaseOrderId)
            val lines = order.lines.joinToString { line -> "${line.productName}: ${received[line.productId] ?: 0}/${line.orderedQuantity} received" }
            content.addView(label("${order.purchaseOrderId} · ${current.purchaseOrderState(order)} · estimate ${money(order.totalPaise)}\n$lines", 14f))
        }

        content.addView(label("Receipts", 19f))
        if (receipts.isEmpty()) content.addView(label("No stock receipts recorded.", 14f))
        receipts.forEach { receipt ->
            val lines = receipt.lines.joinToString { "${it.quantity} × ${it.productName} @ ${money(it.lineCostPaise)}" }
            val origin = receipt.purchaseOrderId?.let { " · order $it" }.orEmpty()
            val invoice = receipt.supplierInvoiceReference?.let { " · invoice reference $it" }.orEmpty()
            content.addView(label("$lines\nReceipt ${receipt.receiptId} · ${money(receipt.totalPaise)}$origin$invoice", 14f))
        }

        content.addView(label("Supplier payments", 19f))
        if (payments.isEmpty()) content.addView(label("No payments recorded.", 14f))
        payments.forEach { payment ->
            val evidence = if (payment.method == "upi") "Staff-declared UPI · not provider-verified" else "Staff-declared cash"
            content.addView(label("${money(payment.amountPaise.toLong())} · $evidence · ${payment.paymentId}", 14f))
        }

        content.addView(label("Purchase returns", 19f))
        if (returns.isEmpty()) content.addView(label("No returns recorded.", 14f))
        returns.forEach { purchaseReturn ->
            val lines = purchaseReturn.lines.joinToString { "${it.quantity} × ${it.productName}" }
            content.addView(label("$lines · payable reduction ${money(purchaseReturn.totalPaise)} · receipt ${purchaseReturn.receiptId}", 14f))
        }
        content.addView(label("History is limited to records stored on this device. Payment entries are staff-declared; no supplier bank or UPI provider verification is connected.", 13f))
        content.addView(button("Back to suppliers") { finish() })
    }

    private fun editSupplier(id: String, currentName: String, currentPhone: String?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val name = EditText(this).apply { hint = "Supplier / dealer name"; setSingleLine(true); setText(currentName) }
        val phone = EditText(this).apply { hint = "Business phone (optional)"; inputType = InputType.TYPE_CLASS_PHONE; setSingleLine(true); setText(currentPhone.orEmpty()) }
        form.addView(name); form.addView(phone)
        AlertDialog.Builder(this).setTitle("Edit supplier profile").setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val result = model?.saveSupplier(id, name.text.toString(), phone = phone.text.toString()) ?: "Storage is unavailable."
                        if (result.startsWith("Supplier saved")) { dialog.dismiss(); render() }
                        else android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                dialog.show()
            }
    }

    private fun money(paise: Long) = String.format(Locale.ROOT, "₹%d.%02d", paise / 100, paise % 100)
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = android.widget.Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }

    companion object { const val EXTRA_SUPPLIER_ID = "supplier_id" }
}
