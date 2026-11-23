package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncAccountClientTest {
    private val businessId = "11111111-1111-4111-8111-111111111111"
    private val shopId = "22222222-2222-4222-8222-222222222222"
    private val deviceId = "33333333-3333-4333-8333-333333333333"

    @Test fun registrationUsesHttpsContractAndReturnsServerIssuedIdentity() {
        var capturedEndpoint = ""
        var capturedBody = ""
        val client = SyncAccountClient(SyncHttpTransport { endpoint, token, body ->
            capturedEndpoint = endpoint
            assertEquals("", token)
            capturedBody = body
            SyncHttpResponse(201, JSONObject().put("businessId", businessId).put("shopId", shopId).put("userId", "44444444-4444-4444-8444-444444444444").put("token", "synthetic-server-token").toString())
        })

        val session = client.register("https://sync.example.test", SyncRegistration(
            "owner@example.test", "synthetic-long-password", "Tapri Owner", "Tapri Business", "Main Shop", deviceId, "Android Emulator"
        ))

        assertEquals("https://sync.example.test/v1/register", capturedEndpoint)
        assertEquals("owner@example.test", JSONObject(capturedBody).getString("email"))
        assertEquals("synthetic-long-password", JSONObject(capturedBody).getString("password"))
        assertEquals(SyncEnvelopeIdentity(businessId, shopId, deviceId), session.identity)
        assertEquals("synthetic-server-token", session.bearerToken)
        assertEquals("44444444-4444-4444-8444-444444444444", session.userId)
        assertEquals(SyncAccountRole.OWNER, session.role)
    }

    @Test fun loginRequiresSameRegisteredShopAndUsesKnownDeviceIdentity() {
        var capturedEndpoint = ""
        val client = SyncAccountClient(SyncHttpTransport { endpoint, token, body ->
            capturedEndpoint = endpoint
            assertEquals("", token)
            assertEquals(shopId, JSONObject(body).getString("shopId"))
            SyncHttpResponse(200, JSONObject().put("businessId", businessId).put("shopId", shopId).put("userId", "44444444-4444-4444-8444-444444444444").put("role", "cashier").put("token", "rotated-token").toString())
        })

        val session = client.login("https://sync.example.test/api", SyncLogin("owner@example.test", "synthetic-password", shopId, deviceId))

        assertEquals("https://sync.example.test/api/v1/login", capturedEndpoint)
        assertEquals(shopId, session.identity.shopId)
        assertEquals("rotated-token", session.bearerToken)
        assertEquals("44444444-4444-4444-8444-444444444444", session.userId)
        assertEquals(SyncAccountRole.CASHIER, session.role)
    }

    @Test fun loginRejectsUnknownOrMissingServerRole() {
        val client = SyncAccountClient(SyncHttpTransport { _, _, _ ->
            SyncHttpResponse(200, JSONObject().put("businessId", businessId).put("shopId", shopId)
                .put("userId", "44444444-4444-4444-8444-444444444444").put("role", "superuser").put("token", "token").toString())
        })
        assertTrue(runCatching { client.login("https://sync.example.test", SyncLogin("a@b.test", "password", shopId, deviceId)) }.isFailure)
    }

    @Test fun logoutRequiresServerRevocationConfirmation() {
        val session = SyncSession("https://sync.example.test", SyncEnvelopeIdentity(businessId, shopId, deviceId), "session-token")
        val client = SyncAccountClient(SyncHttpTransport { endpoint, token, _ ->
            assertEquals("https://sync.example.test/v1/logout", endpoint)
            assertEquals("session-token", token)
            SyncHttpResponse(200, "{\"status\":\"revoked\"}")
        })

        client.logout(session)
    }

    @Test fun insecureEndpointAndWeakRegistrationAreRejectedBeforeTransport() {
        var calls = 0
        val client = SyncAccountClient(SyncHttpTransport { _, _, _ -> calls++; SyncHttpResponse(201, "{}") })
        val endpointRejected = runCatching {
            client.register("http://sync.example.test", SyncRegistration("owner@example.test", "synthetic-long-password", "Tapri Owner", "Tapri Business", "Main Shop", deviceId, "Phone"))
        }.isFailure
        val passwordRejected = runCatching {
            client.register("https://sync.example.test", SyncRegistration("owner@example.test", "short", "Tapri Owner", "Tapri Business", "Main Shop", deviceId, "Phone"))
        }.isFailure
        assertTrue(endpointRejected)
        assertTrue(passwordRejected)
        assertEquals(0, calls)
    }

    @Test fun failedAuthenticationErrorContainsSafeCodeButNoResponseDetails() {
        val client = SyncAccountClient(SyncHttpTransport { _, _, _ -> SyncHttpResponse(401, "{\"error\":\"invalid_credentials\",\"detail\":\"do not expose\"}") })
        val failure = runCatching { client.login("https://sync.example.test", SyncLogin("owner@example.test", "synthetic-password", shopId, deviceId)) }.exceptionOrNull()
        assertTrue(failure is SyncAuthenticationException)
        assertEquals("invalid_credentials", (failure as SyncAuthenticationException).serverCode)
        assertTrue(!failure.message.orEmpty().contains("do not expose"))
    }
}
