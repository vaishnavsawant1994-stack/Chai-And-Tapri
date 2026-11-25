package com.chaiandtapri.counter

/** Inclusive local date bounds in epoch milliseconds. Legacy records with no timestamp never match a selected date. */
internal object OrderHistoryDateRange {
    enum class Basis { ORDER_CREATION, PAYMENT_EVENT }

    fun contains(createdAtEpochMs: Long, fromInclusive: Long?, throughInclusive: Long?): Boolean {
        if (fromInclusive == null && throughInclusive == null) return true
        if (createdAtEpochMs <= 0L) return false
        return (fromInclusive == null || createdAtEpochMs >= fromInclusive) &&
            (throughInclusive == null || createdAtEpochMs <= throughInclusive)
    }

    fun matches(
        orderCreatedAtEpochMs: Long,
        paymentEventTimesEpochMs: List<Long>,
        fromInclusive: Long?,
        throughInclusive: Long?,
        basis: Basis
    ): Boolean {
        if (fromInclusive == null && throughInclusive == null) return true
        return when (basis) {
        Basis.ORDER_CREATION -> contains(orderCreatedAtEpochMs, fromInclusive, throughInclusive)
        Basis.PAYMENT_EVENT -> paymentEventTimesEpochMs.any { contains(it, fromInclusive, throughInclusive) }
        }
    }
}
