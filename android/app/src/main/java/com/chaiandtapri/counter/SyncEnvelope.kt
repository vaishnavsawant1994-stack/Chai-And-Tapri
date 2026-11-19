package com.chaiandtapri.counter

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SyncEnvelopeIdentity(val businessId: String, val shopId: String, val deviceId: String)
data class SyncOutboxEvent(val eventId: String, val commandId: String, val payloadVersion: Int?, val payloadJson: String?)

/** Converts only validated local v1 payloads to the authenticated backend request contract. */
object SyncEnvelopeMapper {
    fun batch(identity: SyncEnvelopeIdentity, baseRevision: Long, events: List<SyncOutboxEvent>): String? {
        if (!identity.businessId.isUuid() || !identity.shopId.isUuid() || !identity.deviceId.isUuid()) return null
        if (baseRevision < 0 || events.isEmpty() || events.size > 100) return null
        val seenEventIds = mutableSetOf<String>()
        val seenCommandIds = mutableSetOf<String>()
        val envelopes = JSONArray()
        events.forEachIndexed { index, event ->
            if (!event.eventId.isUuid() || event.commandId.isBlank() || event.commandId.length > 160 ||
                event.payloadVersion != 1 || event.payloadJson.isNullOrBlank() ||
                !seenEventIds.add(event.eventId.lowercase()) || !seenCommandIds.add(event.commandId)) return null
            val eventBaseRevision = runCatching { Math.addExact(baseRevision, index.toLong()) }.getOrNull() ?: return null
            val payload = runCatching { JSONObject(event.payloadJson) }.getOrNull() ?: return null
            if (payload.optString("kind") != "projection_delta.v1") return null
            envelopes.put(JSONObject()
                .put("type", "business.command.v1")
                .put("schemaVersion", 1)
                .put("eventId", event.eventId)
                .put("commandId", event.commandId)
                .put("businessId", identity.businessId)
                .put("shopId", identity.shopId)
                .put("deviceId", identity.deviceId)
                .put("baseRevision", eventBaseRevision)
                .put("payload", payload))
        }
        return JSONObject().put("shopId", identity.shopId).put("events", envelopes).toString()
    }

    private fun String.isUuid(): Boolean = runCatching {
        UUID.fromString(this).toString().equals(this, ignoreCase = true)
    }.getOrDefault(false)
}
