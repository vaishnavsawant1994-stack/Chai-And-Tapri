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

data class SyncSession(val endpointBase: String, val identity: SyncEnvelopeIdentity, val bearerToken: String)

/** Stores only a server-issued bearer session, encrypted with an app/device-bound Android Keystore key. */
class SyncSessionVault(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun save(session: SyncSession) {
        validate(session)
        val plaintext = JSONObject()
            .put("endpointBase", session.endpointBase.trim().trimEnd('/'))
            .put("businessId", session.identity.businessId)
            .put("shopId", session.identity.shopId)
            .put("deviceId", session.identity.deviceId)
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
                bearerToken = json.getString("bearerToken")
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
        keyStore.deleteEntry(KEY_ALIAS)
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
    }

    private fun loadOrCreateKey(): SecretKey = loadExistingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
        init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        generateKey()
    }

    private fun loadExistingKey(): SecretKey? = KeyStore.getInstance(ANDROID_KEYSTORE).run {
        load(null)
        getKey(KEY_ALIAS, null) as? SecretKey
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
