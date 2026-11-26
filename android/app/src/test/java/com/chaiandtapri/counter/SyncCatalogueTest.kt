package com.chaiandtapri.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncCatalogueTest {
    @Test fun catalogueFetchRequiresHttpsAndMapsHttpFailureWithoutParsingBody() {
        val identity = SyncEnvelopeIdentity("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003")
        var requested = ""
        val transport = object : SyncHttpTransport {
            override fun post(endpoint: String, bearerToken: String, body: String) = error("POST is not used by catalogue fetch.")
            override fun get(endpoint: String, bearerToken: String): SyncHttpResponse {
                requested = "$endpoint|$bearerToken"
                return SyncHttpResponse(401, "")
            }
        }
        val client = SyncCatalogueClient(transport)
        assertThrows(IllegalArgumentException::class.java) { client.fetch("http://sync.example.test", identity, "token") }
        assertThrows(SyncRequestException::class.java) { client.fetch("https://sync.example.test", identity, "token") }
        assertEquals("https://sync.example.test/v1/sync/catalogue/${identity.shopId}|token", requested)
    }
}
