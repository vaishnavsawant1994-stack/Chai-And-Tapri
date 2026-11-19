package com.chaiandtapri.counter

import android.content.Context
import java.io.File

/** Disk-backed cutover journal. A startup retry either keeps a verified committed restore or rolls back. */
internal object RestoreRecoveryJournal {
    private const val PREFERENCES = "chai_tapri_backup_restore_state"
    private const val IMPORTED_IDS = "imported_snapshots"
    private fun pendingKey(databaseName: String) = "pending_restore:$databaseName"
    private fun importedKey(databaseName: String) = "$IMPORTED_IDS:$databaseName"
    private fun rotationKey(databaseName: String, field: String) = "pending_key_rotation:$databaseName:$field"

    fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun markPending(context: Context, databaseName: String, id: String, keyRotation: RestoreKeyRotation? = null) {
        val edit = preferences(context).edit().putString(pendingKey(databaseName), id)
        if (keyRotation != null) {
            edit.putString(rotationKey(databaseName, "archive"), keyRotation.archiveDatabaseName)
            edit.putString(rotationKey(databaseName, "namespace"), keyRotation.keyNamespace)
            writeEnvelope(edit, rotationKey(databaseName, "old"), keyRotation.oldEnvelope)
            writeEnvelope(edit, rotationKey(databaseName, "new"), keyRotation.newEnvelope)
        }
        check(edit.commit()) { "Could not persist restore recovery journal." }
    }

    fun recordCommitted(context: Context, databaseName: String, id: String) {
        val prefs = preferences(context)
        val imported = prefs.getStringSet(importedKey(databaseName), emptySet()).orEmpty().toMutableSet().apply { add(id) }
        val edit = prefs.edit().putStringSet(importedKey(databaseName), imported)
        if (databaseName == SqliteCounterPersistence.DATABASE_NAME) {
            val legacy = prefs.getStringSet(IMPORTED_IDS, emptySet()).orEmpty().toMutableSet().apply { add(id) }
            edit.putStringSet(IMPORTED_IDS, legacy)
        }
        check(edit.commit()) { "Could not persist backup replay protection." }
    }

    fun clearPending(context: Context, databaseName: String) {
        check(preferences(context).edit().remove(pendingKey(databaseName))
            .remove(rotationKey(databaseName, "archive"))
            .remove(rotationKey(databaseName, "namespace"))
            .remove(rotationKey(databaseName, "old_alias")).remove(rotationKey(databaseName, "old_wrapped")).remove(rotationKey(databaseName, "old_iv"))
            .remove(rotationKey(databaseName, "new_alias")).remove(rotationKey(databaseName, "new_wrapped")).remove(rotationKey(databaseName, "new_iv"))
            .commit()) { "Restore completed but its recovery journal could not be cleared; startup recovery will finish it." }
    }

    fun wasImported(context: Context, databaseName: String, id: String): Boolean {
        val prefs = preferences(context)
        return id in prefs.getStringSet(importedKey(databaseName), emptySet()).orEmpty() ||
            (databaseName == SqliteCounterPersistence.DATABASE_NAME && id in prefs.getStringSet(IMPORTED_IDS, emptySet()).orEmpty())
    }

    fun recover(context: Context, databaseName: String) {
        val prefs = preferences(context)
        val key = pendingKey(databaseName)
        val active = context.getDatabasePath(databaseName)
        val previous = context.getDatabasePath("$databaseName.pre-restore")
        val pending = prefs.getString(key, null)
        if (pending == null) {
            if (previous.exists() && !active.exists()) check(moveFamily(previous, active)) { "Previous encrypted database could not be restored." }
            else check(!previous.exists()) { "Unresolved restore recovery files exist. Database access is blocked to preserve both copies." }
            return
        }
        val committed = wasImported(context, databaseName, pending)
        val keyRotation = readRotation(prefs, databaseName)
        if (committed && active.exists()) {
            keyRotation?.let { SecureDatabaseKeyManager(context, active, it.keyNamespace).activateEnvelope(it.newEnvelope) }
            if (previous.exists()) {
                if (keyRotation == null) deleteFamily(previous)
                else check(moveFamily(previous, context.getDatabasePath(keyRotation.archiveDatabaseName))) { "Previous encrypted database could not be retained after verified key recovery." }
            }
        } else if (previous.exists()) {
            val interrupted = context.getDatabasePath("$databaseName.restore-interrupted")
            deleteFamily(interrupted)
            if (active.exists()) check(moveFamily(active, interrupted)) { "Interrupted restore files could not be preserved for recovery." }
            check(moveFamily(previous, active)) { "Previous encrypted database could not be restored after interrupted replacement." }
            keyRotation?.let { SecureDatabaseKeyManager(context, active, it.keyNamespace).activateEnvelope(it.oldEnvelope) }
        } else if (!active.exists()) {
            // No old database existed (clean install); the caller's portable backup remains untouched.
            deleteFamily(context.getDatabasePath("$databaseName.restore-staged"))
            keyRotation?.let { SecureDatabaseKeyManager(context, active, it.keyNamespace).activateEnvelope(it.oldEnvelope) }
        } else {
            // A journal written before the active database was moved can be cleared with its old key still active.
            keyRotation?.let { SecureDatabaseKeyManager(context, active, it.keyNamespace).activateEnvelope(it.oldEnvelope) }
        }
        check(prefs.edit().remove(key).commit()) { "Restore recovery is incomplete; no database was reset." }
    }

    private fun deleteFamily(file: File) { listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() } }
    private fun writeEnvelope(edit: android.content.SharedPreferences.Editor, prefix: String, envelope: DeviceKeyEnvelope?) {
        edit.putString("${prefix}_alias", envelope?.alias)
            .putString("${prefix}_wrapped", envelope?.wrappedKey)
            .putString("${prefix}_iv", envelope?.iv)
    }
    private fun readEnvelope(prefs: android.content.SharedPreferences, prefix: String): DeviceKeyEnvelope? {
        val alias = prefs.getString("${prefix}_alias", null) ?: return null
        val wrapped = prefs.getString("${prefix}_wrapped", null) ?: return null
        val iv = prefs.getString("${prefix}_iv", null) ?: return null
        return DeviceKeyEnvelope(alias, wrapped, iv)
    }
    private fun readRotation(prefs: android.content.SharedPreferences, databaseName: String): RestoreKeyRotation? {
        val archive = prefs.getString(rotationKey(databaseName, "archive"), null) ?: return null
        val replacement = readEnvelope(prefs, rotationKey(databaseName, "new")) ?: return null
        val namespace = prefs.getString(rotationKey(databaseName, "namespace"), null) ?: "default"
        return RestoreKeyRotation(readEnvelope(prefs, rotationKey(databaseName, "old")), replacement, archive, namespace)
    }
    private fun moveFamily(from: File, to: File): Boolean {
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
}

internal data class RestoreKeyRotation(
    val oldEnvelope: DeviceKeyEnvelope?,
    val newEnvelope: DeviceKeyEnvelope,
    val archiveDatabaseName: String,
    val keyNamespace: String
)
