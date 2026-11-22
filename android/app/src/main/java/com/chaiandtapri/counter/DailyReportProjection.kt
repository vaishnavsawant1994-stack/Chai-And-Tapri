package com.chaiandtapri.counter

import java.time.LocalDate
import java.time.ZoneId

/** Date-scoped local report. Dates come from the device clock and are not server-verified. */
data class DailyReportProjection(
    val date: LocalDate,
    val zoneId: ZoneId,
    val salesPaise: Long,
    val cashCollectionsPaise: Long,
    val pendingUpiPaise: Long,
    val receivablesPaise: Long,
    val refundsPaise: Long,
    val refundOrders: Int,
    val cashRefundsPaise: Long,
    val expensesPaise: Long,
    val supplierPayablesPaise: Long,
    val deliveredProducts: List<Pair<String, Int>>,
    val undatedOrders: Int,
    val undatedPayments: Int,
    val undatedExpenses: Int
) {
    val undatedRecords: Int get() = undatedOrders + undatedPayments + undatedExpenses
    val startInclusiveEpochMs: Long get() = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val endExclusiveEpochMs: Long get() = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
}

object DailyReportProjectionCalculator {
    fun calculate(
        date: LocalDate,
        zoneId: ZoneId,
        orders: List<CounterOrder>,
        payments: List<PaymentEntry>,
        credits: List<CustomerCredit>,
        expenses: List<BusinessExpense>,
        productNames: Map<String, String>,
        supplierPayablesPaise: Long
    ): DailyReportProjection {
        val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        fun inDay(timestamp: Long): Boolean = timestamp > 0L && timestamp >= start && timestamp < end

        val datedOrders = orders.filter { inDay(it.createdAtEpochMs) }
        val orderNumbers = datedOrders.mapTo(mutableSetOf()) { it.number }
        val dayPayments = payments.filter { inDay(it.createdAtEpochMs) }
        val dayExpenses = expenses.filter { it.recordedAtEpochMs?.let(::inDay) == true }
        val refunds = dayPayments.filter { it.kind in setOf("cash_refund", "credit_reversal") }
        val sold = datedOrders.asSequence().filterNot { it.voided }
            .flatMap { it.lines.asSequence() }
            .groupBy { it.productId }
            .mapValues { (_, lines) -> lines.sumOf { it.deliveredQuantity } }
            .filterValues { it > 0 }
            .map { (productId, quantity) -> (productNames[productId] ?: productId) to quantity }
            .sortedByDescending { it.second }

        return DailyReportProjection(
            date = date,
            zoneId = zoneId,
            salesPaise = datedOrders.asSequence().filter { it.salePosted && !it.voided }
                .sumOf { (it.salePaise - it.refundedPaise).coerceAtLeast(0).toLong() },
            cashCollectionsPaise = dayPayments.asSequence()
                .filter { it.kind == "order_collection" && it.method == "cash" && it.status.startsWith("staff_declared") && it.amountPaise > 0 }
                .sumOf { it.amountPaise.toLong() },
            pendingUpiPaise = datedOrders.asSequence().filter { !it.voided && it.payment == Payment.UPI_PENDING }
                .sumOf { it.duePaise.toLong() },
            receivablesPaise = credits.asSequence().filter { it.orderNumber in orderNumbers }
                .sumOf { it.outstandingPaise.toLong() }.coerceAtLeast(0),
            refundsPaise = refunds.sumOf { -it.amountPaise.toLong() }.coerceAtLeast(0),
            refundOrders = refunds.mapNotNull { it.orderNumber }.distinct().size,
            cashRefundsPaise = dayPayments.asSequence().filter { it.kind == "cash_refund" && it.method == "cash" }
                .sumOf { it.amountPaise.toLong() }.coerceAtMost(0).let { -it },
            expensesPaise = dayExpenses.sumOf { it.amountPaise.toLong() },
            supplierPayablesPaise = supplierPayablesPaise,
            deliveredProducts = sold,
            undatedOrders = orders.count { it.createdAtEpochMs <= 0L },
            undatedPayments = payments.count { it.createdAtEpochMs <= 0L },
            undatedExpenses = expenses.count { it.recordedAtEpochMs == null || it.recordedAtEpochMs <= 0L }
        )
    }
}
