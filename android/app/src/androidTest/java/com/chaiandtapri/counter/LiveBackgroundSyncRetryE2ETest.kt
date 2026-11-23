package com.chaiandtapri.counter

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Optional live qualification of WorkManager retry after a server commit loses its response. */
@RunWith(AndroidJUnit4::class)
class LiveBackgroundSyncRetryE2ETest {
    @Test fun workerRetriesCommittedEventsAfterAckLossWithoutDuplicates() {
        val endpoint = InstrumentationRegistry.getArguments().getString("syncE2eEndpoint")
        assumeTrue("Requires the isolated synthetic HTTPS/PostgreSQL endpoint.", !endpoint.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        trustTestServer()
        var session: SyncSession? = null
        var testStorePrepared = false
        try {
            assumeTrue("An existing owner cloud session is preserved; use an isolated emulator for this test.",
                SyncSessionVault(context).load() == null)
            assumeTrue("An existing cloud-shop identity is preserved; use an isolated emulator for this test.",
                SyncDeviceIdentityStore(context).load() == null)
            val manager = WorkManager.getInstance(context)
            val existingWork = manager.getWorkInfosForUniqueWork(SyncBackgroundScheduler.ONE_TIME_NAME).get(10, TimeUnit.SECONDS) +
                manager.getWorkInfosForUniqueWork(SyncBackgroundScheduler.PERIODIC_NAME).get(10, TimeUnit.SECONDS)
            assumeTrue("Existing synchronization work is preserved; use an isolated emulator for this test.",
                existingWork.none { !it.state.isFinished })
            assumeTrue("Existing shop records are preserved; use an isolated emulator for this test.", defaultStoreIsEmpty(context))
            testStorePrepared = true

            val transport = HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 7_000)
            val deviceId = UUID.randomUUID().toString()
            session = SyncAccountClient(transport).register(
                endpoint!!,
                SyncRegistration(
                    "worker-${UUID.randomUUID()}@example.test", "Synthetic-Only-Password-2026!",
                    "Synthetic Worker Owner", "Synthetic Worker Business", "Synthetic Worker Shop",
                    deviceId, "API 37 isolated emulator"
                )
            ).copy(localActorId = "local-owner")
            SyncSessionVault(context).save(session!!)
            context.getSharedPreferences("chai_tapri_sync_work_status_v1", Context.MODE_PRIVATE).edit().clear().commit()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                LocalSessionState.establish("Synthetic CAT-052 worker owner")
            }
            SqliteCounterPersistence(context).use { persistence ->
                val model = CounterModel(persistence, requireConfiguredProducts = true)
                assertTrue(model.saveProduct(TapriProduct(
                    productId = "worker_chai", name = "Worker Test Chai", category = "Tea", unitPricePaise = 2_500,
                    active = true, favorite = true, priceConfirmed = true, estimatedUnitCostPaise = 1_000
                )).contains("saved"))
                assertTrue(model.confirmOpeningStock("worker_chai", 10, "synthetic worker test", "worker-opening-${UUID.randomUUID()}").contains("confirmed"))
                assertTrue(model.quickSale(listOf("worker_chai" to 1), "cash", commandId = "worker-sale-${UUID.randomUUID()}").startsWith("Quick sale"))
            }

            SyncBackgroundScheduler.enqueueOneTime(context).result.get(10, TimeUnit.SECONDS)
            val completed = waitForWork(context, 150_000) { it.state.isFinished }
            assertEquals("The worker must finish after retrying the lost acknowledgment.", WorkInfo.State.SUCCEEDED, completed.state)
            assertTrue("The committed response-loss must cause a real WorkManager retry.", completed.runAttemptCount >= 1)
            assertEquals("synced", completed.outputData.getString(SyncOutboxWorker.OUTPUT_STATE))
            assertEquals(2, completed.outputData.getInt(SyncOutboxWorker.OUTPUT_ACKNOWLEDGED, -1))
            assertEquals(2L, completed.outputData.getLong(SyncOutboxWorker.OUTPUT_REVISION, -1L))
            assertTrue(SyncBackgroundStatus.read(context)?.contains("acknowledged 2 event(s)") == true)

            SqliteCounterPersistence(context).use { persistence ->
                val model = CounterModel(persistence, requireConfiguredProducts = true)
                assertEquals(1, model.allOrders().size)
                assertEquals(2_500, model.allOrders().single().salePaise)
                assertEquals(9, model.stockLevels()["worker_chai"])
                assertTrue(persistence.loadPendingSyncEvents(20).isEmpty())
                assertEquals(2L, persistence.loadSyncCursor())
            }
            val overview = ownerOverview(session!!)
            assertEquals(2L, overview.getLong("serverRevision"))
            assertEquals(2, overview.getInt("acceptedEventCount"))
            assertEquals(1, overview.getInt("recordedOrderCount"))
            assertEquals(2_500, overview.getLong("cashCollectedPaise"))
        } finally {
            runCatching { SyncBackgroundScheduler.pause(context).forEach { it.result.get(10, TimeUnit.SECONDS) } }
            session?.let { runCatching { SyncAccountClient(HttpsSyncHttpTransport()).logout(it) } }
            runCatching { SyncSessionVault(context).clear() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { LocalSessionState.clear() }
            if (testStorePrepared) clearSyntheticStore(context)
            HttpsURLConnection.setDefaultSSLSocketFactory(previousSocketFactory)
        }
    }

