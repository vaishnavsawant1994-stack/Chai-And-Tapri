package com.chaiandtapri.counter

/** CSV export for the current local report snapshot. It intentionally carries no calendar-day claim. */
data class DailyReportCsvData(
    val salesPaise: Long,
    val cashPaise: Long,
    val pendingUpiPaise: Long,
    val receivablesPaise: Long,
    val refundsPaise: Long,
    val cashRefundsPaise: Long,
    val expensesPaise: Long,
    val supplierPayablesPaise: Long,
    val deliveredProducts: List<Pair<String, Int>>,
    val reportDate: String? = null,
    val timeZone: String? = null,
    val undatedOrders: Int = 0,
    val undatedPayments: Int = 0,
    val undatedExpenses: Int = 0
)

object DailyReportCsv {
    fun render(data: DailyReportCsvData): String = buildString {
        appendLine("category,metric,value,unit,evidence")
        val dateScope = data.reportDate?.let { "selected local calendar date: $it" } ?: "all local records at export time"
        row("scope", "report_scope", dateScope, "", "Device-recorded dates; not server-verified. Some legacy rows may have no date.")
        data.timeZone?.let { row("scope", "device_time_zone", it, "", "Uses the device time zone at export time.") }
        row("sales", "recorded_sales_net_of_current_refund_allocation", data.salesPaise.toString(), "paise", "Orders grouped by creation date; current refund allocation is applied to the original order date. Not profit or bank settlement.")
        row("collections", "cash_staff_declared", data.cashPaise.toString(), "paise", "Positive staff-declared cash collection entries dated in the selected period; not independently verified.")
        row("collections", "current_upi_due_on_orders_created_in_period", data.pendingUpiPaise.toString(), "paise", "Current due balance for orders created on the selected date; provider settlement is not verified.")
        row("receivables", "current_credit_outstanding_on_orders_created_in_period", data.receivablesPaise.toString(), "paise", "Current outstanding balances for orders created on the selected date.")
        row("refunds", "refund_events_recorded", data.refundsPaise.toString(), "paise", "Refund events dated in the selected period; separate from net sales grouped by original order date.")
        row("refunds", "cash_refunds_staff_declared", data.cashRefundsPaise.toString(), "paise", "Staff-declared cash refund outflow dated in the selected period; not externally verified.")
        row("expenses", "operating_expenses_recorded", data.expensesPaise.toString(), "paise", "Expense entries with device-recorded timestamps in the selected period.")
        row("suppliers", "supplier_payables_current_balance", data.supplierPayablesPaise.toString(), "paise", "Current outstanding supplier balance across all dates; not a period expense.")
        row("data_quality", "undated_orders", data.undatedOrders.toString(), "records", "Excluded from date-scoped order totals.")
        row("data_quality", "undated_payment_entries", data.undatedPayments.toString(), "records", "Excluded from date-scoped payment totals.")
        row("data_quality", "undated_expenses", data.undatedExpenses.toString(), "records", "Excluded from date-scoped expense totals.")
        data.deliveredProducts.sortedBy { it.first.lowercase() }.forEach { (name, quantity) ->
            row("products", name, quantity.toString(), "delivered_units", "Gross delivered units from orders created on the selected date.")
        }
    }

    private fun StringBuilder.row(category: String, metric: String, value: String, unit: String, evidence: String) {
        val csvValue = if (value.matches(Regex("-?[0-9]+"))) "\"$value\"" else cell(value)
        append(listOf(cell(category), cell(metric), csvValue, cell(unit), cell(evidence)).joinToString(",")).append('\n')
    }

    private fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'" + value else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
