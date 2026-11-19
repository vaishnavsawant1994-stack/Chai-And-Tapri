package com.chaiandtapri.counter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local SQLCipher passphrase envelope. The wrapping key never leaves Android Keystore. */
internal class SecureDatabaseKeyManager(context: Context, private val databaseFile: File, private val keyNamespace: String = "default") {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("$PREFERENCES.$keyNamespace", Context.MODE_PRIVATE)
    private val defaultKeyAlias get() = "$KEY_ALIAS.$keyNamespace"
    private val keyAlias get() = preferences.getString(WRAPPING_ALIAS, defaultKeyAlias) ?: defaultKeyAlias

    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        val wrapped = preferences.getString(WRAPPED_KEY, null)
        if (wrapped != null) {
            val iv = preferences.getString(IV, null) ?: throw DatabaseKeyUnavailableException("Stored database key envelope is incomplete.")
            val wrappingKey = findWrappingKey() ?: throw DatabaseKeyUnavailableException("Device database key is missing. Preserve this database and use the documented recovery path.")
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, android.util.Base64.decode(iv, android.util.Base64.NO_WRAP)))
                cipher.doFinal(android.util.Base64.decode(wrapped, android.util.Base64.NO_WRAP))
            } catch (error: AEADBadTagException) {
                throw DatabaseKeyUnavailableException("Device database key could not authenticate. Data was not reset or deleted.", error)
            } catch (error: GeneralSecurityException) {
                throw DatabaseKeyUnavailableException("Device database key is unavailable. Data was not reset or deleted.", error)
            }
        }

        if (databaseFile.exists() && !isLegacyPlaintextDatabase(databaseFile)) {
            throw DatabaseKeyUnavailableException("Database exists but its device key envelope is missing. Data was not reset or deleted.")
        }

        val alias = keyAlias
        val wrappingKey = createWrappingKey(alias)
        val passphrase = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val ciphertext = android.util.Base64.encodeToString(cipher.doFinal(passphrase), android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
            if (!preferences.edit().putString(WRAPPING_ALIAS, alias).putString(WRAPPED_KEY, ciphertext).putString(IV, iv).commit()) {
                passphrase.fill(0)
                throw DatabaseKeyUnavailableException("Could not persist the protected database-key envelope.")
            }
            return passphrase
        } catch (error: GeneralSecurityException) {
            passphrase.fill(0)
            throw DatabaseKeyUnavailableException("Could not provision the device database key.", error)
        }
    }

    private fun findWrappingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun createWrappingKey(): SecretKey {
        return createWrappingKey(keyAlias)
    }

    private fun createWrappingKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build())
                generateKey()
            }
        } catch (error: GeneralSecurityException) {
            throw DatabaseKeyUnavailableException("Android Keystore could not create the device database key.", error)
        }
    }

    /** Captures only the encrypted envelope metadata; the Keystore key itself is never exported. */
    fun envelopeSnapshot(): DeviceKeyEnvelope? {
        val wrapped = preferences.getString(WRAPPED_KEY, null) ?: return null
        val iv = preferences.getString(IV, null) ?: return null
        return DeviceKeyEnvelope(keyAlias, wrapped, iv)
    }

    /** Creates a fresh, inactive device key for an explicitly confirmed restore over an unreadable store. */
    fun createRecoveryPassphrase(): Pair<ByteArray, DeviceKeyEnvelope> {
        val alias = "$KEY_ALIAS.$keyNamespace.recovery.${java.util.UUID.randomUUID()}"
        val wrappingKey = createWrappingKey(alias)
        val passphrase = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val envelope = DeviceKeyEnvelope(
                alias,
                android.util.Base64.encodeToString(cipher.doFinal(passphrase), android.util.Base64.NO_WRAP),
                android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
            )
            passphrase to envelope
        } catch (error: GeneralSecurityException) {
            passphrase.fill(0)
            throw DatabaseKeyUnavailableException("Could not provision a replacement device key for confirmed recovery.", error)
        }
    }

    /** Activates or restores an encrypted envelope. Callers journal the previous envelope before switching. */
    fun activateEnvelope(envelope: DeviceKeyEnvelope?) {
        val editor = preferences.edit()
        if (envelope == null) editor.remove(WRAPPING_ALIAS).remove(WRAPPED_KEY).remove(IV)
        else editor.putString(WRAPPING_ALIAS, envelope.alias).putString(WRAPPED_KEY, envelope.wrappedKey).putString(IV, envelope.iv)
        check(editor.commit()) { "Could not persist the device database-key envelope." }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "chai_tapri_sqlcipher_wrap_v1"
        private const val PREFERENCES = "chai_tapri_secure_key_envelope"
        private const val WRAPPED_KEY = "wrapped_database_key"
        private const val IV = "database_key_iv"
        private const val WRAPPING_ALIAS = "database_key_alias"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32

        internal fun removeWrappingKeyForTests(namespace: String) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry("$KEY_ALIAS.$namespace")
        }

        internal fun isLegacyPlaintextDatabase(file: File): Boolean = try {
            file.inputStream().use { input ->
                val signature = ByteArray(16)
                input.read(signature) == signature.size && signature.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
            }
        } catch (_: Exception) { false }
    }
}

internal data class DeviceKeyEnvelope(val alias: String, val wrappedKey: String, val iv: String)

internal class DatabaseKeyUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
