package com.chaiandtapri.counter

import android.os.Bundle
import android.app.DatePickerDialog
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.text.Editable
import android.text.TextWatcher
import java.util.Locale
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** CT-11: factual local order/payment history with local search and order-creation date filters. */
class SalesHistoryActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private lateinit var orderList: LinearLayout
    private var searchQuery = ""
    private var fromDateStartMillis: Long? = null
    private var throughDateEndMillis: Long? = null
    private lateinit var dateRangeStatus: TextView
    private var model: CounterModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() { super.onResume(); render() }

    override fun onDestroy() { model?.close(); model = null; super.onDestroy() }

    private fun render() {
        model?.close()
        content.removeAllViews()
        try {
            model = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            content.addView(label("Sales & Order History", 25f))
            content.addView(label("Local records only · date range uses order creation date. Payment event dates are not available yet.", 14f))
            val orders = model!!.allOrders()
            val payments = model!!.paymentHistory()
            val search = EditText(this).apply {
                hint = "Search order, item, status or payment"
                setSingleLine(true)
                contentDescription = "Search sales history"
                setText(searchQuery)
            }
            content.addView(search)
            val dateActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            dateActions.addView(button("From date") { pickDate(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            dateActions.addView(button("Through date") { pickDate(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            dateActions.addView(button("Clear dates") { fromDateStartMillis = null; throughDateEndMillis = null; updateDateRange(); showOrders(orders, payments) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(dateActions)
            dateRangeStatus = label("All order dates", 14f)
            content.addView(dateRangeStatus)
            orderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(orderList)
            if (orders.isEmpty()) orderList.addView(label("No orders recorded on this device.", 17f))
            else showOrders(orders, payments)
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    showOrders(orders, payments)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            content.addView(button("Back") { finish() })
        } catch (error: Exception) {
            content.addView(label("Local order history could not be opened. No replacement data was created.", 17f))
            content.addView(label("Storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun showOrders(orders: List<CounterOrder>, payments: List<PaymentEntry>) {
        orderList.removeAllViews()
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        val filtered = orders.filter { order ->
            val events = payments.filter { it.orderNumber == order.number }
            val textMatches = query.isEmpty() || listOf(
                order.number.toString(), order.items, order.fulfillment.name, order.payment.name,
                order.cancelReason.orEmpty(), events.joinToString(" ") { "${it.method} ${it.status} ${it.kind}" }
            ).any { it.lowercase(Locale.ROOT).contains(query) }
            val dateMatches = OrderHistoryDateRange.contains(order.createdAtEpochMs, fromDateStartMillis, throughDateEndMillis)
            textMatches && dateMatches
        }
        if (filtered.isEmpty()) {
            orderList.addView(label(if (orders.isEmpty()) "No orders recorded on this device." else "No orders match the current filters. Legacy orders without a saved date are excluded from date filters.", 17f))
            return
        }
        filtered.forEach { order ->
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 14, 12, 14) }
                card.addView(label("Order #${order.number} · ${order.items}", 18f))
                card.addView(label(if (order.createdAtEpochMs > 0L) "Order date: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(order.createdAtEpochMs))}" else "Order date unavailable · legacy record", 13f))
                card.addView(label("Sale ${money(order.salePaise)} · paid ${money(order.paidPaise)} · Udhar ${money(order.creditPaise)} · refunded ${money(order.refundedPaise)} · due ${money(order.duePaise)}", 14f))
                card.addView(label("${order.fulfillment} / ${order.payment} · revision ${order.revision}${if (order.voided) " · cancelled: ${order.cancelReason.orEmpty()}" else ""}", 13f))
                val events = payments.filter { it.orderNumber == order.number }
                if (events.isNotEmpty()) {
                    val eventText = events.joinToString("\n") { entry ->
                        "${entry.method.uppercase(Locale.ROOT)} ${money(entry.amountPaise)} · ${entry.status} · ${entry.kind}"
                    }
                    card.addView(label("Payment events\n$eventText", 13f))
                }
                card.addView(button("View order details") {
                    startActivity(android.content.Intent(this, OrderDetailsActivity::class.java).putExtra(OrderDetailsActivity.EXTRA_ORDER_NUMBER, order.number))
                })
                card.addView(button("Refund / correct this order") {
                    startActivity(android.content.Intent(this, RefundCorrectionActivity::class.java).putExtra(RefundCorrectionActivity.EXTRA_ORDER_NUMBER, order.number))
                })
                orderList.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun pickDate(isStart: Boolean) {
        val now = Calendar.getInstance()
        val current = if (isStart) fromDateStartMillis else throughDateEndMillis
        if (current != null) now.timeInMillis = current
        DatePickerDialog(this, { _, year, month, day ->
            val selected = Calendar.getInstance().apply {
                set(year, month, day, if (isStart) 0 else 23, if (isStart) 0 else 59, if (isStart) 0 else 59)
                set(Calendar.MILLISECOND, if (isStart) 0 else 999)
            }.timeInMillis
            if (isStart) fromDateStartMillis = selected else throughDateEndMillis = selected
            if (fromDateStartMillis != null && throughDateEndMillis != null && fromDateStartMillis!! > throughDateEndMillis!!) {
                if (isStart) throughDateEndMillis = null else fromDateStartMillis = null
            }
            updateDateRange()
            model?.let { showOrders(it.allOrders(), it.paymentHistory()) }
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateDateRange() {
        if (!::dateRangeStatus.isInitialized) return
        val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val from = fromDateStartMillis?.let { formatter.format(Date(it)) }
        val through = throughDateEndMillis?.let { formatter.format(Date(it)) }
        dateRangeStatus.text = when {
            from == null && through == null -> "All order dates"
            from != null && through != null -> "Orders from $from through $through"
            from != null -> "Orders from $from onward"
            else -> "Orders through $through"
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 60; setOnClickListener { action() } }
    private fun money(paise: Int) = "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
}
