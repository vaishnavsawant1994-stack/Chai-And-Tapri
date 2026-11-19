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
    val deliveredProducts: List<Pair<String, Int>>
)

object DailyReportCsv {
    fun render(data: DailyReportCsvData): String = buildString {
        appendLine("category,metric,value,unit,evidence")
        row("scope", "report_scope", "all local records at export time", "", "Not a calendar-day report; records may be unsynchronized.")
        row("sales", "recorded_sales_net_of_refunds", data.salesPaise.toString(), "paise", "Local ledger total; not profit or bank settlement.")
        row("collections", "cash_staff_declared", data.cashPaise.toString(), "paise", "Staff-declared cash; not independently verified.")
        row("collections", "upi_pending", data.pendingUpiPaise.toString(), "paise", "Pending attempts only; not a successful collection.")
        row("receivables", "customer_credit_outstanding", data.receivablesPaise.toString(), "paise", "Outstanding local customer-credit ledger.")
        row("refunds", "recorded_refunds", data.refundsPaise.toString(), "paise", "Recorded refund allocations.")
        row("refunds", "cash_refunds_staff_declared", data.cashRefundsPaise.toString(), "paise", "Staff-declared cash outflow; not externally verified.")
        row("expenses", "operating_expenses_recorded", data.expensesPaise.toString(), "paise", "Recorded local expense entries.")
        row("suppliers", "supplier_payables_outstanding", data.supplierPayablesPaise.toString(), "paise", "Outstanding supplier balance; not a current-period expense.")
        data.deliveredProducts.sortedBy { it.first.lowercase() }.forEach { (name, quantity) ->
            row("products", name, quantity.toString(), "delivered_units", "Gross delivered units; refund allocation is reported separately.")
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
