package com.chaiandtapri.counter

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class SyncHttpResponse(val statusCode: Int, val body: String)

/** A transport boundary so request policy and journal behavior can be tested without a live service. */
fun interface SyncHttpTransport {
    fun post(endpoint: String, bearerToken: String, body: String): SyncHttpResponse
}

/** Uses platform TLS validation; cleartext, redirects, credentials in URLs, and unbounded bodies are rejected. */
class HttpsSyncHttpTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxResponseBytes: Int = 1_048_576
) : SyncHttpTransport {
    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && maxResponseBytes > 0)
    }

    override fun post(endpoint: String, bearerToken: String, body: String): SyncHttpResponse {
        require(bearerToken.length <= 4096 && bearerToken.none { it.isWhitespace() }) { "Bearer session is invalid." }
        val uri = URI(endpoint)
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Sync requires an HTTPS endpoint without embedded credentials, query, or fragment."
        }
        val connection = (URL(endpoint).openConnection() as? HttpsURLConnection)
            ?: throw IllegalArgumentException("Sync requires HTTPS.")
        connection.requestMethod = "POST"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        if (bearerToken.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                ?: return SyncHttpResponse(code, "")
            val bytes = stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxResponseBytes) { "Sync response is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return SyncHttpResponse(code, bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}

interface SyncJournal {
    fun pending(limit: Int): List<SyncOutboxEvent>
    fun cursor(): Long
    fun record(eventId: String, outcome: String, serverRevision: Long): Boolean
}

class PersistenceSyncJournal(private val persistence: CounterPersistence) : SyncJournal {
    override fun pending(limit: Int) = persistence.loadPendingSyncEvents(limit)
    override fun cursor() = persistence.loadSyncCursor()
    override fun record(eventId: String, outcome: String, serverRevision: Long) =
        persistence.recordSyncOutcome(eventId, outcome, serverRevision)
}

data class SyncRunResult(val acknowledged: Int, val conflictEventId: String? = null, val serverRevision: Long)

class SyncProtocolException(message: String) : IllegalStateException(message)
class SyncRequestException(val statusCode: Int) : IllegalStateException("Sync endpoint returned HTTP $statusCode.")

/** Pushes sendable events in order. Callers supply credentials from a protected session store. */
class SyncRunner(private val journal: SyncJournal, private val transport: SyncHttpTransport) {
    fun push(endpointBase: String, identity: SyncEnvelopeIdentity, bearerToken: String, maxEvents: Int = 25): SyncRunResult {
        require(maxEvents in 1..100)
        require(bearerToken.isNotBlank()) { "An authenticated sync session is required." }
        val endpoint = syncEndpoint(endpointBase)
        var acknowledged = 0
        var cursor = journal.cursor().also { require(it >= 0L) { "Stored sync cursor is invalid." } }
        repeat(maxEvents) {
            val event = journal.pending(1).firstOrNull() ?: return SyncRunResult(acknowledged, serverRevision = cursor)
            val request = SyncEnvelopeMapper.batch(identity, cursor, listOf(event))
                ?: throw SyncProtocolException("Pending outbox event is not valid for the configured sync contract.")
            val response = transport.post(endpoint, bearerToken, request)
            if (response.statusCode !in 200..299) throw SyncRequestException(response.statusCode)
            val result = parseSingleResult(response.body, event.eventId)
            when (result.status) {
                "accepted", "duplicate" -> {
                    if (result.serverRevision == null || result.serverRevision < cursor) throw SyncProtocolException("Server returned an invalid event revision.")
                    if (!journal.record(event.eventId, result.status, result.serverRevision)) {
                        throw SyncProtocolException("Server accepted the event but local acknowledgement could not be committed; retry is safe by event ID.")
                    }
                    cursor = maxOf(cursor, result.serverRevision)
                    acknowledged++
                }
                "conflict" -> {
                    val current = result.currentRevision ?: throw SyncProtocolException("Server conflict omitted its current revision.")
                    if (current < 0L) throw SyncProtocolException("Server returned an invalid conflict revision.")
                    if (!journal.record(event.eventId, "conflict", current)) throw SyncProtocolException("Local conflict state could not be persisted.")
                    return SyncRunResult(acknowledged, event.eventId, current)
                }
                else -> throw SyncProtocolException("Server returned an unsupported event outcome.")
            }
        }
        return SyncRunResult(acknowledged, serverRevision = cursor)
    }

    private data class ParsedResult(val status: String, val serverRevision: Long?, val currentRevision: Long?)

    private fun parseSingleResult(body: String, expectedEventId: String): ParsedResult = try {
        val root = JSONObject(body)
        val results = root.getJSONArray("results")
        if (results.length() != 1) throw SyncProtocolException("Server response did not contain exactly one event result.")
        val item = results.getJSONObject(0)
        if (item.getString("eventId") != expectedEventId) throw SyncProtocolException("Server response event ID did not match the pending event.")
        val status = item.getString("status")
        val serverRevision = if (item.has("serverRevision") && !item.isNull("serverRevision")) item.getLong("serverRevision") else null
        val currentRevision = if (item.has("currentRevision") && !item.isNull("currentRevision")) item.getLong("currentRevision") else null
        ParsedResult(status, serverRevision, currentRevision)
    } catch (error: SyncProtocolException) {
        throw error
    } catch (error: Exception) {
        throw SyncProtocolException("Server response was malformed.")
    }

    private fun syncEndpoint(base: String): String {
        val normalized = base.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid HTTPS sync server URL.")
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Sync requires an HTTPS server URL without credentials, query, or fragment."
        }
        return "$normalized/v1/sync/events"
    }
}
