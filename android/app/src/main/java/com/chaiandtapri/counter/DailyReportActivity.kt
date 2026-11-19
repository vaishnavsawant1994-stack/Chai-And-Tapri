package com.chaiandtapri.counter

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** CT-29 local ledger report. Totals are lifetime-only until trusted timestamps/date attribution are available. */
class DailyReportActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private var status: TextView? = null
    @Volatile private var destroyed = false

    private val createReportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) {
            status?.text = "CSV export cancelled. No business data was changed."
        } else {
            exportLatestReport(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        loadReport()
    }

    private fun loadReport() {
        content.removeAllViews()
        content.addView(label("Daily Reports · CT-29", 24f))
        content.addView(label("Loading encrypted local ledger…", 15f))
        status = null
        RecoveryExecutor.executor.execute {
            val result = runCatching { loadReportData() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                content.removeAllViews()
                content.addView(label("Daily Reports · CT-29", 24f))
                content.addView(label("Scope: all records on this device. Reliable calendar-day filtering is not available because trusted transaction dates are not persisted consistently.", 14f))
                result.onSuccess { report ->
                    status = label("", 14f).also(content::addView)
                    section("Collections and balances")
                    metric("Recorded sales, net of refunds", report.sales)
                    metric("Cash recorded by staff", report.cash)
                    metric("UPI pending", report.upiPending)
                    metric("Customer receivables", report.receivables)
                    section("Other local ledger totals")
                    metric("Recorded refunds (${report.refundOrders} affected orders)", report.refunds)
                    metric("Cash refunds recorded", report.cashRefunds)
                    metric("Operating expenses", report.expenses)
                    metric("Supplier payables", report.supplierPayables)
                    section("Delivered quantities (gross of refund allocation)")
                    if (report.products.isEmpty()) content.addView(label("No delivered product quantities are recorded.", 15f))
                    report.products.forEach { (name, quantity) -> content.addView(label("$name · $quantity units", 15f)) }
                    content.addView(label("Profit is unavailable: product cost and complete expense attribution are not reliable. These lifetime local totals are not a daily close, bank settlement, or tax report.", 14f))
                    content.addView(button("Export current report as CSV") {
                        createReportLauncher.launch("chai-tapri-report-${java.time.LocalDate.now()}.csv")
                    })
                }.onFailure { error ->
                    content.addView(label("Report could not be loaded. No business data was changed.", 16f))
                    content.addView(label("Local storage error: ${error.javaClass.simpleName}", 14f))
                    content.addView(button("Retry") { loadReport() })
                }
                content.addView(button("Sales History · CT-11") { startActivity(Intent(this, SalesHistoryActivity::class.java)) })
                content.addView(button("Back") { finish() })
            }
        }
    }

    private fun loadReportData(): ReportData = CounterModel(SqliteCounterPersistence(this@DailyReportActivity)).use { model ->
        val summary = model.localBusinessSummary()
        val orders = model.allOrders()
        val products = model.products().associateBy { it.productId }
        val sold = orders.asSequence().filterNot { it.voided }.flatMap { it.lines.asSequence() }
            .groupBy { it.productId }
            .mapValues { (_, lines) -> lines.sumOf { it.deliveredQuantity } }
            .filterValues { it > 0 }
            .toList().sortedByDescending { it.second }
        val cashRefunds = model.paymentHistory().filter { it.method == "cash" && it.kind == "cash_refund" }.sumOf { it.amountPaise.toLong() }
        val refunds = orders.sumOf { it.refundedPaise.toLong() }
        val expenses = model.totalExpensesPaise()
        val supplierPayables = model.suppliers().sumOf { model.supplierPayablePaise(it.supplierId) }
        ReportData(summary.recordedSalesPaise, summary.cashRecordedPaise, summary.upiPendingPaise,
            summary.customerReceivablePaise, orders.count { it.refundedPaise > 0 }, refunds,
            cashRefunds, expenses, supplierPayables, sold.map { (id, quantity) ->
                val product = products[id]
                (product?.name ?: id) to quantity
            })
    }

    private fun exportLatestReport(uri: Uri) {
        status?.text = "Preparing a current encrypted-ledger snapshot for export…"
        RecoveryExecutor.executor.execute {
            val result = runCatching {
                val report = loadReportData()
                val data = DailyReportCsvData(report.salesPaise, report.cashPaise, report.upiPendingPaise,
                    report.receivablesPaise, report.refundsPaise, report.cashRefundsPaise,
                    report.expensesPaise, report.supplierPayablesPaise, report.products)
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("The selected destination could not be opened.")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(DailyReportCsv.render(data)) }
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                status?.text = result.fold(
                    { "Report exported. The file contains all local records, not a date-filtered report." },
                    { error -> "Report export failed (${error.javaClass.simpleName}). No ledger records were changed." }
                )
            }
        }
    }

    private data class ReportData(
        val salesPaise: Long, val cashPaise: Long, val upiPendingPaise: Long, val receivablesPaise: Long,
        val refundOrders: Int, val refundsPaise: Long, val cashRefundsPaise: Long, val expensesPaise: Long,
        val supplierPayablesPaise: Long, val products: List<Pair<String, Int>>
    ) {
        val sales get() = money(salesPaise)
        val cash get() = money(cashPaise)
        val upiPending get() = money(upiPendingPaise)
        val receivables get() = money(receivablesPaise)
        val refunds get() = money(refundsPaise)
        val cashRefunds get() = money(cashRefundsPaise)
        val expenses get() = money(expensesPaise)
        val supplierPayables get() = money(supplierPayablesPaise)
    }

    private fun section(text: String) { content.addView(label(text, 19f)) }
    private fun metric(title: String, amount: String) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            addView(label(title, 14f)); addView(label(amount, 21f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; minHeight = 56; setOnClickListener { action() } }
    override fun onDestroy() { destroyed = true; super.onDestroy() }
}

private fun money(paise: Long) = "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

