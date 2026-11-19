package com.chaiandtapri.counter

import java.util.concurrent.TimeUnit

/** Age context for open customer credit; this is not a due-date or overdue calculation. */
object CreditAging {
    fun oldestOutstandingOrderCreatedAt(
        credits: List<CustomerCredit>,
        orders: List<CounterOrder>,
        customerId: String
    ): Long? {
        val openOrderNumbers = credits.asSequence()
            .filter { it.customerId == customerId && it.outstandingPaise > 0 }
            .map { it.orderNumber }
            .toSet()
        return orders.asSequence()
            .filter { it.number in openOrderNumbers && !it.voided }
            .map { it.createdAtEpochMs }
            .filter { it > 0L }
            .minOrNull()
    }

    /** Returns elapsed complete 24-hour periods, or null for missing/future timestamps. */
    fun elapsedDays(createdAtEpochMs: Long, nowEpochMs: Long): Long? {
        if (createdAtEpochMs <= 0L || nowEpochMs < createdAtEpochMs) return null
        return TimeUnit.MILLISECONDS.toDays(nowEpochMs - createdAtEpochMs)
    }

    fun description(createdAtEpochMs: Long?, nowEpochMs: Long): String {
        val days = createdAtEpochMs?.let { elapsedDays(it, nowEpochMs) }
        return if (days == null) {
            "Age unavailable: the order date is missing or inconsistent with this device clock. No due date is recorded."
        } else {
            "Oldest open credit: $days ${if (days == 1L) "day" else "days"} since order (device clock). No due date is recorded."
        }
    }
}
