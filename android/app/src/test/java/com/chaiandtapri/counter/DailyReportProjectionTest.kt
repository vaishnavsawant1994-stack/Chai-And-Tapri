package com.chaiandtapri.counter

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyReportProjectionTest {
    @Test fun reportScopesFlowsToSelectedLocalDayAndCountsUndatedLegacyRows() {
        val date = LocalDate.of(2026, 11, 22)
        val zone = ZoneId.of("Asia/Kolkata")
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        fun at(hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val orders = listOf(
            CounterOrder(1, mutableListOf(CounterLine("chai", "Chai", 1, 1500, deliveredQuantity = 1)),
                fulfillment = Fulfillment.GIVEN, payment = Payment.PARTIALLY_PAID, paidPaise = 1000,
                refundedPaise = 200, salePosted = true, createdAtEpochMs = at(9)),
            CounterOrder(2, mutableListOf(CounterLine("samosa", "Samosa", 2, 1000, deliveredQuantity = 2)),
                fulfillment = Fulfillment.GIVEN, payment = Payment.UPI_PENDING, paidPaise = 500,
                salePosted = true, createdAtEpochMs = at(10)),
            CounterOrder(3, mutableListOf(CounterLine("old", "Old", 1, 500, deliveredQuantity = 1)),
                fulfillment = Fulfillment.GIVEN, salePosted = true, createdAtEpochMs = start - 1),
            CounterOrder(4, mutableListOf(CounterLine("legacy", "Legacy", 1, 700, deliveredQuantity = 1)),
                fulfillment = Fulfillment.GIVEN, salePosted = true, createdAtEpochMs = 0)
        )
        val payments = listOf(
            PaymentEntry("cash", 1, null, 1000, "cash", "staff_declared", "order_collection", createdAtEpochMs = at(9)),
            PaymentEntry("refund", 1, null, -200, "cash", "staff_declared_refund", "cash_refund", createdAtEpochMs = at(11)),
            PaymentEntry("upi-pending", 2, null, 1500, "upi", "pending", "order_collection", createdAtEpochMs = at(10)),
            PaymentEntry("old-cash", 3, null, 300, "cash", "staff_declared", "order_collection", createdAtEpochMs = start - 1),
            PaymentEntry("legacy-cash", 4, null, 50, "cash", "staff_declared", "order_collection", createdAtEpochMs = 0)
        )
        val credits = listOf(
            CustomerCredit("credit-today", "customer-1", 1, 500, 0),
            CustomerCredit("credit-yesterday", "customer-2", 3, 800, 0)
        )
        val expenses = listOf(
            BusinessExpense("expense-today", "supplies", 250, "cash", "staff_declared", "", recordedAtEpochMs = at(12)),
            BusinessExpense("expense-yesterday", "rent", 900, "cash", "staff_declared", "", recordedAtEpochMs = start - 1),
            BusinessExpense("expense-legacy", "other", 50, "cash", "staff_declared", "", recordedAtEpochMs = null)
        )

        val report = DailyReportProjectionCalculator.calculate(date, zone, orders, payments, credits, expenses,
            mapOf("chai" to "Chai", "samosa" to "Samosa", "old" to "Old", "legacy" to "Legacy"), 7000)

        assertEquals(3300L, report.salesPaise)
        assertEquals(1000L, report.cashCollectionsPaise)
        assertEquals(1500L, report.pendingUpiPaise)
        assertEquals(500L, report.receivablesPaise)
        assertEquals(200L, report.refundsPaise)
        assertEquals(200L, report.cashRefundsPaise)
        assertEquals(1, report.refundOrders)
        assertEquals(250L, report.expensesPaise)
        assertEquals(7000L, report.supplierPayablesPaise)
        assertEquals(mapOf("Samosa" to 2, "Chai" to 1), report.deliveredProducts.toMap())
        assertEquals(1, report.undatedOrders)
        assertEquals(1, report.undatedPayments)
        assertEquals(1, report.undatedExpenses)
        assertEquals(3, report.undatedRecords)
        assertEquals(start, report.startInclusiveEpochMs)
    }
}
