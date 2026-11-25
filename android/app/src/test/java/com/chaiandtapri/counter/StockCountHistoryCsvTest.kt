package com.chaiandtapri.counter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockCountHistoryCsvTest {
    @Test fun exportsExactAndLegacyUnknownQuantitiesWithLocalEvidenceDisclosure() {
        val sessions = listOf(
            StockCountHistorySession("count-1", 123L, "evening shelf count", listOf(
                StockCountHistoryRow("tea", "Tea", 0),
                StockCountHistoryRow("old", "Legacy item", null)
            ), "local-owner")
        )
        val csv = StockCountHistoryCsv.render(sessions, "Asia/Calcutta")

        assertTrue(csv.startsWith("command_id,recorded_at_epoch_ms,device_time_zone"))
        assertTrue(csv.contains("\"123\",\"Asia/Calcutta\",\"tea\",\"Tea\",\"0\",\"recorded physical count\""))
        assertTrue(csv.contains("\"old\",\"Legacy item\",\"\",\"historical quantity unavailable\""))
        assertTrue(csv.contains("\"true\""))
        assertTrue(csv.contains("not independently verified"))
    }

    @Test fun quotesCellsAndNeutralizesSpreadsheetFormulaText() {
        val session = StockCountHistorySession("count-2", 456L, "=HYPERLINK(\"https://example.invalid\"), damaged", listOf(
            StockCountHistoryRow("tea", "Tea, special", 4)
        ), null)
        val csv = StockCountHistoryCsv.render(listOf(session), "UTC")
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"https://example.invalid\"\"), damaged\""))
        assertTrue(csv.contains("\"Tea, special\""))
        assertFalse(csv.contains("\"null\""))
    }
}
