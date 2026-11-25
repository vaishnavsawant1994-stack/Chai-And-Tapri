package com.chaiandtapri.counter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CashClosingCsvTest {
    @Test fun exportUsesPaiseAndLabelsDeclaredCashAndUnresolvedDifference() {
        val csv = CashClosingCsv.render(CashClosingCsvData(
            sessionId = "drawer-opaque-1", openedAtEpochMs = 1000, closedAtEpochMs = 2000,
            openingCashPaise = 5_000, cashCollectionsPaise = 2_500, cashRepaymentsPaise = 300,
            cashRefundsPaise = 100, cashExpensesPaise = 200, cashSupplierSettlementsPaise = 0,
            manualCashInPaise = 50, manualCashOutPaise = 25, expectedCashPaise = 7_525,
            countedCashPaise = 7_400, variancePaise = -125
        ))

        assertTrue(csv.startsWith("category,metric,value,unit,evidence\n"))
        assertTrue(csv.contains("\"order_collections\",\"2500\",\"paise\",\"Staff-declared cash ledger entries"))
        assertTrue(csv.contains("\"expected_cash\",\"7525\",\"paise\""))
        assertTrue(csv.contains("\"physical_count\",\"7400\",\"paise\""))
        assertTrue(csv.contains("\"unresolved_variance\",\"'-125\",\"paise\""))
        assertTrue(csv.contains("does not clear or adjust the difference"))
        assertFalse(csv.contains("customer_name"))
        assertFalse(csv.contains("handover_note"))
    }

    @Test fun exportOmitsFreeTextAndFormulaEscapesSessionIdentifier() {
        val csv = CashClosingCsv.render(CashClosingCsvData(
            sessionId = "=HYPERLINK(\"https://example.invalid\")", openedAtEpochMs = 1, closedAtEpochMs = 2,
            openingCashPaise = 0, cashCollectionsPaise = 0, cashRepaymentsPaise = 0,
            cashRefundsPaise = 0, cashExpensesPaise = 0, cashSupplierSettlementsPaise = 0,
            manualCashInPaise = 0, manualCashOutPaise = 0, expectedCashPaise = 0,
            countedCashPaise = 0, variancePaise = 0
        ))

        assertTrue(csv.contains("'=HYPERLINK"))
        assertFalse(csv.contains("handover"))
        assertFalse(csv.contains("customer_name"))
    }
}
