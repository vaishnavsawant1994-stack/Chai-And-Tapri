package com.chaiandtapri.counter

/** Inclusive local date bounds in epoch milliseconds. Legacy records with no timestamp never match a selected date. */
internal object OrderHistoryDateRange {
    fun contains(createdAtEpochMs: Long, fromInclusive: Long?, throughInclusive: Long?): Boolean {
        if (fromInclusive == null && throughInclusive == null) return true
        if (createdAtEpochMs <= 0L) return false
        return (fromInclusive == null || createdAtEpochMs >= fromInclusive) &&
            (throughInclusive == null || createdAtEpochMs <= throughInclusive)
    }
}
