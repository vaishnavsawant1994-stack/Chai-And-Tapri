package com.chaiandtapri.counter

/** Privacy-minimized export for one completed drawer session. All amounts are integer paise. */
data class CashClosingCsvData(
    val sessionId: String,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long,
    val openingCashPaise: Long,
    val cashCollectionsPaise: Long,
    val cashRepaymentsPaise: Long,
    val cashRefundsPaise: Long,
    val cashExpensesPaise: Long,
    val cashSupplierSettlementsPaise: Long,
    val manualCashInPaise: Long,
    val manualCashOutPaise: Long,
    val expectedCashPaise: Long,
    val countedCashPaise: Long,
    val variancePaise: Long
)

object CashClosingCsv {
    fun render(data: CashClosingCsvData): String = buildString {
        appendLine("category,metric,value,unit,evidence")
        row("session", "session_id", data.sessionId, "opaque_id", "Local drawer session identifier; timestamps use the device clock.")
        row("session", "opened_at_epoch_ms", data.openedAtEpochMs.toString(), "milliseconds", "Device-recorded timestamp; not server-verified.")
        row("session", "closed_at_epoch_ms", data.closedAtEpochMs.toString(), "milliseconds", "Device-recorded timestamp; not server-verified.")
        row("cash", "opening_float", data.openingCashPaise.toString(), "paise", "Operator-entered opening count.")
        row("cash_in", "order_collections", data.cashCollectionsPaise.toString(), "paise", "Staff-declared cash ledger entries assigned to this session; not independently verified.")
        row("cash_in", "customer_repayments", data.cashRepaymentsPaise.toString(), "paise", "Staff-declared cash repayment entries assigned to this session.")
        row("cash_out", "cash_refunds", data.cashRefundsPaise.toString(), "paise", "Staff-declared cash refund entries assigned to this session; not independently verified.")
        row("cash_out", "operating_expenses", data.cashExpensesPaise.toString(), "paise", "Recorded cash expense entries assigned to this session.")
        row("cash_out", "supplier_settlements", data.cashSupplierSettlementsPaise.toString(), "paise", "Staff-declared cash supplier settlement entries assigned to this session.")
        row("cash_in", "manual_adjustments", data.manualCashInPaise.toString(), "paise", "Recorded manual cash-in events; reasons and free-text notes are omitted.")
        row("cash_out", "manual_adjustments", data.manualCashOutPaise.toString(), "paise", "Recorded manual cash-out events; reasons and free-text notes are omitted.")
        row("reconciliation", "expected_cash", data.expectedCashPaise.toString(), "paise", "Derived from the opening count and recorded session cash events; not a bank balance.")
        row("reconciliation", "physical_count", data.countedCashPaise.toString(), "paise", "Operator-declared physical cash count.")
        row("reconciliation", "unresolved_variance", data.variancePaise.toString(), "paise", "Physical count minus expected cash; export does not clear or adjust the difference.")
    }

    private fun StringBuilder.row(category: String, metric: String, value: String, unit: String, evidence: String) {
        append(listOf(category, metric, value, unit, evidence).joinToString(",") { cell(it) }).append('\n')
    }

    private fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'" + value else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
