package com.chaiandtapri.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockCountHistoryTest {
    @Test fun projectsPersistedCountsAndKeepsNoOpProductsInSession() {
        val history = StockCountHistory.from(listOf(
            AuditRecord("e1", "count-2", "count-2 stock.physical_count products=tea,sugar counted=sugar:0,tea:5 reason=closing shelf count", 200L, "manager-1"),
            AuditRecord("e2", "not-count", "sale.commit #1", 300L)
        ), mapOf("tea" to "Tea", "sugar" to "Sugar"))

        assertEquals(1, history.size)
        assertEquals("count-2", history.single().commandId)
        assertEquals(200L, history.single().recordedAtEpochMs)
        assertEquals("closing shelf count", history.single().reason)
        assertEquals("manager-1", history.single().actorId)
        assertEquals(listOf("Sugar" to 0, "Tea" to 5), history.single().rows.map { it.productName to it.countedQuantity })
    }

    @Test fun legacyAuditWithoutCountValuesIsRetainedButClearlyUnknown() {
        val session = StockCountHistory.from(listOf(
            AuditRecord("e1", "legacy-count", "legacy-count stock.physical_count products=tea,water reason=night count", 100L)
        ), mapOf("tea" to "Tea")).single()

        assertEquals("Tea", session.rows[0].productName)
        assertNull(session.rows[0].countedQuantity)
        assertEquals("water", session.rows[1].productName)
        assertNull(session.rows[1].countedQuantity)
    }

    @Test fun ignoresMalformedCountRecordsAndOrdersNewestFirst() {
        val history = StockCountHistory.from(listOf(
            AuditRecord("e1", "older", "older stock.physical_count products=tea counted=tea:1 reason=morning", 10L),
            AuditRecord("e2", "bad", "bad stock.physical_count products=tea counted=tea:9999999 reason=bad", 20L),
            AuditRecord("e3", "newer", "newer stock.physical_count products=tea counted=tea:3 reason=evening", 30L)
        ), emptyMap())

        assertEquals(listOf("newer", "older"), history.map { it.commandId })
        assertTrue(history.all { it.rows.single().productName == "tea" })
    }
}
