package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Exercises durable local retry semantics with a deterministic idempotent server double. */
@RunWith(AndroidJUnit4::class)
class SqliteOutboxRetryTest {
    private val identity = SyncEnvelopeIdentity(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333"
    )

    @Test fun lostSaleAcknowledgmentSurvivesSqlCipherReopenAndRetriesIdempotently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val namespace = "outbox_${UUID.randomUUID().toString().replace("-", "")}"
        val databaseName = "$namespace.db"
        var persistence: SqliteCounterPersistence? = null
        var reopened: SqliteCounterPersistence? = null
        var model: CounterModel? = null
        try {
            instrumentation.runOnMainSync { LocalSessionState.establish("Synthetic outbox retry owner") }
            persistence = SqliteCounterPersistence(context, databaseName, namespace)
            model = CounterModel(persistence, requireConfiguredProducts = true)
            assertTrue(model!!.saveProduct(TapriProduct(
                productId = "retry_tea", name = "Retry Tea", category = "Tea", unitPricePaise = 2_500,
                active = true, favorite = true, priceConfirmed = true, estimatedUnitCostPaise = 1_000
            )).startsWith("Product saved"))
            val openingCommand = "retry-opening-${UUID.randomUUID()}"
            val saleCommand = "retry-sale-${UUID.randomUUID()}"
            assertTrue(model!!.confirmOpeningStock("retry_tea", 5, "synthetic retry fixture", openingCommand).startsWith("Opening stock confirmed"))
            assertTrue(model!!.quickSale(listOf("retry_tea" to 1), "cash", commandId = saleCommand).startsWith("Quick sale"))

            val serverRevisions = linkedMapOf<String, Long>()
            val serverCommandIds = linkedSetOf<String>()
            var currentRevision = 0L
            var loseSaleResponse = true
            val transport = SyncHttpTransport { endpoint, token, body ->
                assertEquals("https://sync.example.test/v1/sync/events", endpoint)
                assertEquals("synthetic-owner-token", token)
                val event = JSONObject(body).getJSONArray("events").getJSONObject(0)
                val eventId = event.getString("eventId")
                val commandId = event.getString("commandId")
                val priorRevision = serverRevisions[eventId]
                val outcome: String
                val revision: Long
                if (priorRevision == null) {
                    currentRevision += 1L
                    serverRevisions[eventId] = currentRevision
                    serverCommandIds += commandId
                    outcome = "accepted"
                    revision = currentRevision
                } else {
                    outcome = "duplicate"
                    revision = priorRevision
                }
                if (commandId == saleCommand && loseSaleResponse) {
                    loseSaleResponse = false
                    throw IOException("Synthetic acknowledgment loss after server-side event commit.")
                }
                SyncHttpResponse(200, JSONObject()
                    .put("currentRevision", currentRevision)
                    .put("results", JSONArray().put(JSONObject()
                        .put("eventId", eventId)
                        .put("status", outcome)
                        .put("serverRevision", revision)))
                    .toString())
            }

            val saleEvent = persistence!!.loadPendingSyncEvents(20).single { it.commandId == saleCommand }
            val firstAttempt = runCatching {
                SyncRunner(PersistenceSyncJournal(persistence!!), transport)
                    .push("https://sync.example.test", identity, "synthetic-owner-token")
            }
            assertTrue(firstAttempt.exceptionOrNull() is IOException)
            assertEquals(1L, persistence!!.loadSyncCursor())
            assertEquals(listOf(saleEvent.eventId), persistence!!.loadPendingSyncEvents(20).map { it.eventId })
            model!!.close()
            model = null
            persistence = null

            reopened = SqliteCounterPersistence(context, databaseName, namespace)
            val restoredModel = CounterModel(reopened, requireConfiguredProducts = true)
            assertEquals(2_500, restoredModel.allOrders().single().salePaise)
            assertEquals(4, restoredModel.stockLevels()["retry_tea"])
            assertEquals(1L, reopened!!.loadSyncCursor())
            assertEquals(listOf(saleEvent.eventId), reopened!!.loadPendingSyncEvents(20).map { it.eventId })

            val retry = SyncRunner(PersistenceSyncJournal(reopened!!), transport)
                .push("https://sync.example.test", identity, "synthetic-owner-token")
            assertEquals(1, retry.acknowledged)
            assertEquals(2L, retry.serverRevision)
            assertEquals(2L, reopened!!.loadSyncCursor())
            assertTrue(reopened!!.loadPendingSyncEvents(20).isEmpty())
            assertEquals(setOf(openingCommand, saleCommand), serverCommandIds)
            assertEquals(2, serverRevisions.size)
            restoredModel.close()
            reopened = null
        } finally {
            instrumentation.runOnMainSync { LocalSessionState.clear() }
            model?.close()
            persistence?.close()
            reopened?.close()
            context.deleteDatabase(databaseName)
            context.getSharedPreferences("chai_tapri_secure_key_envelope.$namespace", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
            SecureDatabaseKeyManager.removeWrappingKeyForTests(namespace)
        }
    }
}
