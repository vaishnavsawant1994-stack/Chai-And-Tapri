package com.chaiandtapri.counter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesHistoryCsvTest {
    @Test fun exportsSelectedOrderAndTenderEvidenceWithoutCustomerIdentity() {
        val order = CounterOrder(17, mutableListOf(CounterLine("tea", "Cutting, special", 2, 1000, deliveredQuantity = 2)),
            fulfillment = Fulfillment.GIVEN, payment = Payment.UPI_PENDING, paidPaise = 500, createdAtEpochMs = 123456L)
        val csv = SalesHistoryCsv.render(listOf(SalesHistoryCsvRow(order, listOf(
            PaymentEntry("entry-1", 17, null, 500, "upi", "upi_pending", "order_collection")
        ), mapOf("tea" to "cup"))))

        assertTrue(csv.startsWith("order_number,created_at_epoch_ms,items,"))
        assertTrue(csv.contains("\"123456\""))
        assertTrue(csv.contains("\"2 cup × Cutting, special\""))
        assertTrue(csv.contains("upi 500 paise upi_pending order_collection"))
        assertTrue(csv.contains("not provider-verified"))
        assertFalse(csv.contains("customer_id"))
        assertFalse(csv.contains("entry-1"))
    }

    @Test fun csvQuotesCellsAndNeutralizesSpreadsheetFormulas() {
        val order = CounterOrder(1, mutableListOf(CounterLine("x", "Tea", 1, 100)),
            cancelReason = "=HYPERLINK(\"https://example.invalid\"), damaged")
        val csv = SalesHistoryCsv.render(listOf(SalesHistoryCsvRow(order, emptyList())))
        assertTrue(csv.contains("'=HYPERLINK(\"\"https://example.invalid\"\"), damaged"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"https://example.invalid\"\"), damaged\""))
    }

    @Test fun legacyOrdersClearlyShowWhenTheirUnitWasNotRecorded() {
        val order = CounterOrder(2, mutableListOf(CounterLine("legacy_tea", "Legacy Tea", 1, 100)))

        assertTrue(formatSalesHistoryItems(order, emptyMap()).contains("1 unit not recorded × Legacy Tea"))
        assertTrue(SalesHistoryCsv.render(listOf(SalesHistoryCsvRow(order, emptyList())))
            .contains("1 unit not recorded × Legacy Tea"))
    }
}
