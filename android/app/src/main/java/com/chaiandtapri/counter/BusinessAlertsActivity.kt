package com.chaiandtapri.counter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** CT-30 alerts derived only from the encrypted local business ledger. */
class BusinessAlertsActivity : Activity() {
    private lateinit var content: LinearLayout
    @Volatile private var destroyed = false
    @Volatile private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        loadAlerts()
    }

    private fun loadAlerts() {
        if (loading || destroyed) return
        loading = true
        content.removeAllViews()
        content.addView(label("Business Alerts · CT-30", 24f))
        content.addView(label("Reading current local records…", 14f))
        RecoveryExecutor.executor.execute {
            val loaded = runCatching {
                CounterModel(SqliteCounterPersistence(this@BusinessAlertsActivity)).use { model ->
                    val summary = model.localBusinessSummary()
                    val products = model.products()
                    val alerts = buildList {
                        model.allOrders().filter { !it.voided && it.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) }
                            .forEach { add(Alert("Open order #${it.number}", "${it.items} · ${it.fulfillment}", false, "Review live orders", LiveOrdersActivity::class.java)) }
                        if (summary.upiPendingPaise > 0) add(Alert("UPI collection pending", money(summary.upiPendingPaise), false, "Review payments", PaymentActivity::class.java))
                        if (summary.customerReceivablePaise > 0) add(Alert("Customer credit outstanding", money(summary.customerReceivablePaise), false, "Review customer credit", CustomerCreditActivity::class.java))
                        products.filter { it.active && it.priceConfirmed && !model.isStockConfirmed(it.productId) }
                            .forEach { add(Alert("Opening stock count required", "${it.name} · quantity is not verified", true, "Review inventory", InventoryOverviewActivity::class.java)) }
                        model.lowStockProducts().forEach { (product, quantity) ->
                            add(Alert("Low stock · ${product.name}", "$quantity units on hand · alert threshold ${product.reorderThreshold} units", true, "Review inventory", InventoryOverviewActivity::class.java))
                        }
                        model.suppliers().forEach { supplier ->
                            val payable = model.supplierPayablePaise(supplier.supplierId)
                            if (payable > 0) add(Alert("Supplier balance outstanding", "${supplier.name} · ${money(payable)}", false, "Review purchases", ProcurementActivity::class.java))
                        }
                        model.activeCashDrawer()?.let { add(Alert("Cash drawer session still open", "Opened ${it.staffLabel?.takeIf(String::isNotBlank) ?: "without an authenticated staff identity"}", true, "Review shift", StaffShiftsActivity::class.java)) }
                        model.closedCashDrawerHistory().firstOrNull()?.let { session ->
                            val expected = model.expectedCashPaise(session.sessionId)
                            val counted = session.closingCashPaise?.toLong()
                            if (expected != null && counted != null && counted != expected) {
                                add(Alert("Cash count difference unresolved", "${money(counted - expected)} · closed drawer session", true, "Review daily closing", DailyClosingActivity::class.java))
                            }
                        }
                    }
                    alerts
                }
            }
            runOnUiThread {
                loading = false
                if (destroyed) return@runOnUiThread
                content.removeAllViews()
                content.addView(label("Business Alerts · CT-30", 24f))
                content.addView(label("Alerts are based on the current encrypted local records. They do not imply employee fault or verified bank settlement.", 14f))
                loaded.onSuccess { alerts ->
                    if (alerts.isEmpty()) content.addView(label("No current local alerts were found.", 17f))
                    alerts.forEach { alert ->
                        content.addView(label("${if (alert.actionRequired) "Action" else "Follow-up"} · ${alert.title}", 17f))
                        content.addView(label(alert.detail, 14f))
                        if (alert.actionLabel != null && alert.destination != null) {
                            content.addView(button(alert.actionLabel) { startActivity(Intent(this, alert.destination)) })
                        }
                    }
                    content.addView(label("Low-stock alerts use only owner-configured thresholds and physically confirmed stock. Overdue-credit and sync alerts are unavailable because due dates and server synchronization are not configured.", 14f))
                    content.addView(button("Refresh") { loadAlerts() })
                    content.addView(button("Live Orders · CT-08") { startActivity(Intent(this, LiveOrdersActivity::class.java)) })
                    content.addView(button("Inventory · CT-15") { startActivity(Intent(this, InventoryOverviewActivity::class.java)) })
                }.onFailure { error ->
                    content.addView(label("Alerts could not be loaded. No business data was changed.", 16f))
                    content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
                    content.addView(button("Retry") { loadAlerts() })
                }
                content.addView(button("Back") { finish() })
            }
        }
    }

    private data class Alert(
        val title: String,
        val detail: String,
        val actionRequired: Boolean,
        val actionLabel: String? = null,
        val destination: Class<out Activity>? = null
    )
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    override fun onDestroy() { destroyed = true; super.onDestroy() }
}

private fun money(paise: Long) = "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