    private fun trustTestServer() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val cert = context.assets.open("tls/sync-test-server.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-worker-e2e", cert) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply {
            init(null, managers.trustManagers, null)
        }.socketFactory)
    }

    private fun defaultStoreIsEmpty(context: Context): Boolean = SqliteCounterPersistence(context).use { persistence ->
        val snapshot = persistence.load()
        val snapshotHasRows = snapshot?.let {
            it.orders.isNotEmpty() || it.commandIds.isNotEmpty() || it.auditEvents.isNotEmpty() ||
                it.stockMovements.isNotEmpty() || it.customerCredits.isNotEmpty() || it.creditRepayments.isNotEmpty() ||
                it.paymentEntries.isNotEmpty() || it.stockConfirmedProductIds.isNotEmpty() || it.suppliers.isNotEmpty() ||
                it.purchaseReceipts.isNotEmpty() || it.supplierPayments.isNotEmpty() || it.purchaseReturns.isNotEmpty() ||
                it.expenses.isNotEmpty() || it.cashDrawerSessions.isNotEmpty() || it.cashDrawerMovements.isNotEmpty() ||
                it.cashClosingReviews.isNotEmpty() || it.purchaseOrderDrafts.isNotEmpty()
        } ?: false
        val hasProtectedAccounts = persistence.writableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM staff_credentials) + (SELECT COUNT(*) FROM local_owner_credentials)", null
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }
        val seededProducts = listOf(
            TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1_000),
            TapriProduct("chai", "Regular Chai", "Chai", 1_500),
            TapriProduct("samosa", "Samosa", "Snacks", 2_000),
            TapriProduct("coffee", "Coffee", "Coffee", 2_500)
        ).toSet()
        val productsAreOnlyUnconfiguredSeed = persistence.loadProducts().toSet() == seededProducts
        val stockIsOnlyZeroSeed = snapshot?.stock?.all { (productId, quantity) ->
            productId in setOf("cutting_chai", "chai", "samosa", "coffee") && quantity == 0
        } ?: false
        !snapshotHasRows && !hasProtectedAccounts && persistence.loadAuditRecords().isEmpty() &&
            productsAreOnlyUnconfiguredSeed && stockIsOnlyZeroSeed && persistence.loadCustomerProfiles().isEmpty() &&
            persistence.loadShopProfile().shopName.isBlank() && !persistence.localOwnerExists() &&
            persistence.loadPendingSyncEvents(20).isEmpty()
    }

    private fun clearSyntheticStore(context: Context) {
        runCatching { SecureDatabaseKeyManager.removeWrappingKeyForTests("default") }
        context.getSharedPreferences("chai_tapri_secure_key_envelope.default", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("chai_tapri_counter.db")
        context.getSharedPreferences("chai_tapri_sync_work_status_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitForWork(context: Context, timeoutMs: Long, predicate: (WorkInfo) -> Boolean): WorkInfo {
        val manager = WorkManager.getInstance(context)
        val expires = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < expires) {
            val infos = manager.getWorkInfosForUniqueWork(SyncBackgroundScheduler.ONE_TIME_NAME).get(10, TimeUnit.SECONDS)
            infos.firstOrNull(predicate)?.let { return it }
            val failure = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
            if (failure != null) throw AssertionError("Background sync failed: ${failure.outputData.getString(SyncOutboxWorker.OUTPUT_STATE)}")
            android.os.SystemClock.sleep(250)
        }
        throw AssertionError("Timed out waiting for background sync; status=${SyncBackgroundStatus.read(context)}")
    }

    private fun ownerOverview(session: SyncSession): JSONObject {
        val connection = URL("${session.endpointBase.trimEnd('/')}/v1/dashboard/overview?shopId=${session.identity.shopId}").openConnection() as HttpsURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 7_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer ${session.bearerToken}")
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { connection.disconnect() }
    }
}
