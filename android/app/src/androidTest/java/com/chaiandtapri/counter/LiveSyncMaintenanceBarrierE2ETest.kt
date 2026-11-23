package com.chaiandtapri.counter

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Live loopback HTTPS/PostgreSQL proof that restore waits for an in-flight WorkManager sync lease. */
@RunWith(AndroidJUnit4::class)
class LiveSyncMaintenanceBarrierE2ETest {
    @Test fun maintenanceWaitsForCommittedSyncAckAndRejectsNewWorker() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("syncE2eEndpoint")
        val controlToken = args.getString("syncE2eControlToken")
        assumeTrue("Requires the isolated synthetic HTTPS/PostgreSQL endpoint.", !endpoint.isNullOrBlank())
        assumeTrue("Requires the test-only local control token.", !controlToken.isNullOrBlank())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val priorSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        trustTestServer()
        val executor = Executors.newSingleThreadExecutor()
        var session: SyncSession? = null
        var prepared = false
        var releaseNeeded = false
        try {
            assumeTrue("Preserve a saved owner session; use an isolated emulator.", SyncSessionVault(context).load() == null)
            assumeTrue("Preserve a registered cloud identity; use an isolated emulator.", SyncDeviceIdentityStore(context).load() == null)
            val work = WorkManager.getInstance(context)
            val existing = work.getWorkInfosForUniqueWork(SyncBackgroundScheduler.ONE_TIME_NAME).get(10, TimeUnit.SECONDS) +
                work.getWorkInfosForUniqueWork(SyncBackgroundScheduler.PERIODIC_NAME).get(10, TimeUnit.SECONDS)
            assumeTrue("Preserve active sync work; use an isolated emulator.", existing.none { !it.state.isFinished })
            assumeTrue("Preserve existing business records; use a clean synthetic emulator.", defaultStoreIsEmpty(context))
            prepared = true
            work.pruneWork().result.get(10, TimeUnit.SECONDS)

            val transport = HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 30_000)
            val deviceId = UUID.randomUUID().toString()
            session = SyncAccountClient(transport).register(
                endpoint!!,
                SyncRegistration("barrier-${UUID.randomUUID()}@example.test", "Synthetic-Only-Password-2026!",
                    "Synthetic Barrier Owner", "Synthetic Barrier Business", "Synthetic Barrier Shop", deviceId, "API 37 isolated emulator")
            )
            SyncSessionVault(context).save(session!!)
            context.getSharedPreferences("chai_tapri_sync_work_status_v1", Context.MODE_PRIVATE).edit().clear().commit()
            instrumentation.runOnMainSync { LocalSessionState.establish("Synthetic CAT-051B sync owner") }

            SqliteCounterPersistence(context).use { persistence ->
                val model = CounterModel(persistence, requireConfiguredProducts = true)
                assertTrue(model.saveProduct(TapriProduct("barrier_chai", "Barrier Test Chai", "Tea", 2_500,
                    active = true, favorite = true, priceConfirmed = true, estimatedUnitCostPaise = 1_000)).contains("saved"))
                assertTrue(model.confirmOpeningStock("barrier_chai", 10, "synthetic barrier test", "barrier-open-${UUID.randomUUID()}").contains("confirmed"))
                assertTrue(model.quickSale(listOf("barrier_chai" to 1), "cash", commandId = "barrier-sale-${UUID.randomUUID()}").startsWith("Quick sale"))
                assertEquals(2, persistence.loadPendingSyncEvents(20).size)
            }

            assertTrue(control(endpoint, controlToken!!, "/__test/reset", "POST").getBoolean("reset"))
            val before = control(endpoint, controlToken, "/__test/status", "GET")
            assertEquals(0, before.getInt("heldAckCount"))
            assertEquals(0, before.getInt("syncRequestCount"))
            SyncBackgroundScheduler.enqueueOneTime(context).result.get(10, TimeUnit.SECONDS)
            awaitControl(endpoint, controlToken, 30_000) { it.getInt("heldAckCount") == 1 }
            releaseNeeded = true
            assertEquals("The first outbox batch must be committed remotely before its response is held.", 1,
                ownerOverview(session!!).getInt("acceptedEventCount"))

