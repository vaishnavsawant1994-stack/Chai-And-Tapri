package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncOutboxActorAttributionTest {
    @Test fun pendingOutboxEventsCarryTheLocalActorFromTheirAuditRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        try {
            LocalSessionState.establish("Synthetic owner")
            SqliteCounterPersistence(context).use { persistence ->
                val model = CounterModel(persistence, requireConfiguredProducts = true)
                try {
                    assertTrue(model.saveProduct(TapriProduct("actor_sync_tea", "Actor Sync Tea", "Chai", 1200, priceConfirmed = true)).startsWith("Product saved"))
                    assertTrue(model.confirmOpeningStock("actor_sync_tea", 3, "actor attribution fixture", "actor-sync-count").startsWith("Opening stock confirmed"))
                    LocalSessionState.establishStaff("cashier-sync-01", "Synthetic Cashier", StaffRole.CASHIER)
                    assertTrue(model.quickSale(listOf("actor_sync_tea" to 1), "cash", commandId = "actor-sync-sale").startsWith("Quick sale"))

                    val events = persistence.loadPendingSyncEvents(20).associateBy { it.commandId }
                    assertEquals("local-owner", events["actor-sync-count"]?.actorId)
                    assertEquals("cashier-sync-01", events["actor-sync-sale"]?.actorId)
                    assertTrue(model.auditRecords().any { it.commandId == "actor-sync-sale" && it.actorId == "cashier-sync-01" })
                } finally { model.close() }
            }
        } finally {
            LocalSessionState.clear()
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    }
}
