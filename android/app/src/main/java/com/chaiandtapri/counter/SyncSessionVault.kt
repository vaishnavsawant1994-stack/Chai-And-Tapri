package com.chaiandtapri.counter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SyncAccountRole(val wireValue: String) {
    OWNER("owner"), MANAGER("manager"), CASHIER("cashier");

    companion object {
        fun parse(value: String): SyncAccountRole = entries.firstOrNull { it.wireValue == value }
            ?: throw SyncProtocolException("Sync account returned an unsupported role.")
    }
}

data class SyncSession(
    val endpointBase: String,
    val identity: SyncEnvelopeIdentity,
    val bearerToken: String,
    val localActorId: String? = null,
    val userId: String? = null,
    val role: SyncAccountRole? = null
)

internal object SyncOwnerPolicy {
    fun isLocalOwner(actorId: String?): Boolean = actorId == LOCAL_OWNER_ACTOR

    fun mayBindCloudSession(session: SyncSession, activeLocalActorId: String?): Boolean =
        isLocalOwner(activeLocalActorId) && session.userId != null && session.role == SyncAccountRole.OWNER

    fun mayUpload(session: SyncSession): Boolean =
        isLocalOwner(session.localActorId) && session.userId != null && session.role == SyncAccountRole.OWNER

    const val LOCAL_OWNER_ACTOR = "local-owner"
}

/** Stores only a server-issued bearer session, encrypted with an app/device-bound Android Keystore key. */
class SyncSessionVault(context: Context, private val keyNamespace: String = "default") {
    private val appContext = context.applicationContext
    private val storageName = if (keyNamespace == "default") PREFERENCES else "$PREFERENCES.$keyNamespace"
    private val storageAlias = if (keyNamespace == "default") KEY_ALIAS else "$KEY_ALIAS.$keyNamespace"
    private val preferences = appContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)

    init { require(keyNamespace.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Sync session namespace is invalid." } }

    @Synchronized
    fun save(session: SyncSession) {
        validate(session)
        val plaintext = JSONObject()
            .put("endpointBase", session.endpointBase.trim().trimEnd('/'))
            .put("businessId", session.identity.businessId)
            .put("shopId", session.identity.shopId)
            .put("deviceId", session.identity.deviceId)
            .put("localActorId", session.localActorId)
            .put("userId", session.userId)
            .put("role", session.role?.wireValue)
            .put("bearerToken", session.bearerToken)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
            val ciphertext = Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP)
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            check(preferences.edit().putString(CIPHERTEXT, ciphertext).putString(IV, iv).commit()) {
                "Could not persist the encrypted sync session."
            }
        } catch (error: GeneralSecurityException) {
            throw SyncSessionUnavailableException("Android Keystore could not protect the sync session.", error)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun load(): SyncSession? {
        val encoded = preferences.getString(CIPHERTEXT, null) ?: return null
        val encodedIv = preferences.getString(IV, null)
            ?: throw SyncSessionUnavailableException("Stored sync-session envelope is incomplete.")
        val key = loadExistingKey()
            ?: throw SyncSessionUnavailableException("The sync-session Keystore key is missing. Sign in again after reviewing this device's sync state.")
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
        } catch (error: AEADBadTagException) {
            throw SyncSessionUnavailableException("Stored sync-session data failed authentication and was preserved.", error)
        } catch (error: GeneralSecurityException) {
            throw SyncSessionUnavailableException("Sync session is inaccessible; encrypted data was preserved.", error)
        }
        return try {
            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            SyncSession(
                endpointBase = json.getString("endpointBase"),
                identity = SyncEnvelopeIdentity(json.getString("businessId"), json.getString("shopId"), json.getString("deviceId")),
                bearerToken = json.getString("bearerToken"),
                localActorId = if (json.has("localActorId") && !json.isNull("localActorId")) json.getString("localActorId") else null,
                userId = if (json.has("userId") && !json.isNull("userId")) json.getString("userId") else null,
                role = if (json.has("role") && !json.isNull("role")) SyncAccountRole.parse(json.getString("role")) else null
            ).also(::validate)
        } catch (error: Exception) {
            throw SyncSessionUnavailableException("Stored sync-session data is malformed; encrypted data was preserved.", error)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Call after a successful server logout or explicit owner disconnect. */
    @Synchronized
    fun clear() {
        check(preferences.edit().remove(CIPHERTEXT).remove(IV).commit()) { "Could not clear the local sync-session envelope." }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(storageAlias)
    }

    private fun validate(session: SyncSession) {
        val endpoint = session.endpointBase.trim().trimEnd('/')
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Sync session requires an HTTPS server URL without embedded credentials, query, or fragment."
        }
        require(session.identity.businessId.isUuid() && session.identity.shopId.isUuid() && session.identity.deviceId.isUuid()) {
            "Sync session contains an invalid business, shop, or device identifier."
        }
        require(session.bearerToken.isNotBlank() && session.bearerToken.length <= 4096 && session.bearerToken.none { it.isWhitespace() }) {
            "Sync session token is invalid."
        }
        require(session.localActorId == null || session.localActorId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{1,59}"))) {
            "Sync session local-operator binding is invalid."
        }
        require(session.userId == null || session.userId.isUuid()) { "Sync session account identifier is invalid." }
        require((session.userId == null) == (session.role == null)) { "Sync session account identity is incomplete." }
    }

    private fun loadOrCreateKey(): SecretKey = loadExistingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
        init(KeyGenParameterSpec.Builder(storageAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        generateKey()
    }

    private fun loadExistingKey(): SecretKey? = KeyStore.getInstance(ANDROID_KEYSTORE).run {
        load(null)
        getKey(storageAlias, null) as? SecretKey
    }

    private fun String.isUuid(): Boolean = runCatching { java.util.UUID.fromString(this).toString().equals(this, true) }.getOrDefault(false)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "chai_tapri_sync_session_v1"
        private const val PREFERENCES = "chai_tapri_secure_sync_session_v1"
        private const val CIPHERTEXT = "session_ciphertext"
        private const val IV = "session_iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        internal fun deleteKeyForTests() {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            store.deleteEntry(KEY_ALIAS)
        }

        internal fun resetForTests(context: Context) {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
            deleteKeyForTests()
        }

        internal fun encryptedEnvelopeForTests(context: Context): Map<String, String?> {
            val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return mapOf(CIPHERTEXT to preferences.getString(CIPHERTEXT, null), IV to preferences.getString(IV, null))
        }
    }
}

class SyncSessionUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
