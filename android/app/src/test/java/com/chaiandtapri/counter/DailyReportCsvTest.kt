package com.chaiandtapri.counter

import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReportCsvTest {
    @Test fun exportLabelsLifetimeScopeAndSeparatesPaymentEvidence() {
        val csv = DailyReportCsv.render(DailyReportCsvData(
            salesPaise = 1250, cashPaise = 1000, pendingUpiPaise = 250, receivablesPaise = 0,
            refundsPaise = 0, cashRefundsPaise = -200, expensesPaise = 300, supplierPayablesPaise = 500,
            deliveredProducts = listOf("Cutting, special" to 2)
        ))
        assertTrue(csv.contains("all local records at export time"))
        assertTrue(csv.contains("\"1000\",\"paise\",\"Staff-declared cash"))
        assertTrue(csv.contains("\"250\",\"paise\",\"Pending attempts only"))
        assertTrue(csv.contains("\"-200\",\"paise\",\"Staff-declared cash outflow"))
        assertTrue(csv.contains("\"Cutting, special\""))
        assertTrue(csv.contains("Not a calendar-day report"))
        assertTrue(csv.contains("not profit"))
    }

    @Test fun exportEscapesCsvCellsAndPreventsSpreadsheetFormulaExecution() {
        val csv = DailyReportCsv.render(DailyReportCsvData(
            0, 0, 0, 0, 0, 0, 0, 0, listOf("=HYPERLINK(\"https://example.invalid\")" to 1)
        ))
        assertTrue(csv.contains("'=HYPERLINK(\"\"https://example.invalid\"\")"))
    }
}
