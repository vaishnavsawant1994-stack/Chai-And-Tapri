package com.chaiandtapri.counter

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.app.DatePickerDialog
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId

/** CT-29 date-scoped local ledger report, with legacy undated records disclosed. */
class DailyReportActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private var status: TextView? = null
    private var selectedDate: LocalDate = LocalDate.now(ZoneId.systemDefault())
    private var exportDate: LocalDate? = null
    @Volatile private var destroyed = false

    private val createReportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) {
            exportDate = null
            status?.text = "CSV export cancelled. No business data was changed."
        } else {
            val date = exportDate ?: selectedDate
            exportDate = null
            exportLatestReport(uri, date)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireManager(this)) return
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
        loadReport()
    }

    private fun loadReport(date: LocalDate = selectedDate) {
        selectedDate = date
        content.removeAllViews()
        content.addView(label("Daily Reports · CT-29", 24f))
        content.addView(label("Loading encrypted local ledger…", 15f))
        status = null
        RecoveryExecutor.executor.execute {
            val result = runCatching { loadReportData(date) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                content.removeAllViews()
                content.addView(label("Daily Reports · CT-29", 24f))
                content.addView(label("Selected business date: $date · device time zone ${ZoneId.systemDefault().id}", 16f))
                content.addView(button("Choose report date") { chooseReportDate() })
                content.addView(label("Date-scoped totals use device-recorded timestamps and are not server-verified. Legacy records without timestamps are excluded and counted below.", 14f))
                result.onSuccess { report ->
                    status = label("", 14f).also(content::addView)
                    section("Activity for $date")
                    metric("Recorded sales, net of current refunds on these orders", money(report.salesPaise))
                    metric("Cash collections recorded by staff", money(report.cashCollectionsPaise))
                    metric("Current UPI due on orders created on this date", money(report.pendingUpiPaise))
                    metric("Refund events recorded on this date (${report.refundOrders} affected orders)", money(report.refundsPaise))
                    metric("Staff-declared cash refunds", money(report.cashRefundsPaise))
                    metric("Operating expenses recorded on this date", money(report.expensesPaise))
                    section("Current balances")
                    metric("Current customer receivables for orders created on this date", money(report.receivablesPaise))
                    metric("Current supplier payables (all dates)", money(report.supplierPayablesPaise))
                    section("Delivered quantities from orders created on this date")
                    if (report.deliveredProducts.isEmpty()) content.addView(label("No delivered product quantities are recorded.", 15f))
                    report.deliveredProducts.forEach { (name, quantity) -> content.addView(label("$name · $quantity units", 15f)) }
                    section("Undated legacy records excluded")
                    metric("Orders", report.undatedOrders.toString())
                    metric("Payment entries", report.undatedPayments.toString())
                    metric("Expenses", report.undatedExpenses.toString())
                    content.addView(label("Sales and delivered units are grouped by order creation date; current refund allocations are applied to those original orders. Refunds and cash collections use their own entry dates. Current balances are point-in-time figures. Profit is unavailable because cost and complete expense attribution are not reliable. This is not a bank settlement, tax report, or daily close.", 14f))
                    content.addView(button("Export current report as CSV") {
                        exportDate = selectedDate
                        createReportLauncher.launch("chai-tapri-report-${selectedDate}.csv")
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

    private fun chooseReportDate() {
        val today = LocalDate.now(ZoneId.systemDefault())
        DatePickerDialog(this, { _, year, month, day ->
            loadReport(LocalDate.of(year, month + 1, day))
        }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).apply {
            datePicker.maxDate = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
            show()
        }
    }

    private fun loadReportData(date: LocalDate): DailyReportProjection = CounterModel(SqliteCounterPersistence(this@DailyReportActivity)).use { model ->
        val products = model.products().associateBy { it.productId }
        val supplierPayables = model.suppliers().sumOf { model.supplierPayablePaise(it.supplierId) }
        DailyReportProjectionCalculator.calculate(date, ZoneId.systemDefault(), model.allOrders(), model.paymentHistory(),
            model.customerCredits(), model.expenseHistory(), products.mapValues { it.value.name }, supplierPayables)
    }

    private fun exportLatestReport(uri: Uri, date: LocalDate) {
        status?.text = "Preparing a current encrypted-ledger snapshot for export…"
        RecoveryExecutor.executor.execute {
            val result = runCatching {
                val report = loadReportData(date)
                val data = DailyReportCsvData(report.salesPaise, report.cashCollectionsPaise, report.pendingUpiPaise,
                    report.receivablesPaise, report.refundsPaise, -report.cashRefundsPaise,
                    report.expensesPaise, report.supplierPayablesPaise, report.deliveredProducts,
                    report.date.toString(), report.zoneId.id, report.undatedOrders, report.undatedPayments, report.undatedExpenses)
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("The selected destination could not be opened.")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(DailyReportCsv.render(data)) }
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                status?.text = result.fold(
                    { "Report for $date exported. The CSV identifies device time zone and undated records." },
                    { error -> "Report export failed (${error.javaClass.simpleName}). No ledger records were changed." }
                )
            }
        }
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

