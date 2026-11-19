package com.chaiandtapri.counter

import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class SyncRegistration(
    val email: String,
    val password: String,
    val displayName: String,
    val businessName: String,
    val shopName: String,
    val deviceId: String,
    val deviceLabel: String
)

data class SyncLogin(val email: String, val password: String, val shopId: String, val deviceId: String)

/** Non-secret local identity needed to renew this device's session after server logout/expiry. */
class SyncDeviceIdentityStore(context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chai_tapri_sync_device_identity_v1", android.content.Context.MODE_PRIVATE)

    fun save(session: SyncSession) {
        check(prefs.edit().putString("business_id", session.identity.businessId)
            .putString("shop_id", session.identity.shopId)
            .putString("device_id", session.identity.deviceId)
            .putString("endpoint_base", session.endpointBase)
            .commit()) { "Device sync identity could not be saved." }
    }

    fun load(): SyncDeviceIdentity? {
        val businessId = prefs.getString("business_id", null) ?: return null
        val shopId = prefs.getString("shop_id", null) ?: return null
        val deviceId = prefs.getString("device_id", null) ?: return null
        val endpoint = prefs.getString("endpoint_base", null) ?: return null
        return runCatching {
            require(UUID.fromString(businessId).toString() == businessId)
            require(UUID.fromString(shopId).toString() == shopId)
            require(UUID.fromString(deviceId).toString() == deviceId)
            SyncDeviceIdentity(endpoint, SyncEnvelopeIdentity(businessId, shopId, deviceId))
        }.getOrNull()
    }
}

data class SyncDeviceIdentity(val endpointBase: String, val identity: SyncEnvelopeIdentity)

class SyncAuthenticationException(val statusCode: Int, val serverCode: String?) :
    IllegalStateException("Sync account request failed (HTTP $statusCode${serverCode?.let { ": $it" } ?: ""}).")

/** HTTPS-only client for the existing registration, login and revocation API contract. */
class SyncAccountClient(private val transport: SyncHttpTransport) {
    fun register(endpointBase: String, request: SyncRegistration): SyncSession {
        validateRegistration(request)
        val response = transport.post(apiEndpoint(endpointBase, "register"), "", JSONObject()
            .put("email", request.email.trim().lowercase())
            .put("password", request.password)
            .put("displayName", request.displayName.trim())
            .put("businessName", request.businessName.trim())
            .put("shopName", request.shopName.trim())
            .put("deviceId", request.deviceId.lowercase())
            .put("deviceLabel", request.deviceLabel.trim())
            .toString())
        if (response.statusCode != 201) throw apiError(response)
        val body = parseBody(response.body)
        val businessId = body.getString("businessId")
        val shopId = body.getString("shopId")
        val token = body.getString("token")
        val session = SyncSession(endpointBase.trim().trimEnd('/'), SyncEnvelopeIdentity(businessId, shopId, request.deviceId.lowercase()), token)
        validateSession(session)
        return session
    }

    fun login(endpointBase: String, request: SyncLogin): SyncSession {
        require(request.email.isNotBlank() && request.email.length <= 254)
        require(request.password.isNotEmpty() && request.password.length <= 256)
        require(request.shopId.isUuid() && request.deviceId.isUuid()) { "Shop or device identity is invalid." }
        val response = transport.post(apiEndpoint(endpointBase, "login"), "", JSONObject()
            .put("email", request.email.trim().lowercase())
            .put("password", request.password)
            .put("shopId", request.shopId.lowercase())
            .put("deviceId", request.deviceId.lowercase())
            .toString())
        if (response.statusCode != 200) throw apiError(response)
        val body = parseBody(response.body)
        val session = SyncSession(
            endpointBase.trim().trimEnd('/'),
            SyncEnvelopeIdentity(body.getString("businessId"), body.getString("shopId"), request.deviceId.lowercase()),
            body.getString("token")
        )
        require(session.identity.shopId.equals(request.shopId, ignoreCase = true)) { "Server returned a different shop identity." }
        validateSession(session)
        return session
    }

    fun logout(session: SyncSession) {
        validateSession(session)
        val response = transport.post(apiEndpoint(session.endpointBase, "logout"), session.bearerToken, "{}")
        if (response.statusCode !in 200..299) throw apiError(response)
        val body = parseBody(response.body)
        require(body.optString("status") == "revoked") { "Server did not confirm session revocation." }
    }

    private fun validateRegistration(request: SyncRegistration) {
        require(request.email.trim().length <= 254 && EMAIL.matches(request.email.trim())) { "Enter a valid owner email." }
        require(request.password.length in 12..256) { "Account password must contain 12 to 256 characters." }
        require(request.displayName.trim().length in 2..100)
        require(request.businessName.trim().length in 2..120)
        require(request.shopName.trim().length in 2..120)
        require(request.deviceLabel.trim().isNotEmpty() && request.deviceLabel.trim().length <= 80)
        require(request.deviceId.isUuid()) { "Device identity is invalid." }
    }

    private fun validateSession(session: SyncSession) {
        val endpoint = apiEndpoint(session.endpointBase, "sync/events")
        check(endpoint.startsWith("https://"))
        require(session.identity.businessId.isUuid() && session.identity.shopId.isUuid() && session.identity.deviceId.isUuid())
        require(session.bearerToken.isNotBlank() && session.bearerToken.length <= 4096 && session.bearerToken.none { it.isWhitespace() })
    }

    private fun apiEndpoint(base: String, route: String): String {
        val normalized = base.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Account setup requires an HTTPS server URL without credentials, query, or fragment."
        }
        return "$normalized/v1/$route"
    }

    private fun parseBody(value: String): JSONObject = try {
        JSONObject(value).also { require(it.length() in 1..16) }
    } catch (_: Exception) {
        throw SyncProtocolException("Sync account server returned a malformed response.")
    }

    private fun apiError(response: SyncHttpResponse): SyncAuthenticationException {
        val code = runCatching { JSONObject(response.body).optString("error").takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) } }.getOrNull()
        return SyncAuthenticationException(response.statusCode, code)
    }

    private fun String.isUuid() = runCatching { UUID.fromString(this).toString().equals(this, true) }.getOrDefault(false)

    companion object { private val EMAIL = Regex("^\\S+@\\S+\\.\\S+$") }
}
