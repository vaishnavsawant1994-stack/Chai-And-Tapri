package com.chaiandtapri.counter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Process-wide barrier: active business operations take a shared permit; snapshot/restore is exclusive. */
object BusinessWriteBarrier {
    private val lock = ReentrantReadWriteLock(true)
    private val admissionGate = Any()
    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()
    @Volatile private var maintenance: String? = null
    private var maintenanceRequests = 0

    fun currentMaintenance(): String? = maintenance

    fun addListener(listener: (String?) -> Unit) {
        listeners += listener
        try { listener(maintenance) } catch (_: Exception) { }
    }

    fun removeListener(listener: (String?) -> Unit) { listeners -= listener }

    /** Shared lease for an operation that must finish before snapshot/restore begins. */
    fun tryBeginBusinessOperation(): AutoCloseable? = synchronized(admissionGate) {
        if (maintenanceRequests > 0) return@synchronized null
        val permit = lock.readLock()
        if (!permit.tryLock()) return@synchronized null
        AutoCloseable { permit.unlock() }
    }

    /** Compatibility name for business mutations that require the shared lease. */
    fun tryBeginCommit(): AutoCloseable? = tryBeginBusinessOperation()

    fun <T> duringMaintenance(label: String, action: () -> T): T {
        synchronized(admissionGate) { maintenanceRequests++ }
        val permit = lock.writeLock()
        try {
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
        } finally {
            synchronized(admissionGate) { maintenanceRequests-- }
        }
    }

    private fun notifyListeners(value: String?) {
        listeners.forEach { listener -> try { listener(value) } catch (_: Exception) { } }
    }
}

internal class BusinessMaintenanceException : IllegalStateException("Backup or recovery is in progress.")
