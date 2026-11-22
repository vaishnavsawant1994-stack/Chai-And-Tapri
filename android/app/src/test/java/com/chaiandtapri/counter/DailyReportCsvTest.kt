package com.chaiandtapri.counter

import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReportCsvTest {
    @Test fun exportLabelsSelectedLocalDateUndatedRowsAndSeparatesPaymentEvidence() {
        val csv = DailyReportCsv.render(DailyReportCsvData(
            salesPaise = 1250, cashPaise = 1000, pendingUpiPaise = 250, receivablesPaise = 0,
            refundsPaise = 0, cashRefundsPaise = -200, expensesPaise = 300, supplierPayablesPaise = 500,
            deliveredProducts = listOf("Cutting, special" to 2), reportDate = "2026-11-22",
            timeZone = "Asia/Kolkata", undatedOrders = 2, undatedPayments = 3, undatedExpenses = 1
        ))
        assertTrue(csv.contains("selected local calendar date: 2026-11-22"))
        assertTrue(csv.contains("Asia/Kolkata"))
        assertTrue(csv.contains("\"1000\",\"paise\",\"Positive staff-declared cash collection"))
        assertTrue(csv.contains("current_upi_due_on_orders_created_in_period"))
        assertTrue(csv.contains("Current due balance for orders created on the selected date; provider settlement is not verified."))
        assertTrue(csv.contains("current_credit_outstanding_on_orders_created_in_period"))
        assertTrue(csv.contains("\"-200\",\"paise\",\"Staff-declared cash refund outflow"))
        assertTrue(csv.contains("\"2\",\"records\",\"Excluded from date-scoped order totals"))
        assertTrue(csv.contains("\"Cutting, special\""))
        assertTrue(csv.contains("current refund allocation is applied to the original order date"))
        assertTrue(csv.contains("Not profit"))
    }

    @Test fun exportEscapesCsvCellsAndPreventsSpreadsheetFormulaExecution() {
        val csv = DailyReportCsv.render(DailyReportCsvData(
            0, 0, 0, 0, 0, 0, 0, 0, listOf("=HYPERLINK(\"https://example.invalid\")" to 1)
        ))
        assertTrue(csv.contains("'=HYPERLINK(\"\"https://example.invalid\"\")"))
    }
}
