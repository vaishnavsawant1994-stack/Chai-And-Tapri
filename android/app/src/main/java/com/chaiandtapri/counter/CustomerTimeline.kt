package com.chaiandtapri.counter

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Presentation helpers for customer account history; never infers a business due date. */
object CustomerTimeline {
    fun newestFirst(events: List<PaymentEntry>): List<PaymentEntry> = events
        .withIndex()
        .sortedWith(compareByDescending<IndexedValue<PaymentEntry>> { it.value.createdAtEpochMs }.thenByDescending { it.index })
        .map { it.value }

    fun recordedTime(
        epochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        if (epochMs <= 0L) return "Date unavailable"
        if (epochMs > nowEpochMs) return "Date unavailable (device clock mismatch)"
        return SimpleDateFormat("dd MMM yyyy, HH:mm", locale).apply { this.timeZone = timeZone }
            .format(Date(epochMs)) + " (device local time)"
    }
}
