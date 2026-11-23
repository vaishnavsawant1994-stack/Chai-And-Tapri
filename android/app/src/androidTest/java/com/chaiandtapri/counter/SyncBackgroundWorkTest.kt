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

    @Test fun workerIsIdleWithoutPendingBusinessEvents() = runBlocking {
        SyncSessionVault(context).clear()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        val result = TestListenableWorkerBuilder<SyncOutboxWorker>(context).build().doWork()
        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("idle", result.outputData.getString(SyncOutboxWorker.OUTPUT_STATE))
    }

    @Test fun workerRequiresSessionForTheAttributedOutboxOperator(): Unit { runBlocking {
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        SyncSessionVault(context).clear()
        LocalSessionState.establish("Synthetic owner")
        SqliteCounterPersistence(context).use { persistence ->
            CounterModel(persistence, requireConfiguredProducts = true).use { model ->
                assertTrue(model.saveProduct(TapriProduct("sync_worker_tea", "Worker Tea", "Chai", 1000, priceConfirmed = true)).startsWith("Product saved"))
                assertTrue(model.confirmOpeningStock("sync_worker_tea", 2, "synthetic worker test", "worker-opening-stock").startsWith("Opening stock confirmed"))
            }
        }
        LocalSessionState.clear()
        val result = TestListenableWorkerBuilder<SyncOutboxWorker>(context).build().doWork()
        assertTrue(result is androidx.work.ListenableWorker.Result.Failure)
        assertEquals("operator_session_required", result.outputData.getString(SyncOutboxWorker.OUTPUT_STATE))
        LocalSessionState.clear()
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
    } }

    @Test fun workerRejectsCloudStaffSessionBoundAsLocalOwnerBeforeNetworkAccess(): Unit { runBlocking {
        context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        LocalSessionState.establish("Synthetic owner")
        SqliteCounterPersistence(context).use { persistence ->
            CounterModel(persistence, requireConfiguredProducts = true).use { model ->
                assertTrue(model.saveProduct(TapriProduct("sync_worker_owner", "Worker Owner Tea", "Chai", 1000, priceConfirmed = true)).startsWith("Product saved"))
                assertTrue(model.confirmOpeningStock("sync_worker_owner", 2, "synthetic worker test", "worker-owner-opening-stock").startsWith("Opening stock confirmed"))
            }
        }
        LocalSessionState.clear()
        SyncSessionVault(context).save(SyncSession(
            "https://sync.example.test",
            SyncEnvelopeIdentity(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "33333333-3333-4333-8333-333333333333"
            ),
            "synthetic-staff-token",
            localActorId = "local-owner",
            userId = "44444444-4444-4444-8444-444444444444",
            role = SyncAccountRole.CASHIER
        ))
        try {
            val result = TestListenableWorkerBuilder<SyncOutboxWorker>(context).build().doWork()
            assertTrue(result is androidx.work.ListenableWorker.Result.Failure)
            assertEquals("owner_identity_required", result.outputData.getString(SyncOutboxWorker.OUTPUT_STATE))
        } finally {
            SyncSessionVault(context).clear()
            LocalSessionState.clear()
            context.deleteDatabase(SqliteCounterPersistence.DATABASE_NAME)
        }
    } }

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
