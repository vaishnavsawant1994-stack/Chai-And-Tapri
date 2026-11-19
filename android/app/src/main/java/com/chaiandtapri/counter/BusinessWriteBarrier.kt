package com.chaiandtapri.counter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Process-wide barrier: commits take a read permit; snapshot/restore holds the exclusive permit. */
object BusinessWriteBarrier {
    private val lock = ReentrantReadWriteLock(true)
    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()
    @Volatile private var maintenance: String? = null

    fun currentMaintenance(): String? = maintenance

    fun addListener(listener: (String?) -> Unit) {
        listeners += listener
        try { listener(maintenance) } catch (_: Exception) { }
    }

    fun removeListener(listener: (String?) -> Unit) { listeners -= listener }

    fun tryBeginCommit(): AutoCloseable? {
        val permit = lock.readLock()
        if (!permit.tryLock()) return null
        if (maintenance != null) {
            permit.unlock()
            return null
        }
        return AutoCloseable { permit.unlock() }
    }

    fun <T> duringMaintenance(label: String, action: () -> T): T {
        val permit = lock.writeLock()
        permit.lock()
        val previousMaintenance = maintenance
        maintenance = label
        notifyListeners(label)
        try { return action() }
        finally {
            // The write lock is reentrant. If a maintenance workflow invokes another
            // maintenance helper, keep the outer operation visible until it also exits.
            maintenance = previousMaintenance
            notifyListeners(previousMaintenance)
            permit.unlock()
        }
    }

    private fun notifyListeners(value: String?) {
        listeners.forEach { listener -> try { listener(value) } catch (_: Exception) { } }
    }
}

internal class BusinessMaintenanceException : IllegalStateException("Backup or recovery is in progress.")
