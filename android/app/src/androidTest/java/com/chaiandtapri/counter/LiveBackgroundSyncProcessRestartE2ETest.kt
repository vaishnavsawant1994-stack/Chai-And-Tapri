package com.chaiandtapri.counter

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Two-phase synthetic check that persisted WorkManager work resumes in a fresh app process. */
@RunWith(AndroidJUnit4::class)
class LiveBackgroundSyncProcessRestartE2ETest {
    @Test fun scheduledWorkerResumesAfterAppProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("syncE2eEndpoint")
        val phase = args.getString("syncE2ePhase")
        assumeTrue("Requires the isolated synthetic HTTPS/PostgreSQL endpoint and explicit phase.",
            !endpoint.isNullOrBlank() && phase in setOf("prepare", "resume"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stateFile = File(context.noBackupFilesDir, "cat052-worker-process-restart.json")
        val previousSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        trustTestServer()
        try {
            if (phase == "prepare") prepare(endpoint!!, context, stateFile)
            else resume(endpoint!!, context, stateFile)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousSocketFactory)
        }
    }

    private fun prepare(endpoint: String, context: Context, stateFile: File) {
        assertTrue("No previous worker-restart checkpoint may be overwritten.", !stateFile.exists())
        assumeTrue("Existing cloud session is preserved; use a synthetic isolated emulator.",
            SyncSessionVault(context).load() == null)
        assumeTrue("Existing cloud identity is preserved; use a synthetic isolated emulator.",
            SyncDeviceIdentityStore(context).load() == null)
        val manager = WorkManager.getInstance(context)
        val priorWork = manager.getWorkInfosForUniqueWork(SyncBackgroundScheduler.ONE_TIME_NAME).get(10, TimeUnit.SECONDS) +
            manager.getWorkInfosForUniqueWork(SyncBackgroundScheduler.PERIODIC_NAME).get(10, TimeUnit.SECONDS)
        assumeTrue("Existing synchronization work is preserved; use a synthetic isolated emulator.",
            priorWork.none { !it.state.isFinished })
        assumeTrue("Existing business records are preserved; use a synthetic isolated emulator.", defaultStoreIsEmpty(context))

        var session: SyncSession? = null
        var prepared = false
        try {
            val deviceId = UUID.randomUUID().toString()
            session = SyncAccountClient(HttpsSyncHttpTransport()).register(
                endpoint,
                SyncRegistration(
                    "restart-${UUID.randomUUID()}@example.test", "Synthetic-Only-Password-2026!",
                    "Synthetic Restart Owner", "Synthetic Restart Business", "Synthetic Restart Shop",
                    deviceId, "API 37 isolated emulator"
                )
            ).copy(localActorId = "local-owner")
            SyncSessionVault(context).save(session!!)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                LocalSessionState.establish("Synthetic CAT-052 restart owner")
            }
            SqliteCounterPersistence(context).use { persistence ->
                val model = CounterModel(persistence, requireConfiguredProducts = true)
                assertTrue(model.saveProduct(TapriProduct(
                    productId = "restart_chai", name = "Restart Test Chai", category = "Tea", unitPricePaise = 3_000,
                    active = true, favorite = true, priceConfirmed = true, estimatedUnitCostPaise = 1_100
                )).contains("saved"))
                assertTrue(model.confirmOpeningStock("restart_chai", 8, "synthetic worker restart", "restart-opening-${UUID.randomUUID()}").contains("confirmed"))
                assertTrue(model.quickSale(listOf("restart_chai" to 1), "cash", commandId = "restart-sale-${UUID.randomUUID()}").startsWith("Quick sale"))
            }
            context.getSharedPreferences("chai_tapri_sync_work_status_v1", Context.MODE_PRIVATE).edit().clear().commit()

            val delayedRequest = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(60, TimeUnit.SECONDS)
                .addTag(SyncBackgroundScheduler.ONE_TIME_NAME)
                .build()
            manager.enqueueUniqueWork(SyncBackgroundScheduler.ONE_TIME_NAME, ExistingWorkPolicy.KEEP, delayedRequest)
                .result.get(10, TimeUnit.SECONDS)
            val scheduled = manager.getWorkInfoById(delayedRequest.id).get(10, TimeUnit.SECONDS)
                ?: throw AssertionError("The delayed WorkManager request was not persisted.")
            assertEquals(WorkInfo.State.ENQUEUED, scheduled.state)
            stateFile.writeText(JSONObject()
                .put("preparedProcessId", Process.myPid())
                .put("preparedProcessName", Application.getProcessName())
                .put("workId", delayedRequest.id.toString())
                .put("shopId", session!!.identity.shopId)
                .put("endpoint", endpoint)
                .toString(), Charsets.UTF_8)
            prepared = true
            println("CAT052_WORKER_RESTART_PREPARED pid=${Process.myPid()} workId=${delayedRequest.id}")
        } finally {
            if (!prepared) {
                runCatching { SyncBackgroundScheduler.pause(context).forEach { it.result.get(10, TimeUnit.SECONDS) } }
                session?.let { runCatching { SyncAccountClient(HttpsSyncHttpTransport()).logout(it) } }
                runCatching { SyncSessionVault(context).clear() }
                runCatching { clearSyntheticStore(context) }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { LocalSessionState.clear() }
            }
        }
    }

    private fun resume(endpoint: String, context: Context, stateFile: File) {
        assertTrue("The prepare phase must leave a pending durable WorkManager request and synthetic outbox.", stateFile.isFile)
        val state = JSONObject(stateFile.readText(Charsets.UTF_8))
        assertEquals("com.chaiandtapri.counter", state.getString("preparedProcessName"))
        assertNotEquals("The app process must have restarted between phases.", state.getInt("preparedProcessId"), Process.myPid())
        assertEquals(endpoint.trimEnd('/'), state.getString("endpoint").trimEnd('/'))
        println("CAT052_WORKER_RESTART_RESUMED previousPid=${state.getInt("preparedProcessId")} currentPid=${Process.myPid()} workId=${state.getString("workId")}")
        var session: SyncSession? = null
        var verified = false
        try {
            session = SyncSessionVault(context).load()
                ?: throw AssertionError("The Keystore-protected owner session must survive process death.")
            assertEquals(state.getString("shopId"), session!!.identity.shopId)
            assertEquals(endpoint.trimEnd('/'), session!!.endpointBase.trimEnd('/'))
            val id = UUID.fromString(state.getString("workId"))
            val manager = WorkManager.getInstance(context)
            val before = manager.getWorkInfoById(id).get(10, TimeUnit.SECONDS)
            assertTrue("The exact saved work request must remain queued after restart.",
                before != null && (before.state == WorkInfo.State.ENQUEUED || before.state == WorkInfo.State.RUNNING))

            val completed = waitForWork(context, id, 150_000)
            assertEquals(WorkInfo.State.SUCCEEDED, completed.state)
            assertEquals("synced", completed.outputData.getString(SyncOutboxWorker.OUTPUT_STATE))
            assertEquals(2, completed.outputData.getInt(SyncOutboxWorker.OUTPUT_ACKNOWLEDGED, -1))
            assertEquals(2L, completed.outputData.getLong(SyncOutboxWorker.OUTPUT_REVISION, -1L))
            SqliteCounterPersistence(context).use { persistence ->
                val model = CounterModel(persistence, requireConfiguredProducts = true)
                assertEquals(1, model.allOrders().size)
                assertEquals(3_000, model.allOrders().single().salePaise)
                assertEquals(7, model.stockLevels()["restart_chai"])
                assertTrue(persistence.loadPendingSyncEvents(20).isEmpty())
                assertEquals(2L, persistence.loadSyncCursor())
            }
            val overview = ownerOverview(session!!)
            assertEquals(2L, overview.getLong("serverRevision"))
            assertEquals(2, overview.getInt("acceptedEventCount"))
            assertEquals(1, overview.getInt("recordedOrderCount"))
            assertEquals(3_000, overview.getLong("cashCollectedPaise"))
            verified = true
        } finally {
            if (verified) {
                runCatching { SyncBackgroundScheduler.pause(context).forEach { it.result.get(10, TimeUnit.SECONDS) } }
                session?.let { runCatching { SyncAccountClient(HttpsSyncHttpTransport()).logout(it) } }
                runCatching { SyncSessionVault(context).clear() }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { LocalSessionState.clear() }
                clearSyntheticStore(context)
                stateFile.delete()
            }
        }
    }

    private fun trustTestServer() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val cert = context.assets.open("tls/sync-test-server.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-worker-restart", cert) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply {
            init(null, managers.trustManagers, null)
        }.socketFactory)
    }

    private fun defaultStoreIsEmpty(context: Context): Boolean = SqliteCounterPersistence(context).use { persistence ->
        val snapshot = persistence.load()
        val hasBusinessRows = snapshot?.let {
            it.orders.isNotEmpty() || it.commandIds.isNotEmpty() || it.auditEvents.isNotEmpty() ||
                it.stockMovements.isNotEmpty() || it.customerCredits.isNotEmpty() || it.creditRepayments.isNotEmpty() ||
                it.paymentEntries.isNotEmpty() || it.stockConfirmedProductIds.isNotEmpty() || it.suppliers.isNotEmpty() ||
                it.purchaseReceipts.isNotEmpty() || it.supplierPayments.isNotEmpty() || it.purchaseReturns.isNotEmpty() ||
                it.expenses.isNotEmpty() || it.cashDrawerSessions.isNotEmpty() || it.cashDrawerMovements.isNotEmpty() ||
                it.cashClosingReviews.isNotEmpty() || it.purchaseOrderDrafts.isNotEmpty()
        } ?: false
        val accountCount = persistence.writableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM staff_credentials) + (SELECT COUNT(*) FROM local_owner_credentials)", null
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }
        val sampleProducts = listOf(
            TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1_000),
            TapriProduct("chai", "Regular Chai", "Chai", 1_500),
            TapriProduct("samosa", "Samosa", "Snacks", 2_000),
            TapriProduct("coffee", "Coffee", "Coffee", 2_500)
        ).toSet()
        val onlySampleStock = snapshot?.stock?.all { (productId, quantity) ->
            productId in setOf("cutting_chai", "chai", "samosa", "coffee") && quantity == 0
        } ?: false
        !hasBusinessRows && !accountCount && persistence.loadProducts().toSet() == sampleProducts && onlySampleStock &&
            persistence.loadAuditRecords().isEmpty() && persistence.loadCustomerProfiles().isEmpty() &&
            persistence.loadShopProfile().shopName.isBlank() && !persistence.localOwnerExists() &&
            persistence.loadPendingSyncEvents(20).isEmpty()
    }

    private fun clearSyntheticStore(context: Context) {
        runCatching { SecureDatabaseKeyManager.removeWrappingKeyForTests("default") }
        context.getSharedPreferences("chai_tapri_secure_key_envelope.default", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("chai_tapri_counter.db")
        context.getSharedPreferences("chai_tapri_sync_work_status_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitForWork(context: Context, id: UUID, timeoutMs: Long): WorkInfo {
        val manager = WorkManager.getInstance(context)
        val expires = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < expires) {
            val info = manager.getWorkInfoById(id).get(10, TimeUnit.SECONDS)
                ?: throw AssertionError("The saved WorkManager request disappeared.")
            if (info.state.isFinished) return info
            SystemClock.sleep(250)
        }
        throw AssertionError("Timed out waiting for saved worker; status=${SyncBackgroundStatus.read(context)}")
    }

    private fun ownerOverview(session: SyncSession): JSONObject {
        val url = "${session.endpointBase.trimEnd('/')}/v1/dashboard/overview?shopId=${session.identity.shopId}"
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 7_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer ${session.bearerToken}")
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { connection.disconnect() }
    }
}
