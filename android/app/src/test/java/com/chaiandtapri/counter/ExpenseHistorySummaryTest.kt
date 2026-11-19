package com.chaiandtapri.counter

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpenseHistorySummaryTest {
    @Test fun groupsRecordedExpensesBySelectedLocalDateAndLeavesUnknownDatesSeparate() {
        val zone = ZoneId.of("Asia/Kolkata")
        val first = ZonedDateTime.of(2026, 11, 19, 0, 15, 0, 0, zone).toInstant().toEpochMilli()
        val sameDay = ZonedDateTime.of(2026, 11, 19, 22, 30, 0, 0, zone).toInstant().toEpochMilli()
        val priorLocalDay = ZonedDateTime.of(2026, 11, 18, 23, 45, 0, 0, zone).toInstant().toEpochMilli()
        val expenses = listOf(
            BusinessExpense("rent-1", "rent", 50_000, "upi", "staff_declared", "", recordedAtEpochMs = first),
            BusinessExpense("utilities-1", "utilities", 1_250, "cash", "staff_declared", "", recordedAtEpochMs = sameDay),
            BusinessExpense("legacy", "other", 500, "cash", "staff_declared", ""),
            BusinessExpense("cleaning-1", "cleaning", 700, "cash", "staff_declared", "", recordedAtEpochMs = priorLocalDay)
        )

        assertEquals(
            listOf(
                DailyExpenseSummary(LocalDate.of(2026, 11, 19), 2, 51_250),
                DailyExpenseSummary(LocalDate.of(2026, 11, 18), 1, 700),
                DailyExpenseSummary(null, 1, 500)
            ),
            ExpenseHistorySummary.daily(expenses, zone)
        )
    }
}
