package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRunnerTest {
    private val identity = SyncEnvelopeIdentity(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333"
    )

    private fun event(n: Int) = SyncOutboxEvent(
        "44444444-4444-4444-8444-${n.toString().padStart(12, '0')}", "synthetic-command-$n", 1,
        "{\"kind\":\"projection_delta.v1\",\"orders\":[],\"stock\":[{\"productId\":\"tea\",\"quantity\":$n}],\"stockMovements\":[],\"customerCredits\":[],\"repayments\":[],\"paymentEntries\":[]}"
    )

    private class MemoryJournal(events: List<SyncOutboxEvent>) : SyncJournal {
        val pendingEvents = events.toMutableList()
        val outcomes = mutableListOf<Triple<String, String, Long>>()
        var revision = 0L
        override fun pending(limit: Int) = pendingEvents.take(limit)
        override fun cursor() = revision
        override fun record(eventId: String, outcome: String, serverRevision: Long): Boolean {
            val event = pendingEvents.firstOrNull() ?: return false
            if (event.eventId != eventId) return false
            outcomes += Triple(eventId, outcome, serverRevision)
            if (outcome == "conflict") return true
            pendingEvents.removeAt(0)
            revision = maxOf(revision, serverRevision)
            return true
        }
    }

    @Test fun acceptedEventsArePushedInOrderAndAcknowledgedOnlyAfterResponse() {
        val journal = MemoryJournal(listOf(event(1), event(2)))
        val sent = mutableListOf<String>()
        val transport = SyncHttpTransport { endpoint, token, body ->
            assertEquals("https://sync.example.test/v1/sync/events", endpoint)
            assertEquals("secret-test-token", token)
            val request = JSONObject(body)
            val envelope = request.getJSONArray("events").getJSONObject(0)
            sent += envelope.getString("eventId")
            val nextRevision = envelope.getLong("baseRevision") + 1
            SyncHttpResponse(200, JSONObject().put("currentRevision", nextRevision).put("results", JSONArray().put(
                JSONObject().put("eventId", envelope.getString("eventId")).put("status", "accepted").put("serverRevision", nextRevision)
            )).toString())
        }

        val result = SyncRunner(journal, transport).push("https://sync.example.test", identity, "secret-test-token")

        assertEquals(2, result.acknowledged)
        assertEquals(2L, result.serverRevision)
        assertEquals(listOf(event(1).eventId, event(2).eventId), sent)
        assertTrue(journal.pendingEvents.isEmpty())
        assertEquals(listOf("accepted", "accepted"), journal.outcomes.map { it.second })
    }

    @Test fun conflictIsPersistedAndStopsTheQueue() {
        val journal = MemoryJournal(listOf(event(1), event(2)))
        var calls = 0
        val transport = SyncHttpTransport { _, _, body ->
            calls++
            val eventId = JSONObject(body).getJSONArray("events").getJSONObject(0).getString("eventId")
            SyncHttpResponse(200, JSONObject().put("currentRevision", 5).put("results", JSONArray().put(
                JSONObject().put("eventId", eventId).put("status", "conflict").put("currentRevision", 5)
            )).toString())
        }

        val result = SyncRunner(journal, transport).push("https://sync.example.test", identity, "secret-test-token")

        assertEquals(0, result.acknowledged)
        assertEquals(event(1).eventId, result.conflictEventId)
        assertEquals(5L, result.serverRevision)
        assertEquals(1, calls)
        assertEquals("conflict", journal.outcomes.single().second)
        assertEquals(2, journal.pendingEvents.size)
    }

    @Test fun failedHttpResponseLeavesBusinessOutboxPending() {
        val journal = MemoryJournal(listOf(event(1)))
        val runner = SyncRunner(journal, SyncHttpTransport { _, _, _ -> SyncHttpResponse(503, "unavailable") })
        var failed = false
        try { runner.push("https://sync.example.test", identity, "secret-test-token") }
        catch (error: SyncRequestException) { failed = error.statusCode == 503 }
        assertTrue(failed)
        assertTrue(journal.outcomes.isEmpty())
        assertEquals(event(1).eventId, journal.pendingEvents.single().eventId)
    }

    @Test fun mismatchedResponseEventCannotBeAcknowledgedAndHttpIsRejected() {
        val journal = MemoryJournal(listOf(event(1)))
        val wrong = event(2).eventId
        val transport = SyncHttpTransport { _, _, _ ->
            SyncHttpResponse(200, JSONObject().put("results", JSONArray().put(
                JSONObject().put("eventId", wrong).put("status", "accepted").put("serverRevision", 1)
            )).toString())
        }
        var mismatchRejected = false
        try { SyncRunner(journal, transport).push("https://sync.example.test", identity, "secret-test-token") }
        catch (_: SyncProtocolException) { mismatchRejected = true }
        assertTrue(mismatchRejected)
        assertTrue(journal.outcomes.isEmpty())
        assertEquals(event(1).eventId, journal.pendingEvents.single().eventId)

        var cleartextRejected = false
        try { SyncRunner(journal, transport).push("http://sync.example.test", identity, "secret-test-token") }
        catch (_: IllegalArgumentException) { cleartextRejected = true }
        assertTrue(cleartextRejected)
        assertFalse(journal.outcomes.isNotEmpty())
    }
}
