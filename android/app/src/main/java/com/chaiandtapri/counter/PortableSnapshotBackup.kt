package com.chaiandtapri.counter

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Portable, password-protected SQLCipher snapshot. The caller must pause counter writes during export/restore. */
class PortableSnapshotBackup(
    context: Context,
    private val databaseName: String = SqliteCounterPersistence.DATABASE_NAME,
    private val keyNamespace: String = "default",
    private val faultInjector: BackupFaultInjector = BackupFaultInjector { }
) {
    private val appContext = context.applicationContext

    /** Exports an authenticated SQLCipher database snapshot; the caller supplies a private SAF/document stream. */
    fun export(output: OutputStream, recoverySecret: CharArray) =
        try {
            if (databaseName == SqliteCounterPersistence.DATABASE_NAME && keyNamespace == "default") SqliteCounterPersistence(appContext).close()
            BusinessWriteBarrier.duringMaintenance("Creating encrypted backup") { exportLocked(output, recoverySecret) }
        } finally { recoverySecret.fill('\u0000') }

    private fun exportLocked(output: OutputStream, recoverySecret: CharArray) {
        try {
        validateSecret(recoverySecret)
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val backupId = ByteArray(BACKUP_ID_BYTES).also(SecureRandom()::nextBytes)
        val derivedKey = derive(recoverySecret, salt)
        val tempName = "$databaseName.portable-export"
        val temp = appContext.getDatabasePath(tempName)
        deleteFamily(temp)
        val deviceKey = SecureDatabaseKeyManager(appContext, appContext.getDatabasePath(databaseName), keyNamespace).getOrCreatePassphrase()
        val activeHelper = SqliteCounterPersistence(appContext, deviceKey, databaseName, appContext, false, CounterPersistenceFaultInjector { })
        val db = activeHelper.writableDatabase
        val shopIdentity = activeHelper.shopIdentity()
        activeHelper.bindShopIdentity()
        try {
            attachAndExport(db, temp, derivedKey)
            val portable = SqliteCounterPersistence(appContext, derivedKey.copyOf(), tempName, appContext, false, CounterPersistenceFaultInjector { })
            try {
                verifyBusiness(portable)
                require(portable.shopIdentity() == shopIdentity) { "Backup shop identity did not match the encrypted snapshot." }
            } finally { portable.close() }
            val header = makeHeaderV3(salt, backupId, shopIdentity)
            val out = DataOutputStream(BufferedOutputStream(output))
            out.write(header)
            out.write(headerMac(derivedKey, header))
            require(temp.length() <= MAX_DATABASE_BYTES) { "Snapshot exceeds the supported 512 MiB portable-backup limit." }
            temp.inputStream().use { input -> input.copyBoundedTo(out, MAX_DATABASE_BYTES) }
            out.flush()
        } finally {
            activeHelper.close()
            derivedKey.fill(0)
            deleteFamily(temp)
        }
        } finally { recoverySecret.fill('\u0000') }
    }

    /** Imports only after explicit replacement confirmation. A wrong key/corrupt snapshot never touches active data. */
    fun restore(input: InputStream, recoverySecret: CharArray, confirmReplace: Boolean) =
        BusinessWriteBarrier.duringMaintenance("Restoring encrypted backup") { restoreLocked(input, recoverySecret, confirmReplace) }

    private fun restoreLocked(input: InputStream, recoverySecret: CharArray, confirmReplace: Boolean) {
      try {
        require(confirmReplace) { "Restore replaces this device's local shop snapshot; explicit confirmation is required." }
        validateSecret(recoverySecret)
        val tempPortableName = "$databaseName.portable-import"
        val tempLocalName = "$databaseName.restore-staged"
        val tempPortable = appContext.getDatabasePath(tempPortableName)
        val tempLocal = appContext.getDatabasePath(tempLocalName)
        deleteFamily(tempPortable); deleteFamily(tempLocal)
        var portableKey = ByteArray(0)
        var localKey = ByteArray(0)
        var keyRotation: RestoreKeyRotation? = null
        try {
            val container = readContainerToFile(input, tempPortable, recoverySecret)
            val idText = android.util.Base64.encodeToString(container.backupId, android.util.Base64.NO_WRAP)
            require(!RestoreRecoveryJournal.wasImported(appContext, databaseName, idText)) { "This backup snapshot was already restored on this installation." }
            portableKey = derive(recoverySecret, container.salt)
            val portableHelper = SqliteCounterPersistence(appContext, portableKey.copyOf(), tempPortableName, appContext, false, CounterPersistenceFaultInjector { })
            var importedShopIdentity = ""
            try {
                verifyBusiness(portableHelper)
                importedShopIdentity = portableHelper.shopIdentity()
                require(container.shopIdentity == null || container.shopIdentity == importedShopIdentity) { "Backup shop identity does not match the encrypted shop data." }
                val portable = portableHelper.writableDatabase
                val keyManager = SecureDatabaseKeyManager(appContext, appContext.getDatabasePath(databaseName), keyNamespace)
                localKey = try {
                    keyManager.getOrCreatePassphrase()
                } catch (unavailable: DatabaseKeyUnavailableException) {
                    if (!appContext.getDatabasePath(databaseName).exists()) throw unavailable
                    require(container.shopIdentity != null) { "An unreadable local store can only be replaced by a backup with an authenticated shop identity." }
                    val boundIdentity = SqliteCounterPersistence.locallyBoundShopIdentity(appContext, databaseName, keyNamespace)
                        ?: throw IllegalStateException("This installation has no trusted local shop-identity recovery binding. Preserve the database and use a separately verified recovery process.", unavailable)
                    require(boundIdentity == importedShopIdentity && boundIdentity == container.shopIdentity) {
                        "Backup shop identity does not match this installation's local recovery binding. Existing encrypted data was preserved."
                    }
                    val (replacementPassphrase, replacementEnvelope) = keyManager.createRecoveryPassphrase()
                    keyRotation = RestoreKeyRotation(
                        keyManager.envelopeSnapshot(), replacementEnvelope,
                        "$databaseName.key-lost-${java.util.UUID.randomUUID()}", keyNamespace
                    )
                    replacementPassphrase
                }
                attachAndExport(portable, tempLocal, localKey)
                val stage = SqliteCounterPersistence(appContext, localKey.copyOf(), tempLocalName, appContext, false, CounterPersistenceFaultInjector { })
                try {
                    verifyBusiness(stage)
                    require(stage.shopIdentity() == importedShopIdentity) { "Staged restore changed the shop identity." }
                } finally { stage.close() }
            } finally { portableHelper.close() }

            verifyRestoreTarget(importedShopIdentity, container.shopIdentity != null, keyRotation != null)

            cutOverRestoredDatabase(tempLocal, localKey, idText, keyRotation)
        } finally {
            portableKey.fill(0); localKey.fill(0)
            deleteFamily(tempPortable); deleteFamily(tempLocal)
        }
      } finally { recoverySecret.fill('\u0000') }
    }

    /** Full preflight verification for a user-selected snapshot. No active database files are replaced. */
    fun verify(input: InputStream, recoverySecret: CharArray): SnapshotMetadata =
        BusinessWriteBarrier.duringMaintenance("Verifying encrypted backup") { verifyLocked(input, recoverySecret) }

    private fun verifyLocked(input: InputStream, recoverySecret: CharArray): SnapshotMetadata {
        val tempName = "$databaseName.portable-verify"
        val temp = appContext.getDatabasePath(tempName)
        var key = ByteArray(0)
        try {
            validateSecret(recoverySecret)
            deleteFamily(temp)
            val container = readContainerToFile(input, temp, recoverySecret)
            key = derive(recoverySecret, container.salt)
            val helper = SqliteCounterPersistence(appContext, key.copyOf(), tempName, appContext, false, CounterPersistenceFaultInjector { })
            val shopIdentity = try {
                verifyBusiness(helper)
                helper.shopIdentity().also { identity -> require(container.shopIdentity == null || container.shopIdentity == identity) { "Backup shop identity does not match the encrypted shop data." } }
            } finally { helper.close() }
            // Do not label a valid archive as restorable until its authenticated shop
            // identity is compatible with the active local shop. The UI presents this
            // result before asking the owner to approve replacement.
            verifyRestoreTarget(shopIdentity, container.shopIdentity != null, allowUnreadablePreview = true)
            return SnapshotMetadata(SqliteCounterPersistence.DATABASE_VERSION, android.util.Base64.encodeToString(container.backupId, android.util.Base64.NO_WRAP).takeLast(8), temp.length(), shopIdentity.takeLast(8), container.formatVersion)
        } finally {
            key.fill(0)
            recoverySecret.fill('\u0000')
            deleteFamily(temp)
        }
    }

    private fun attachAndExport(source: SQLiteDatabase, targetFile: File, targetKey: ByteArray) {
        val path = targetFile.absolutePath.replace("'", "''")
        val key = targetKey.joinToString("") { "%02x".format(it) }
        try {
            source.execSQL("ATTACH DATABASE '$path' AS portable KEY \"x'$key'\"")
            source.rawQuery("SELECT sqlcipher_export('portable')", null).use { if (!it.moveToFirst()) error("SQLCipher export produced no result.") }
            source.execSQL("PRAGMA portable.user_version=${SqliteCounterPersistence.DATABASE_VERSION}")
            source.execSQL("DETACH DATABASE portable")
        } catch (error: Throwable) {
            try { source.execSQL("DETACH DATABASE portable") } catch (_: Exception) { }
            throw IllegalStateException("SQLCipher snapshot export failed; active database was not replaced.", error)
        }
    }

    private fun cutOverRestoredDatabase(staged: File, localKey: ByteArray, backupId: String, keyRotation: RestoreKeyRotation?) {
        val active = appContext.getDatabasePath(databaseName)
        val previous = appContext.getDatabasePath("$databaseName.pre-restore")
        val stageName = staged.name
        val stageHelper = SqliteCounterPersistence(appContext, localKey.copyOf(), stageName, appContext, false, CounterPersistenceFaultInjector { })
        try { verifyBusiness(stageHelper) } finally { stageHelper.close() }
        check(!previous.exists()) { "An earlier restore recovery file exists; preserve it and resolve recovery before another import." }
        RestoreRecoveryJournal.markPending(appContext, databaseName, backupId, keyRotation)
        val hadActive = active.exists()
        if (hadActive && !moveDatabaseFamily(active, previous)) {
            RestoreRecoveryJournal.clearPending(appContext, databaseName)
            throw IllegalStateException("Could not preserve current encrypted database before restore.")
        }
        faultInjector.at(BackupFaultPoint.AFTER_ACTIVE_PRESERVED)
        if (!moveDatabaseFamily(staged, active)) {
            if (hadActive) moveDatabaseFamily(previous, active)
            RestoreRecoveryJournal.clearPending(appContext, databaseName)
            throw IllegalStateException("Could not activate restored snapshot; previous database was restored.")
        }
        try {
            keyRotation?.let { SecureDatabaseKeyManager(appContext, active, keyNamespace).activateEnvelope(it.newEnvelope) }
            faultInjector.at(BackupFaultPoint.AFTER_DATABASE_REPLACED)
            val restored = SqliteCounterPersistence(appContext, localKey.copyOf(), databaseName, appContext, false, CounterPersistenceFaultInjector { })
            try { verifyBusiness(restored) } finally { restored.close() }
            RestoreRecoveryJournal.recordCommitted(appContext, databaseName, backupId)
        } catch (error: Throwable) {
            val failed = appContext.getDatabasePath("$databaseName.restore-failed")
            moveDatabaseFamily(active, failed)
            if (hadActive) moveDatabaseFamily(previous, active)
            keyRotation?.let { SecureDatabaseKeyManager(appContext, active, keyNamespace).activateEnvelope(it.oldEnvelope) }
            RestoreRecoveryJournal.clearPending(appContext, databaseName)
            throw IllegalStateException("Restored snapshot failed verification; previous data was restored.", error)
        }
        if (hadActive) {
            if (keyRotation == null) deleteFamily(previous)
            else check(moveDatabaseFamily(previous, appContext.getDatabasePath(keyRotation.archiveDatabaseName))) {
                "Restored shop is verified, but the inaccessible prior encrypted database could not be archived; preserve all files for recovery."
            }
        }
        RestoreRecoveryJournal.clearPending(appContext, databaseName)
    }

    private fun readContainerToFile(input: InputStream, destination: File, recoverySecret: CharArray): ContainerMetadata {
        check(destination.parentFile?.exists() == true || destination.parentFile?.mkdirs() == true) { "Could not create the private staging directory." }
        DataInputStream(BufferedInputStream(input)).use { source ->
            val magic = ByteArray(MAGIC_V2.size); source.readFully(magic)
            val formatVersion = when {
                magic.contentEquals(MAGIC_V2) -> 2
                magic.contentEquals(MAGIC_V3) -> 3
                else -> throw IllegalArgumentException("Unsupported or damaged backup header.")
            }
            val schemaVersion = source.readInt()
            require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..SqliteCounterPersistence.DATABASE_VERSION) { "Backup schema version is unsupported." }
            require(source.readInt() == PBKDF2_ITERATIONS) { "Backup key-derivation parameters are unsupported." }
            require(source.readInt() == SALT_BYTES) { "Backup salt length is invalid." }
            val salt = ByteArray(SALT_BYTES); source.readFully(salt)
            require(source.readInt() == BACKUP_ID_BYTES) { "Backup identifier is invalid." }
            val backupId = ByteArray(BACKUP_ID_BYTES); source.readFully(backupId)
            val shopIdentity = if (formatVersion == 3) ByteArray(SHOP_IDENTITY_BYTES).also(source::readFully).toString(StandardCharsets.US_ASCII) else null
            if (shopIdentity != null) require(runCatching { java.util.UUID.fromString(shopIdentity).toString() == shopIdentity }.getOrDefault(false)) { "Backup shop identity is invalid." }
            val key = derive(recoverySecret, salt)
            try {
                val suppliedMac = ByteArray(HEADER_MAC_BYTES); source.readFully(suppliedMac)
                val expectedHeader = if (formatVersion == 3) makeHeaderV3(salt, backupId, shopIdentity!!) else makeHeaderV2(salt, backupId)
                require(java.security.MessageDigest.isEqual(suppliedMac, headerMac(key, expectedHeader))) { "Backup header authentication failed." }
            } finally { key.fill(0) }
            faultInjector.wrapImportStaging(destination.outputStream()).buffered().use { target ->
                source.copyBoundedTo(target, MAX_DATABASE_BYTES)
            }
            require(destination.length() >= MIN_DATABASE_BYTES) { "Backup database payload is incomplete." }
            return ContainerMetadata(salt, backupId, shopIdentity, formatVersion, schemaVersion)
        }
    }

    private fun verifyRestoreTarget(
        importedShopIdentity: String,
        hasAuthenticatedContainerIdentity: Boolean,
        replacingUnreadableStore: Boolean = false,
        allowUnreadablePreview: Boolean = false
    ) {
        val active = appContext.getDatabasePath(databaseName)
        if (!active.exists()) return
        val helper = try { SqliteCounterPersistence(appContext, databaseName, keyNamespace) }
        catch (error: Exception) {
            if ((replacingUnreadableStore || allowUnreadablePreview) && hasAuthenticatedContainerIdentity) {
                val boundIdentity = SqliteCounterPersistence.locallyBoundShopIdentity(appContext, databaseName, keyNamespace)
                require(boundIdentity != null && boundIdentity == importedShopIdentity) {
                    "Cannot verify that this backup belongs to the unreadable local shop. Existing encrypted data was not changed."
                }
                return
            }
            throw error
        }
        try {
            val targetIdentity = helper.shopIdentity()
            if (helper.hasBusinessActivity()) {
                require(hasAuthenticatedContainerIdentity && targetIdentity == importedShopIdentity) {
                    "This backup belongs to a different or unverified shop identity. Active records were not changed."
                }
            }
        } finally { helper.close() }
    }

    private fun verify(db: SQLiteDatabase) {
        val cipher = db.rawQuery("PRAGMA cipher_version", null).use { it.moveToFirst() && it.getString(0).isNotBlank() }
        check(cipher) { "Snapshot is not encrypted with SQLCipher." }
        val integrity = db.rawQuery("PRAGMA integrity_check", null).use { if (it.moveToFirst()) it.getString(0) else "" }
        check(integrity == "ok") { "Snapshot integrity check failed." }
        check(db.version == SqliteCounterPersistence.DATABASE_VERSION) { "Snapshot database schema version does not match its container." }
    }

    private fun verifyBusiness(helper: SqliteCounterPersistence) {
        verify(helper.writableDatabase)
        val snapshot = (if (BusinessWriteBarrier.currentMaintenance() != null) helper.loadDuringMaintenance() else helper.load())
            ?: error("Snapshot has no business metadata record.")
        CounterSnapshotIntegrity.requireConsistent(snapshot)
    }

    private fun derive(secret: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret, salt, PBKDF2_ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    private fun makeHeaderV2(salt: ByteArray, backupId: ByteArray): ByteArray = java.io.ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC_V2); out.writeInt(MIN_SUPPORTED_SCHEMA_VERSION); out.writeInt(PBKDF2_ITERATIONS)
            out.writeInt(SALT_BYTES); out.write(salt); out.writeInt(BACKUP_ID_BYTES); out.write(backupId)
        }
        bytes.toByteArray()
    }

    private fun makeHeaderV3(salt: ByteArray, backupId: ByteArray, shopIdentity: String): ByteArray = java.io.ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC_V3); out.writeInt(SqliteCounterPersistence.DATABASE_VERSION); out.writeInt(PBKDF2_ITERATIONS)
            out.writeInt(SALT_BYTES); out.write(salt); out.writeInt(BACKUP_ID_BYTES); out.write(backupId)
            out.write(shopIdentity.toByteArray(StandardCharsets.US_ASCII))
        }
        bytes.toByteArray()
    }

    private fun headerMac(key: ByteArray, header: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(header)
    }

    private fun InputStream.copyBoundedTo(output: OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= limit) { "Portable snapshot exceeds the supported 512 MiB limit." }
            output.write(buffer, 0, count)
        }
    }

    private fun validateSecret(secret: CharArray) {
        require(secret.size >= 12) { "Use a recovery secret with at least 12 characters and keep it separately from the backup file." }
    }

    private fun deleteFamily(file: File) { listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() } }

    private fun moveDatabaseFamily(from: File, to: File): Boolean {
        val moved = mutableListOf<Pair<File, File>>()
        for (suffix in listOf("-wal", "-shm", "-journal", "")) {
            val source = File(from.path + suffix)
            if (!source.exists()) continue
            val destination = File(to.path + suffix)
            if (!source.renameTo(destination)) {
                moved.asReversed().forEach { (old, new) -> new.renameTo(old) }
                return false
            }
            moved += source to destination
        }
        return true
    }

    companion object {
        private val MAGIC_V2 = "CTBKUP02".toByteArray(StandardCharsets.US_ASCII)
        private val MAGIC_V3 = "CTBKUP03".toByteArray(StandardCharsets.US_ASCII)
        private const val MIN_SUPPORTED_SCHEMA_VERSION = 5
        private const val PBKDF2_ITERATIONS = 310_000
        private const val SALT_BYTES = 16
        private const val BACKUP_ID_BYTES = 16
        private const val SHOP_IDENTITY_BYTES = 36
        private const val HEADER_MAC_BYTES = 32
        private const val MIN_DATABASE_BYTES = 4096L
        private const val MAX_DATABASE_BYTES = 512L * 1024L * 1024L
    }
}

data class SnapshotMetadata(val schemaVersion: Int, val snapshotIdSuffix: String, val encryptedPayloadBytes: Long, val shopIdentitySuffix: String, val formatVersion: Int)

private data class ContainerMetadata(val salt: ByteArray, val backupId: ByteArray, val shopIdentity: String?, val formatVersion: Int, val schemaVersion: Int)

enum class BackupFaultPoint { AFTER_ACTIVE_PRESERVED, AFTER_DATABASE_REPLACED }
fun interface BackupFaultInjector {
    fun at(point: BackupFaultPoint)

    /** Test seam for deterministic short-write/ENOSPC behavior while staging an authenticated import. */
    fun wrapImportStaging(output: OutputStream): OutputStream = output
}
