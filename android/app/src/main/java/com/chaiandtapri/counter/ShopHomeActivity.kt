package com.chaiandtapri.counter

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** CT-05 local dashboard. It deliberately reports lifetime local ledger totals, not unverified daily profit. */
class ShopHomeActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private var model: CounterModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 32) }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        model?.close()
        model = null
        super.onDestroy()
    }

    private fun render() {
        model?.close()
        content.removeAllViews()
        try {
            model = CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = true)
            val summary = model!!.localBusinessSummary()
            val profile = model!!.shopProfile()
            val shop = profile.shopName.ifBlank { "Shop not set up" }
            content.addView(label("$shop · Shop Home", 25f))
            content.addView(label("Local encrypted records · lifetime totals on this device · sync not configured", 14f))
            content.addView(label("First-sale setup", 18f))
            content.addView(label("${if (profile.shopName.isNotBlank()) "✓" else "○"} Shop identity${if (profile.shopName.isNotBlank()) " · ${profile.businessType}" else " · needs setup"}", 14f))
            content.addView(label("${if (summary.productCount > 0) "✓" else "○"} Sellable products · ${summary.productCount}", 14f))
            content.addView(label("${if (summary.productCount > 0 && summary.stockConfirmedProductCount == summary.productCount) "✓" else "○"} Physical opening stock · ${summary.stockConfirmedProductCount}/${summary.productCount} products confirmed", 14f))
            if (LocalSessionState.isOwner && profile.shopName.isBlank()) {
                content.addView(button("Set up shop identity") { startActivity(Intent(this, ShopSetupActivity::class.java)) })
            }
            if (LocalSessionState.isOwner && summary.productCount == 0) {
                content.addView(button("Add products and confirm prices") { startActivity(Intent(this, ProductCatalogueActivity::class.java)) })
            }
            if (LocalSessionState.isOwner && summary.productCount > summary.stockConfirmedProductCount) {
                content.addView(button("Confirm physical opening stock") { startActivity(Intent(this, StockCountActivity::class.java)) })
            }
            content.addView(metric("Recorded sales", money(summary.recordedSalesPaise), "Net of recorded refunds; not a daily total"))
            content.addView(metric("Cash recorded", money(summary.cashRecordedPaise), "Staff-declared cash events, net of recorded cash refunds"))
            content.addView(metric("UPI pending", money(summary.upiPendingPaise), "Expected collection only; never marked verified here"))
            content.addView(metric("Customer credit outstanding", money(summary.customerReceivablePaise), "Open Udhar balances from local credit entries"))
            content.addView(metric("Open orders", summary.openOrderCount.toString(), "Orders not yet given or cancelled"))
            content.addView(metric("Sellable products", summary.productCount.toString(), "Active items with an explicitly confirmed price"))
            content.addView(label("Opening stock confirmed for ${summary.stockConfirmedProductCount} of ${summary.productCount} sellable products. Keep counts current; direct purchases are recorded locally.", 14f))
            content.addView(label("Profit is unavailable: cost of goods sold and complete expense coverage are not yet included in this dashboard. No estimate is shown.", 14f))
            content.addView(button("Quick Sale · CT-06") { startActivity(Intent(this, QuickSaleActivity::class.java)) })
            content.addView(button("Live Orders · CT-08") { startActivity(Intent(this, LiveOrdersActivity::class.java)) })
            content.addView(button("Products & Prices · CT-13/14") { startActivity(Intent(this, ProductCatalogueActivity::class.java)) })
            content.addView(button("Shop Profile & Settings · CT-32") { startActivity(Intent(this, ShopSetupActivity::class.java)) })
            content.addView(button("Inventory Overview - CT-15") { startActivity(Intent(this, InventoryOverviewActivity::class.java)) })
            content.addView(button("Customers & Udhar - CT-21/23") { startActivity(Intent(this, CustomerCreditActivity::class.java)) })
            content.addView(button("Suppliers & Purchases - CT-17/20") { startActivity(Intent(this, ProcurementActivity::class.java)) })
            content.addView(button("Expenses - CT-25") { startActivity(Intent(this, ExpensesActivity::class.java)) })
            content.addView(button("Cash Register - CT-26") { startActivity(Intent(this, CashRegisterActivity::class.java)) })
            content.addView(button("Staff Shifts - CT-27") { startActivity(Intent(this, StaffShiftsActivity::class.java)) })
            content.addView(button("Daily Closing Review - CT-28") { startActivity(Intent(this, DailyClosingActivity::class.java)) })
            content.addView(button("Daily Reports - CT-29") { startActivity(Intent(this, DailyReportActivity::class.java)) })
            content.addView(button("Business Alerts - CT-30") { startActivity(Intent(this, BusinessAlertsActivity::class.java)) })
            content.addView(button("Voice Settings - CT-31") { startActivity(Intent(this, VoiceSettingsActivity::class.java)) })
            content.addView(button("Cloud Sync - CAT-052 prototype") { startActivity(Intent(this, CloudSyncActivity::class.java)) })
            content.addView(button("Voice Counter · CT-07") { startActivity(Intent(this, MainActivity::class.java)) })
        } catch (error: Exception) {
            content.addView(label("Shop data could not be opened. No replacement data was created.", 18f))
            content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
            content.addView(button("Retry") { render() })
        }
    }

    private fun metric(title: String, value: String, note: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 14, 18, 14)
        addView(label(title, 15f))
        addView(label(value, 24f))
        addView(label(note, 12f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, 8, 0, 8)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        minHeight = 64
        setOnClickListener { action() }
    }

    private fun money(paise: Long) = "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
}
