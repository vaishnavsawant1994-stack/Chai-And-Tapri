package com.chaiandtapri.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StockCountReviewTest {
    private val tea = TapriProduct("tea", "Tea", "Beverages", 1000, active = true)

    @Test fun knownBaselineShowsPositiveNegativeAndZeroVariance() {
        val rows = StockCountReview.build(listOf(tea), mapOf("tea" to 3), mapOf("tea" to 5), setOf("tea"))
        assertEquals(5, rows.single().previousQuantity)
        assertEquals(3, rows.single().countedQuantity)
        assertEquals(-2, rows.single().variance)
        assertEquals(0, StockCountReview.build(listOf(tea), mapOf("tea" to 0), mapOf("tea" to 0), setOf("tea")).single().variance)
        assertEquals(3, StockCountReview.build(listOf(tea), mapOf("tea" to 8), mapOf("tea" to 5), setOf("tea")).single().variance)
    }

    @Test fun unconfirmedBaselineIsUnknownAndInvalidRowsAreRejected() {
        val row = StockCountReview.build(listOf(tea), mapOf("tea" to 0), mapOf("tea" to 99), emptySet()).single()
        assertNull(row.previousQuantity)
        assertNull(row.variance)
        assertThrows(IllegalArgumentException::class.java) {
            StockCountReview.build(listOf(tea), mapOf("missing" to 1), emptyMap(), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            StockCountReview.build(listOf(tea), mapOf("tea" to -1), emptyMap(), emptySet())
        }
    }
}
