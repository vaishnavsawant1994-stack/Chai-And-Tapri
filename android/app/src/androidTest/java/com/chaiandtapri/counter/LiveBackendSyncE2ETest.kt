package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Optional end-to-end CAT-052 drill. Run with an isolated backend endpoint supplied to instrumentation. */
@RunWith(AndroidJUnit4::class)
class LiveBackendSyncE2ETest {
    private fun configureTestTrust() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val certificate = context.assets.open("tls/sync-test-server.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("isolated-backend-e2e", certificate)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory)
    }

    private fun getJson(endpoint: String, token: String): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            assertEquals("Dashboard GET should succeed", 200, status)
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    @Test fun realBackendStoresAndroidEventAndMakesLostAcknowledgmentRetryIdempotent() {
        val args = InstrumentationRegistry.getArguments()
        val endpointBase = args.getString("syncE2eEndpoint")
        assumeTrue("Provide syncE2eEndpoint for the isolated backend drill.", !endpointBase.isNullOrBlank())
        val priorSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storeId = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "sync_e2e_$storeId.db"
        val keyNamespace = "sync_e2e_$storeId"
        var model: CounterModel? = null
        var reopened: SqliteCounterPersistence? = null
        try {
            configureTestTrust()
            val transport = HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
            val deviceId = UUID.randomUUID().toString()
            val email = "android-${UUID.randomUUID()}@example.test"
            val session = SyncAccountClient(transport).register(
                endpointBase!!,
                SyncRegistration(email, "Synthetic-Only-Password-2026!", "Android E2E Owner", "Synthetic E2E Business", "Loopback Shop", deviceId, "API 37 Emulator")
            )
            val vault = SyncSessionVault(context, keyNamespace)
            vault.save(session)

            instrumentation.runOnMainSync { LocalSessionState.establish("Synthetic sync E2E owner") }
            val persistence = SqliteCounterPersistence(context, databaseName, keyNamespace)
            model = CounterModel(persistence, requireConfiguredProducts = true)
            val product = TapriProduct(
                productId = "e2e_chai", name = "E2E Chai", category = "Tea", unitPricePaise = 2_500,
                active = true, favorite = true, priceConfirmed = true, estimatedUnitCostPaise = 1_000
            )
            assertTrue(model!!.saveProduct(product).contains("saved"))
            assertTrue(model!!.confirmOpeningStock("e2e_chai", 10, "synthetic sync setup", "e2e-opening-${UUID.randomUUID()}").contains("confirmed"))
            val saleCommandId = "e2e-sale-${UUID.randomUUID()}"
            assertTrue(model!!.quickSale(listOf("e2e_chai" to 1), "cash", commandId = saleCommandId).startsWith("Quick sale"))
            val sendableEvents = persistence.loadPendingSyncEvents(20)
            val saleEvent = sendableEvents.singleOrNull { it.commandId == saleCommandId }
            val outboxState = persistence.writableDatabase.rawQuery("SELECT command_id,status,blocked_reason FROM sync_outbox ORDER BY created_at,event_id", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add("${cursor.getString(0)}=${cursor.getString(1)}:${if (cursor.isNull(2)) "" else cursor.getString(2)}") }.joinToString(" | ")
            }
            assertTrue("Actual SQLite outbox should contain the sale event; sendable=${sendableEvents.map { it.commandId }}; rows=$outboxState", saleEvent != null)
            assertTrue("Opening-stock event should also be in the outbox", sendableEvents.any { it.commandId.startsWith("e2e-opening-") })
            var loseFirstAcknowledgment = true
            val interruptedTransport = SyncHttpTransport { endpoint, token, body ->
                val response = transport.post(endpoint, token, body)
                val postedEventId = JSONObject(body).getJSONArray("events").getJSONObject(0).getString("eventId")
                if (postedEventId == saleEvent!!.eventId && loseFirstAcknowledgment) {
                    loseFirstAcknowledgment = false
                    throw IOException("Synthetic response-loss fault after server commit.")
                }
                response
            }

            val firstAttempt = runCatching {
                SyncRunner(PersistenceSyncJournal(persistence), interruptedTransport).push(session.endpointBase, session.identity, session.bearerToken)
            }
            assertTrue("The test fault should simulate the lost response", firstAttempt.exceptionOrNull() is IOException)
            assertEquals("Sale remains the only unacknowledged local event", listOf(saleEvent!!.eventId), persistence.loadPendingSyncEvents(20).map { it.eventId })
            assertEquals("Earlier stock event should have advanced the stored cursor", 1L, persistence.loadSyncCursor())
            model!!.close()
            model = null

            reopened = SqliteCounterPersistence(context, databaseName, keyNamespace)
            val restoredModel = CounterModel(reopened)
            assertEquals(2_500, restoredModel.allOrders().single().salePaise)
            assertEquals(9, restoredModel.stockLevels()["e2e_chai"])
            assertEquals(listOf(saleEvent.eventId), reopened!!.loadPendingSyncEvents(20).map { it.eventId })
            assertEquals(1L, reopened!!.loadSyncCursor())

            val retry = SyncRunner(PersistenceSyncJournal(reopened!!), transport).push(session.endpointBase, session.identity, session.bearerToken)
            assertEquals(1, retry.acknowledged)
            assertEquals(2L, retry.serverRevision)
            assertTrue("Durable outbox should clear only after the duplicate acknowledgment", reopened!!.loadPendingSyncEvents(20).isEmpty())
            assertEquals(2L, reopened!!.loadSyncCursor())
            restoredModel.close()
            reopened = null

            val overview = getJson("${session.endpointBase}/v1/dashboard/overview?shopId=${session.identity.shopId}", session.bearerToken)
            assertEquals(2L, overview.getLong("serverRevision"))
            assertEquals(2, overview.getInt("acceptedEventCount"))
            assertEquals(1, overview.getInt("recordedOrderCount"))
            assertEquals(2_500, overview.getLong("cashCollectedPaise"))
            val readiness = getJson("${session.endpointBase}/v1/dashboard/profit-readiness?shopId=${session.identity.shopId}&days=30", session.bearerToken)
            assertEquals(1, readiness.getJSONObject("costCoverage").getInt("costedNetDeliveredSaleLines"))
            assertNull(readiness.getJSONObject("figures").opt("grossProfitPaise").takeUnless { it === JSONObject.NULL })
            assertNull(readiness.getJSONObject("figures").opt("operatingProfitPaise").takeUnless { it === JSONObject.NULL })
            assertFalse("A server retry must not duplicate the accepted order", overview.getInt("recordedOrderCount") > 1)
            SyncAccountClient(transport).logout(session)
        } finally {
            model?.close()
            reopened?.close()
            instrumentation.runOnMainSync { LocalSessionState.clear() }
            context.deleteDatabase(databaseName)
            context.getSharedPreferences("chai_tapri_secure_key_envelope.$keyNamespace", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            SecureDatabaseKeyManager.removeWrappingKeyForTests(keyNamespace)
            SyncSessionVault(context, keyNamespace).clear()
            HttpsURLConnection.setDefaultSSLSocketFactory(priorSocketFactory)
        }
    }
}
