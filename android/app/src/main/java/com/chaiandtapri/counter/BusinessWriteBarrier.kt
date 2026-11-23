package com.chaiandtapri.counter

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide barrier whose shared leases may be closed by a different coroutine dispatcher thread. */
object BusinessWriteBarrier {
    private val stateMonitor = Object()
    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()
    private val activeOperationContext = ThreadLocal<BusinessOperationPermit?>()
    @Volatile private var maintenance: String? = null
    private var activeBusinessOperations = 0
    private var maintenanceRequests = 0
    private var maintenanceOwner: Thread? = null
    private var maintenanceDepth = 0

    fun currentMaintenance(): String? = maintenance

    fun addListener(listener: (String?) -> Unit) {
        listeners += listener
        try { listener(maintenance) } catch (_: Exception) { }
    }

    fun removeListener(listener: (String?) -> Unit) { listeners -= listener }

    /** Shared lease for an operation that must finish before snapshot/restore begins. */
    fun tryBeginBusinessOperation(): BusinessOperationPermit? = synchronized(stateMonitor) {
        if (maintenanceRequests > 0 || maintenanceOwner != null) return@synchronized null
        activeBusinessOperations++
        BusinessOperationPermit {
            synchronized(stateMonitor) {
                check(activeBusinessOperations > 0) { "Business operation lease was released more than once." }
                activeBusinessOperations--
                stateMonitor.notifyAll()
            }
        }
    }

    /** Compatibility name for business mutations that require the shared lease. */
    fun tryBeginCommit(): AutoCloseable? {
        if (activeOperationContext.get()?.isActive == true) return AutoCloseable { }
        return tryBeginBusinessOperation()
    }

    /** Install an admitted operation lease across coroutine dispatcher/thread switches. */
    fun operationContext(permit: BusinessOperationPermit): ThreadContextElement<BusinessOperationPermit?> =
        activeOperationContext.asContextElement(permit)

    fun <T> duringMaintenance(label: String, action: () -> T): T {
        val currentThread = Thread.currentThread()
        var previousMaintenance: String? = null
        synchronized(stateMonitor) {
            maintenanceRequests++
            try {
                while (activeBusinessOperations > 0 || (maintenanceOwner != null && maintenanceOwner !== currentThread)) {
                    stateMonitor.wait()
                }
                if (maintenanceOwner == null) maintenanceOwner = currentThread
                maintenanceDepth++
                previousMaintenance = maintenance
                maintenance = label
            } catch (interrupted: InterruptedException) {
                maintenanceRequests--
                stateMonitor.notifyAll()
                currentThread.interrupt()
                throw IllegalStateException("Interrupted while waiting for business operations to finish.", interrupted)
            }
        }
        notifyListeners(label)
        try {
            return action()
        } finally {
            synchronized(stateMonitor) {
                maintenanceDepth--
                maintenance = previousMaintenance
                notifyListeners(previousMaintenance)
                if (maintenanceDepth == 0) maintenanceOwner = null
                maintenanceRequests--
                stateMonitor.notifyAll()
            }
        }
    }

    private fun notifyListeners(value: String?) {
        listeners.forEach { listener -> try { listener(value) } catch (_: Exception) { } }
    }
}

class BusinessOperationPermit internal constructor(private val onClose: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    internal val isActive: Boolean get() = !closed.get()
    override fun close() {
        check(closed.compareAndSet(false, true)) { "Business operation lease was released more than once." }
        onClose()
    }
}

internal class BusinessMaintenanceException : IllegalStateException("Backup or recovery is in progress.")
