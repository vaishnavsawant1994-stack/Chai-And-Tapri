package com.chaiandtapri.counter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncBackgroundWorkTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun workerFailsClosedWithoutSavedOwnerSession() = runBlocking {
        SyncSessionVault(context).clear()
        val result = TestListenableWorkerBuilder<SyncOutboxWorker>(context).build().doWork()
        assertTrue(result is androidx.work.ListenableWorker.Result.Failure)
        assertEquals("session_required", result.outputData.getString(SyncOutboxWorker.OUTPUT_STATE))
    }

    @Test fun schedulerUsesConnectedNetworkAndUniqueDurableRequests() {
        val manager = WorkManager.getInstance(context)
        SyncBackgroundScheduler.pause(context).forEach { it.result.get() }

        SyncBackgroundScheduler.enqueueOneTime(context).result.get()
        SyncBackgroundScheduler.enqueueOneTime(context).result.get()
        val oneTime = manager.getWorkInfosForUniqueWork(SyncBackgroundScheduler.ONE_TIME_NAME).get()
        assertEquals(1, oneTime.size)
        assertEquals(NetworkType.CONNECTED, oneTime.single().constraints.requiredNetworkType)

        SyncBackgroundScheduler.enablePeriodic(context).result.get()
        SyncBackgroundScheduler.enablePeriodic(context).result.get()
        val periodic = manager.getWorkInfosForUniqueWork(SyncBackgroundScheduler.PERIODIC_NAME).get()
        assertEquals(1, periodic.size)
        assertEquals(NetworkType.CONNECTED, periodic.single().constraints.requiredNetworkType)
        assertTrue(SyncBackgroundStatus.enabled(context))

        SyncBackgroundScheduler.pause(context).forEach { it.result.get() }
        assertFalse(SyncBackgroundStatus.enabled(context))
    }

    @Test fun businessMaintenanceCausesRetryWithoutOpeningSyncSession() {
        SyncSessionVault(context).clear()
        BusinessWriteBarrier.duringMaintenance("synthetic background sync test") {
            val result = runBlocking { TestListenableWorkerBuilder<SyncOutboxWorker>(context).build().doWork() }
            assertTrue(result is androidx.work.ListenableWorker.Result.Retry)
            assertEquals("Background sync will retry when its network and backoff conditions allow.", SyncBackgroundStatus.read(context))
        }
    }
}
