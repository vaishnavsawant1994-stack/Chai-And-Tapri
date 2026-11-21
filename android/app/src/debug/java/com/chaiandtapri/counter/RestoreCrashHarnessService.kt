package com.chaiandtapri.counter

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File
import java.io.FileOutputStream

/** Debug-only destructive harness for exercising restore recovery after a real process death. */
internal class RestoreCrashHarnessService : Service() {
    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            val token = message.data.getString(EXTRA_TOKEN) ?: error("Missing crash-test token.")
            when (message.what) {
                WHAT_CRASH_AFTER_PRESERVE -> crashAfterPreserve(
                    message.data.getString(EXTRA_DATABASE_NAME) ?: error("Missing database name."), token
                )
                WHAT_RECOVER_AND_REPORT -> recoverAndReport(token)
                else -> error("Unsupported restore crash checkpoint.")
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun crashAfterPreserve(databaseName: String, token: String) {
        check(databaseName == SqliteCounterPersistence.DATABASE_NAME) { "Only the synthetic default test database is allowed." }
        check(token.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid crash-test token." }

        val active = getDatabasePath(databaseName)
        val previous = getDatabasePath("$databaseName.pre-restore")
        check(active.exists() && !previous.exists()) { "Crash checkpoint requires one active database and no previous copy." }
        check(moveDatabaseFamily(active, previous)) { "Could not preserve the active database for the crash drill." }
        RestoreRecoveryJournal.markPending(this, databaseName, "process-crash-$token")

        val marker = File(cacheDir, "restore-process-crash-$token.marker")
        FileOutputStream(marker).use { stream ->
            stream.write(Process.myPid().toString().toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
        }
        // Leave a short window for the test to unbind. The delayed kill then models abrupt
        // process death without Android treating the service as a still-bound restart request.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1_000)
    }

    private fun recoverAndReport(token: String) {
        check(token.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid crash-test token." }
        val model = CounterModel(SqliteCounterPersistence(this))
        try {
            val order = model.allOrders().single()
            val pending = RestoreRecoveryJournal.preferences(this)
                .getString("pending_restore:${SqliteCounterPersistence.DATABASE_NAME}", null)
            val report = listOf(
                order.number.toString(),
                order.salePaise.toString(),
                model.auditEvents().any { it.contains("real-process-crash-original") }.toString(),
                (pending == null).toString(),
                getDatabasePath("${SqliteCounterPersistence.DATABASE_NAME}.pre-restore").exists().toString()
            ).joinToString("\n")
            FileOutputStream(File(cacheDir, "restore-process-recovered-$token.report")).use { stream ->
                stream.write(report.toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
        } finally {
            model.close()
        }
    }

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
        const val WHAT_CRASH_AFTER_PRESERVE = 1
        const val WHAT_RECOVER_AND_REPORT = 2
        const val EXTRA_DATABASE_NAME = "database_name"
        const val EXTRA_TOKEN = "test_token"
    }
}
