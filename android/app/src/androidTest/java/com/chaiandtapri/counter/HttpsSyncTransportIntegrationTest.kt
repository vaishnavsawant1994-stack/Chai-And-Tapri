package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

/** Exercises the real Android HTTPS transport against a loopback-only, test-certificate server. */
@RunWith(AndroidJUnit4::class)
class HttpsSyncTransportIntegrationTest {
    private class LoopbackTlsServer : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().context
        private val password = "test-only-sync-keystore".toCharArray()
        private val serverContext: SSLContext
        private val socket: SSLServerSocket
        private val executor = Executors.newSingleThreadExecutor()
        val paths = mutableListOf<String>()
        val authenticatedRequests = mutableListOf<Boolean>()
        val eventIds = mutableListOf<String>()

        init {
            val keys = KeyStore.getInstance("PKCS12")
            context.assets.open("tls/sync-test-server.p12").use { keys.load(it, password) }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
            socket = serverContext.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            executor.execute { serve() }
        }

        val baseUrl get() = "https://localhost:${socket.localPort}"

        private fun serve() {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { return }
                client.use { connection ->
                    try {
                        val input = DataInputStream(connection.inputStream)
                        val requestLine = input.readAsciiLine()
                        val path = requestLine.split(' ').getOrNull(1) ?: return@use
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = input.readAsciiLine()
                            if (line.isEmpty()) break
                            val split = line.indexOf(':')
                            if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
                        }
                        val bodySize = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = ByteArray(bodySize)
                        input.readFully(body)
                        val request = JSONObject(body.toString(Charsets.UTF_8))
                        synchronized(this) {
                            paths += path
                            authenticatedRequests += headers["authorization"] == "Bearer local-test-token"
                        }
                        val response = when (path) {
                            "/v1/register" -> JSONObject()
                                .put("businessId", "11111111-1111-4111-8111-111111111111")
                                .put("shopId", "22222222-2222-4222-8222-222222222222")
                                .put("userId", "synthetic-owner")
                                .put("token", "local-test-token")
                            "/v1/sync/events" -> {
                                val event = request.getJSONArray("events").getJSONObject(0)
                                val id = event.getString("eventId")
                                synchronized(this) { eventIds += id }
                                val revision = event.getLong("baseRevision") + 1
                                JSONObject().put("currentRevision", revision).put("results", JSONArray().put(
                                    JSONObject().put("eventId", id).put("status", "accepted").put("serverRevision", revision)
                                ))
                            }
                            else -> JSONObject().put("error", "not_found")
                        }
                        val status = if (path == "/v1/register") 201 else if (path == "/v1/sync/events") 200 else 404
                        val encoded = response.toString().toByteArray(Charsets.UTF_8)
                        connection.outputStream.write("HTTP/1.1 $status ${if (status < 300) "OK" else "Not Found"}\r\nContent-Type: application/json\r\nContent-Length: ${encoded.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        connection.outputStream.write(encoded)
                        connection.outputStream.flush()
                    } catch (_: Exception) { /* Request failures are asserted by the client-side test. */ }
                }
            }
        }

        fun installTestTrust() {
            val certificate = context.assets.open("tls/sync-test-server.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("local-sync-test", certificate)
            }
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }.socketFactory)
        }

        override fun close() {
            socket.close()
            executor.shutdownNow()
        }

        private fun DataInputStream.readAsciiLine(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = readUnsignedByte()
                if (next == '\n'.code) break
                if (next != '\r'.code) bytes.write(next)
                require(bytes.size() <= 8192) { "Test HTTP header is unexpectedly long." }
            }
            return bytes.toString(Charsets.US_ASCII.name())
        }
    }

    private class MemoryJournal(private val event: SyncOutboxEvent) : SyncJournal {
        val outcomes = mutableListOf<Pair<String, Long>>()
        private var revision = 0L
        override fun pending(limit: Int) = if (outcomes.isEmpty() && limit > 0) listOf(event) else emptyList()
        override fun cursor() = revision
        override fun record(eventId: String, outcome: String, serverRevision: Long): Boolean {
            if (eventId != event.eventId || outcome !in setOf("accepted", "duplicate")) return false
            outcomes += outcome to serverRevision
            revision = maxOf(revision, serverRevision)
            return true
        }
    }

    @Test fun accountAndSyncRunnerUsePlatformTlsAndAcknowledgeOnlyAcceptedEvent() {
        val priorSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        LoopbackTlsServer().use { server ->
            try {
                server.installTestTrust()
                val transport = HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
                val deviceId = UUID.randomUUID().toString()
                val session = SyncAccountClient(transport).register(server.baseUrl, SyncRegistration(
                    "owner-${UUID.randomUUID()}@example.test", "Synthetic-Only-Password-2026!", "Synthetic Owner",
                    "Synthetic Business", "Synthetic Shop", deviceId, "API 37 test emulator"
                ))
                val event = SyncOutboxEvent(
                    UUID.randomUUID().toString(), "local-sync-command-${UUID.randomUUID()}", 1,
                    """{"kind":"projection_delta.v1","orders":[],"stock":[],"stockMovements":[],"customerCredits":[],"repayments":[],"paymentEntries":[]}"""
                )
                val journal = MemoryJournal(event)
                val result = SyncRunner(journal, transport).push(session.endpointBase, session.identity, session.bearerToken)

                assertEquals(1, result.acknowledged)
                assertEquals(1L, result.serverRevision)
                synchronized(server) {
                    assertEquals(listOf("/v1/register", "/v1/sync/events"), server.paths)
                    assertEquals(listOf(false, true), server.authenticatedRequests)
                    assertEquals(listOf(event.eventId), server.eventIds)
                }
                assertEquals(listOf("accepted" to 1L), journal.outcomes)
            } finally {
                HttpsURLConnection.setDefaultSSLSocketFactory(priorSocketFactory)
            }
        }
    }
}
