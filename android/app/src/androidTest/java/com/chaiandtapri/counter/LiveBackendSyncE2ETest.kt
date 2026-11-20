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
    private class PendingJournal(private val event: SyncOutboxEvent) : SyncJournal {
        val outcomes = mutableListOf<Pair<String, Long>>()
        private var cursor = 0L
        override fun pending(limit: Int) = if (outcomes.isEmpty() && limit > 0) listOf(event) else emptyList()
        override fun cursor() = cursor
        override fun record(eventId: String, outcome: String, serverRevision: Long): Boolean {
            if (eventId != event.eventId || outcome !in setOf("accepted", "duplicate") || serverRevision < cursor) return false
            outcomes += outcome to serverRevision
            cursor = maxOf(cursor, serverRevision)
            return true
        }
    }

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
        try {
            configureTestTrust()
            val transport = HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
            val deviceId = UUID.randomUUID().toString()
            val email = "android-${UUID.randomUUID()}@example.test"
            val session = SyncAccountClient(transport).register(
                endpointBase!!,
                SyncRegistration(email, "Synthetic-Only-Password-2026!", "Android E2E Owner", "Synthetic E2E Business", "Loopback Shop", deviceId, "API 37 Emulator")
            )

            val eventId = UUID.randomUUID().toString()
            val commandId = "android-e2e-${UUID.randomUUID()}"
            val stockMovementId = UUID.randomUUID().toString()
            val paymentEntryId = UUID.randomUUID().toString()
            val payload = JSONObject()
                .put("kind", "projection_delta.v1")
                .put("orders", org.json.JSONArray().put(JSONObject()
                    .put("orderNumber", 12)
                    .put("fulfillment", "GIVEN")
                    .put("payment", "STAFF_DECLARED")
                    .put("paidPaise", 2_500)
                    .put("creditPaise", 0)
                    .put("refundedPaise", 0)
                    .put("cashRefundedPaise", 0)
                    .put("creditReversedPaise", 0)
                    .put("salePosted", true)
                    .put("voided", false)
                    .put("revision", 1)
                    .put("createdAtEpochMs", System.currentTimeMillis())
                    .put("lines", org.json.JSONArray().put(JSONObject()
                        .put("productId", "chai")
                        .put("quantity", 1)
                        .put("deliveredQuantity", 1)
                        .put("refundedQuantity", 0)
                        .put("unitPricePaise", 2_500)
                        .put("estimatedUnitCostPaise", 1_000)))))
                .put("stock", org.json.JSONArray().put(JSONObject().put("productId", "chai").put("quantity", 9)))
                .put("stockMovements", org.json.JSONArray().put(JSONObject()
                    .put("movementId", stockMovementId).put("productId", "chai").put("quantityDelta", -1).put("orderNumber", 12)))
                .put("customerCredits", org.json.JSONArray())
                .put("repayments", org.json.JSONArray())
                .put("paymentEntries", org.json.JSONArray().put(JSONObject()
                    .put("entryId", paymentEntryId).put("orderNumber", 12).put("customerId", JSONObject.NULL)
                    .put("amountPaise", 2_500).put("method", "cash").put("status", "staff_declared")
                    .put("kind", "order_collection").put("relatedEntryId", JSONObject.NULL)))
                .put("expenses", org.json.JSONArray())
            val event = SyncOutboxEvent(eventId, commandId, 1, payload.toString())
            val journal = PendingJournal(event)
            var loseFirstAcknowledgment = true
            val interruptedTransport = SyncHttpTransport { endpoint, token, body ->
                val response = transport.post(endpoint, token, body)
                if (loseFirstAcknowledgment) {
                    loseFirstAcknowledgment = false
                    throw IOException("Synthetic response-loss fault after server commit.")
                }
                response
            }

            val firstAttempt = runCatching {
                SyncRunner(journal, interruptedTransport).push(session.endpointBase, session.identity, session.bearerToken)
            }
            assertTrue("The test fault should simulate the lost response", firstAttempt.exceptionOrNull() is IOException)
            assertTrue("Local outbox must remain pending after the lost response", journal.pending(1).singleOrNull()?.eventId == eventId)

            val retry = SyncRunner(journal, transport).push(session.endpointBase, session.identity, session.bearerToken)
            assertEquals(1, retry.acknowledged)
            assertEquals(1L, retry.serverRevision)
            assertEquals(listOf("duplicate" to 1L), journal.outcomes)
            assertTrue("Retry must remove the acknowledged event from the outbox", journal.pending(1).isEmpty())

            val overview = getJson("${session.endpointBase}/v1/dashboard/overview?shopId=${session.identity.shopId}", session.bearerToken)
            assertEquals(1L, overview.getLong("serverRevision"))
            assertEquals(1, overview.getInt("acceptedEventCount"))
            assertEquals(1, overview.getInt("recordedOrderCount"))
            assertEquals(2_500, overview.getLong("cashCollectedPaise"))
            val readiness = getJson("${session.endpointBase}/v1/dashboard/profit-readiness?shopId=${session.identity.shopId}&days=30", session.bearerToken)
            assertEquals(1, readiness.getJSONObject("costCoverage").getInt("costedNetDeliveredSaleLines"))
            assertNull(readiness.getJSONObject("figures").opt("grossProfitPaise").takeUnless { it === JSONObject.NULL })
            assertNull(readiness.getJSONObject("figures").opt("operatingProfitPaise").takeUnless { it === JSONObject.NULL })
            assertFalse("A server retry must not duplicate the accepted order", overview.getInt("recordedOrderCount") > 1)
            SyncAccountClient(transport).logout(session)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(priorSocketFactory)
        }
    }
}