            val maintenanceEntered = CountDownLatch(1)
            val maintenance = executor.submit(Callable {
                maintenanceEntered.countDown()
                BusinessWriteBarrier.duringMaintenance("Synthetic live sync/recovery race") {
                    assertEquals("Synthetic live sync/recovery race", BusinessWriteBarrier.currentMaintenance())
                    "maintenance acquired after sync acknowledgment"
                }
            })
            assertTrue(maintenanceEntered.await(2, TimeUnit.SECONDS))
            awaitAdmissionClosed()
            assertFalse("Maintenance must wait while the server response is held.", maintenance.isDone)

            val competing = runBlocking { TestListenableWorkerBuilder<SyncOutboxWorker>(context).build().doWork() }
            assertTrue("A new worker must retry while recovery is pending.", competing is ListenableWorker.Result.Retry)
            assertEquals("A denied worker must not issue another HTTP sync request.", 1,
                control(endpoint, controlToken, "/__test/status", "GET").getInt("syncRequestCount"))

            val released = control(endpoint, controlToken, "/__test/release-held-acks", "POST")
            releaseNeeded = false
            assertEquals(1, released.getInt("releasedAckCount"))
            val completed = waitForWork(context, 30_000) { it.state.isFinished }
            assertEquals(WorkInfo.State.SUCCEEDED, completed.state)
            assertEquals("synced", completed.outputData.getString(SyncOutboxWorker.OUTPUT_STATE))
            assertEquals(2, completed.outputData.getInt(SyncOutboxWorker.OUTPUT_ACKNOWLEDGED, -1))
            val final = control(endpoint, controlToken, "/__test/status", "GET")
            assertEquals("The worker should send both ordered outbox batches once the held acknowledgment is released.", 2,
                final.getInt("syncRequestCount"))
            assertEquals(0, final.getInt("heldAckCount"))
            assertEquals(2, ownerOverview(session!!).getInt("acceptedEventCount"))
            assertEquals("maintenance acquired after sync acknowledgment", maintenance.get(10, TimeUnit.SECONDS))
            SqliteCounterPersistence(context).use { persistence ->
                assertTrue(persistence.loadPendingSyncEvents(20).isEmpty())
                assertEquals(2L, persistence.loadSyncCursor())
                val model = CounterModel(persistence, requireConfiguredProducts = true)
                assertEquals(1, model.allOrders().size)
                assertEquals(9, model.stockLevels()["barrier_chai"])
            }
            val resumedPermit = BusinessWriteBarrier.tryBeginBusinessOperation()
            assertTrue("Barrier should be released after maintenance completes.", resumedPermit != null)
            resumedPermit?.close()
        } finally {
            if (releaseNeeded && !endpoint.isNullOrBlank() && !controlToken.isNullOrBlank()) {
                runCatching { control(endpoint, controlToken, "/__test/release-held-acks", "POST") }
            }
            executor.shutdownNow()
            runCatching { executor.awaitTermination(5, TimeUnit.SECONDS) }
            runCatching { SyncBackgroundScheduler.pause(context).forEach { it.result.get(10, TimeUnit.SECONDS) } }
            session?.let { runCatching { SyncAccountClient(HttpsSyncHttpTransport()).logout(it) } }
            runCatching { SyncSessionVault(context).clear() }
            instrumentation.runOnMainSync { LocalSessionState.clear() }
            if (prepared) clearSyntheticStore(context)
            HttpsURLConnection.setDefaultSSLSocketFactory(priorSocketFactory)
        }
    }

    private fun awaitAdmissionClosed() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val probe = BusinessWriteBarrier.tryBeginBusinessOperation()
            if (probe == null) return
            probe.close()
            android.os.SystemClock.sleep(25)
        }
        throw AssertionError("Recovery never closed admission while waiting for the held sync lease.")
    }

    private fun control(endpoint: String, token: String, path: String, method: String): JSONObject {
        val connection = URL("${endpoint.trimEnd('/')}$path").openConnection() as HttpsURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = method
        connection.setRequestProperty("x-test-control-token", token)
        if (method == "POST") connection.doOutput = true
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            assertEquals("Test control request $path", 200, status)
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun awaitControl(endpoint: String, token: String, timeoutMs: Long, predicate: (JSONObject) -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val status = control(endpoint, token, "/__test/status", "GET")
            if (predicate(status)) return
            android.os.SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for the synthetic server to hold the committed sync acknowledgment.")
    }

    private fun trustTestServer() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val cert = context.assets.open("tls/sync-test-server.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-barrier-e2e", cert) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory)
    }

    private fun ownerOverview(session: SyncSession): JSONObject {
        val connection = URL("${session.endpointBase.trimEnd('/')}/v1/dashboard/overview?shopId=${session.identity.shopId}").openConnection() as HttpsURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Authorization", "Bearer ${session.bearerToken}")
        try {
            val status = connection.responseCode
            assertEquals(200, status)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun defaultStoreIsEmpty(context: Context): Boolean = SqliteCounterPersistence(context).use { persistence ->
        val state = persistence.load()
        val empty = state == null || (state.orders.isEmpty() && state.commandIds.isEmpty() && state.auditEvents.isEmpty() &&
            state.stockMovements.isEmpty() && state.customerCredits.isEmpty() && state.creditRepayments.isEmpty() &&
            state.paymentEntries.isEmpty() && state.suppliers.isEmpty() && state.purchaseReceipts.isEmpty() &&
            state.supplierPayments.isEmpty() && state.purchaseReturns.isEmpty() && state.expenses.isEmpty() &&
            state.cashDrawerSessions.isEmpty() && state.cashDrawerMovements.isEmpty() && state.cashClosingReviews.isEmpty() &&
            state.purchaseOrderDrafts.isEmpty())
        val protected = persistence.writableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM staff_credentials) + (SELECT COUNT(*) FROM local_owner_credentials)", null
        ).use { it.moveToFirst() && it.getInt(0) > 0 }
        val seededProducts = setOf(
            TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1_000),
            TapriProduct("chai", "Regular Chai", "Chai", 1_500),
            TapriProduct("samosa", "Samosa", "Snacks", 2_000),
            TapriProduct("coffee", "Coffee", "Coffee", 2_500)
        )
        val productsAreOnlyUnconfiguredSeed = persistence.loadProducts().toSet() == seededProducts
        val stockIsOnlyZeroSeed = state?.stock?.all { (productId, quantity) ->
            productId in setOf("cutting_chai", "chai", "samosa", "coffee") && quantity == 0
        } ?: false
        empty && !protected && productsAreOnlyUnconfiguredSeed && stockIsOnlyZeroSeed &&
            persistence.loadAuditRecords().isEmpty() && persistence.loadPendingSyncEvents(20).isEmpty() &&
            persistence.loadCustomerProfiles().isEmpty() && persistence.loadShopProfile().shopName.isBlank() && !persistence.localOwnerExists()
    }

    private fun clearSyntheticStore(context: Context) {
        runCatching { SecureDatabaseKeyManager.removeWrappingKeyForTests("default") }
        context.getSharedPreferences("chai_tapri_secure_key_envelope.default", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("chai_tapri_counter.db")
        context.getSharedPreferences("chai_tapri_sync_work_status_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitForWork(context: Context, timeoutMs: Long, predicate: (WorkInfo) -> Boolean): WorkInfo {
        val manager = WorkManager.getInstance(context)
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val infos = manager.getWorkInfosForUniqueWork(SyncBackgroundScheduler.ONE_TIME_NAME).get(10, TimeUnit.SECONDS)
            infos.firstOrNull(predicate)?.let { return it }
            val failure = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
            if (failure != null) throw AssertionError("Background sync failed: ${failure.outputData.getString(SyncOutboxWorker.OUTPUT_STATE)}")
            android.os.SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for background sync; status=${SyncBackgroundStatus.read(context)}")
    }
}
