package com.chaiandtapri.counter

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyExpenseSummary(val localDate: LocalDate?, val recordCount: Int, val totalPaise: Long)

/** Groups locally recorded expenses by device-local calendar date; it does not verify the device clock. */
object ExpenseHistorySummary {
    fun daily(expenses: List<BusinessExpense>, zoneId: ZoneId): List<DailyExpenseSummary> {
        val totals = linkedMapOf<LocalDate?, Pair<Int, Long>>()
        expenses.forEach { expense ->
            val localDate = expense.recordedAtEpochMs?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            val current = totals[localDate] ?: (0 to 0L)
            totals[localDate] = (Math.addExact(current.first, 1)) to Math.addExact(current.second, expense.amountPaise.toLong())
        }
        return totals.map { (date, total) -> DailyExpenseSummary(date, total.first, total.second) }
            .sortedWith(compareByDescending<DailyExpenseSummary> { it.localDate != null }.thenByDescending { it.localDate })
    }
}
