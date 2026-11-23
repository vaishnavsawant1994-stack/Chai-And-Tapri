package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Optional end-to-end CAT-052 drill. Run with an isolated backend endpoint supplied to instrumentation. */
@RunWith(AndroidJUnit4::class)
class LiveBackendSyncE2ETest {
    private fun configureTestTrust() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val certificate = context.assets.open("tls/sync-test-server.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("isolated-backend-e2e", certificate)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory)
    }

    private fun getJson(endpoint: String, token: String): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            assertEquals("Dashboard GET should succeed", 200, status)
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    @Test fun realBackendStoresAndroidEventAndMakesLostAcknowledgmentRetryIdempotent() {
        val args = InstrumentationRegistry.getArguments()
        val endpointBase = args.getString("syncE2eEndpoint")
        assumeTrue("Provide syncE2eEndpoint for the isolated backend drill.", !endpointBase.isNullOrBlank())
        val priorSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val phase = args.getString("syncE2ePhase") ?: "close_reopen"
        if (phase == "resume_after_process_death") {
            System.out.println("CAT052_TEST_PROCESS_NAME=${android.app.Application.getProcessName()}")
            resumeAfterProcessDeath(endpointBase!!, context, instrumentation)
            return
        }
        assumeTrue("Unsupported syncE2ePhase.", phase in setOf("close_reopen", "prepare_process_death"))
        val storeId = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "sync_e2e_$storeId.db"
        val keyNamespace = "sync_e2e_$storeId"
        val processDeathState = File(context.noBackupFilesDir, "cat052-process-death-state.json")
        var preserveForProcessDeath = false
        var model: CounterModel? = null
        var reopened: SqliteCounterPersistence? = null
        try {
            configureTestTrust()
            val transport = HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
            val deviceId = UUID.randomUUID().toString()
            val email = "android-${UUID.randomUUID()}@example.test"
            val session = SyncAccountClient(transport).register(
                endpointBase!!,
                SyncRegistration(email, "Synthetic-Only-Password-2026!", "Android E2E Owner", "Synthetic E2E Business", "Loopback Shop", deviceId, "API 37 Emulator")
            )
            val vault = SyncSessionVault(context, keyNamespace)
            vault.save(session)

            instrumentation.runOnMainSync { LocalSessionState.establish("Synthetic sync E2E owner") }
            val persistence = SqliteCounterPersistence(context, databaseName, keyNamespace)
            model = CounterModel(persistence, requireConfiguredProducts = true)
            val product = TapriProduct(
                productId = "e2e_chai", name = "E2E Chai", category = "Tea", unitPricePaise = 2_500,
                active = true, favorite = true, priceConfirmed = true, estimatedUnitCostPaise = 1_000
            )
            assertTrue(model!!.saveProduct(product).contains("saved"))
            assertTrue(model!!.confirmOpeningStock("e2e_chai", 10, "synthetic sync setup", "e2e-opening-${UUID.randomUUID()}").contains("confirmed"))
            val saleCommandId = "e2e-sale-${UUID.randomUUID()}"
            assertTrue(model!!.quickSale(listOf("e2e_chai" to 1), "cash", commandId = saleCommandId).startsWith("Quick sale"))
            val sendableEvents = persistence.loadPendingSyncEvents(20)
            val saleEvent = sendableEvents.singleOrNull { it.commandId == saleCommandId }
            val outboxState = persistence.writableDatabase.rawQuery("SELECT command_id,status,blocked_reason FROM sync_outbox ORDER BY created_at,event_id", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add("${cursor.getString(0)}=${cursor.getString(1)}:${if (cursor.isNull(2)) "" else cursor.getString(2)}") }.joinToString(" | ")
            }
            assertTrue("Actual SQLite outbox should contain the sale event; sendable=${sendableEvents.map { it.commandId }}; rows=$outboxState", saleEvent != null)
            assertTrue("Opening-stock event should also be in the outbox", sendableEvents.any { it.commandId.startsWith("e2e-opening-") })
            var loseFirstAcknowledgment = true
            val interruptedTransport = SyncHttpTransport { endpoint, token, body ->
                val response = transport.post(endpoint, token, body)
                val postedEventId = JSONObject(body).getJSONArray("events").getJSONObject(0).getString("eventId")
                if (postedEventId == saleEvent!!.eventId && loseFirstAcknowledgment) {
                    loseFirstAcknowledgment = false
                    throw IOException("Synthetic response-loss fault after server commit.")
                }
                response
            }

            val firstAttempt = runCatching {
                SyncRunner(PersistenceSyncJournal(persistence), interruptedTransport).push(session.endpointBase, session.identity, session.bearerToken)
            }
            assertTrue("The test fault should simulate the lost response", firstAttempt.exceptionOrNull() is IOException)
            assertEquals("Sale remains the only unacknowledged local event", listOf(saleEvent!!.eventId), persistence.loadPendingSyncEvents(20).map { it.eventId })
            assertEquals("Earlier stock event should have advanced the stored cursor", 1L, persistence.loadSyncCursor())
            if (phase == "prepare_process_death") {
                System.out.println("CAT052_TEST_PROCESS_NAME=${android.app.Application.getProcessName()}")
                processDeathState.writeText(JSONObject()
                    .put("databaseName", databaseName)
                    .put("keyNamespace", keyNamespace)
                    .put("preparedProcessId", android.os.Process.myPid())
                    .put("preparedProcessName", android.app.Application.getProcessName())
                    .toString(), Charsets.UTF_8)
                // Intentionally keep the SQLCipher connection open. The host
                // force-stops this instrumentation process before phase two.
                preserveForProcessDeath = true
                return
            }
            model!!.close()
            model = null

            reopened = SqliteCounterPersistence(context, databaseName, keyNamespace)
            val restoredModel = CounterModel(reopened)
            assertEquals(2_500, restoredModel.allOrders().single().salePaise)
            assertEquals(9, restoredModel.stockLevels()["e2e_chai"])
            assertEquals(listOf(saleEvent.eventId), reopened!!.loadPendingSyncEvents(20).map { it.eventId })
            assertEquals(1L, reopened!!.loadSyncCursor())

            val retry = SyncRunner(PersistenceSyncJournal(reopened!!), transport).push(session.endpointBase, session.identity, session.bearerToken)
            assertEquals(1, retry.acknowledged)
            assertEquals(2L, retry.serverRevision)
            assertTrue("Durable outbox should clear only after the duplicate acknowledgment", reopened!!.loadPendingSyncEvents(20).isEmpty())
            assertEquals(2L, reopened!!.loadSyncCursor())
            restoredModel.close()
            reopened = null

            val overview = getJson("${session.endpointBase}/v1/dashboard/overview?shopId=${session.identity.shopId}", session.bearerToken)
            assertEquals(2L, overview.getLong("serverRevision"))
            assertEquals(2, overview.getInt("acceptedEventCount"))
            assertEquals(1, overview.getInt("recordedOrderCount"))
            assertEquals(2_500, overview.getLong("cashCollectedPaise"))
            val readiness = getJson("${session.endpointBase}/v1/dashboard/profit-readiness?shopId=${session.identity.shopId}&days=30", session.bearerToken)
            assertEquals(1, readiness.getJSONObject("costCoverage").getInt("costedNetDeliveredSaleLines"))
            assertNull(readiness.getJSONObject("figures").opt("grossProfitPaise").takeUnless { it === JSONObject.NULL })
            assertNull(readiness.getJSONObject("figures").opt("operatingProfitPaise").takeUnless { it === JSONObject.NULL })
            assertFalse("A server retry must not duplicate the accepted order", overview.getInt("recordedOrderCount") > 1)
            SyncAccountClient(transport).logout(session)
        } finally {
            if (!preserveForProcessDeath) {
                model?.close()
                reopened?.close()
                instrumentation.runOnMainSync { LocalSessionState.clear() }
                context.deleteDatabase(databaseName)
                context.getSharedPreferences("chai_tapri_secure_key_envelope.$keyNamespace", android.content.Context.MODE_PRIVATE).edit().clear().commit()
                SecureDatabaseKeyManager.removeWrappingKeyForTests(keyNamespace)
                SyncSessionVault(context, keyNamespace).clear()
                processDeathState.delete()
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(priorSocketFactory)
        }
    }

    @Test fun androidSupplierExpenseAndCustomerFinanceOutboxesReachOwnerProjections() {
        val endpointBase = InstrumentationRegistry.getArguments().getString("syncE2eEndpoint")
        assumeTrue("Provide syncE2eEndpoint for the isolated supplier-finance backend drill.", !endpointBase.isNullOrBlank())
        val priorSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storeId = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "supplier_sync_e2e_$storeId.db"
        val keyNamespace = "supplier_sync_e2e_$storeId"
        var model: CounterModel? = null
        var persistence: SqliteCounterPersistence? = null
        var session: SyncSession? = null
        try {
            configureTestTrust()
            val transport = HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
            val deviceId = UUID.randomUUID().toString()
            session = SyncAccountClient(transport).register(
                endpointBase!!,
                SyncRegistration("supplier-${UUID.randomUUID()}@example.test", "Synthetic-Only-Password-2026!", "Synthetic Supplier Owner", "Synthetic Supplier Business", "Supplier Sync Shop", deviceId, "API 37 Supplier Sync Test")
            )
            val vault = SyncSessionVault(context, keyNamespace)
            vault.save(session!!)
            instrumentation.runOnMainSync { LocalSessionState.establish("Synthetic supplier sync owner") }
            persistence = SqliteCounterPersistence(context, databaseName, keyNamespace)
            model = CounterModel(persistence!!, requireConfiguredProducts = true)
            val shiftOpenCommand = "shift-open-${UUID.randomUUID()}"
            assertTrue(model!!.openCashDrawer(10_000, shiftOpenCommand, staffLabel = "Private E2E Staff").contains("Shift started"))
            val supplierId = "local_supplier_${UUID.randomUUID().toString().replace("-", "").take(24)}"
            val productId = "supplier_sync_tea"
            assertTrue(model!!.saveProduct(TapriProduct(productId, "Private Tea Detail", "Pantry", 1_000, true, false, true)).startsWith("Product saved"))
            val openingId = "supplier-opening-${UUID.randomUUID()}"
            assertTrue(model!!.confirmOpeningStock(productId, 3, "synthetic supplier sync setup", openingId).contains("3 units"))
            assertTrue(model!!.saveSupplier(supplierId, "Private Supplier Name", "supplier-profile-${UUID.randomUUID()}", "+919876543210").startsWith("Supplier saved"))
            val receiptCommand = "supplier-receipt-${UUID.randomUUID()}"
            assertTrue(model!!.receivePurchase(supplierId, mapOf(productId to 2), mapOf(productId to 500), receiptCommand, supplierInvoiceReference = "PRIVATE-INVOICE-DETAIL").startsWith("Received"))
            val paymentCommand = "supplier-payment-${UUID.randomUUID()}"
            assertTrue(model!!.recordSupplierPayment(supplierId, 200, "cash", paymentCommand).contains("Staff-declared cash payment"))
            val receiptId = model!!.purchaseHistory().single().receiptId
            val returnCommand = "supplier-return-${UUID.randomUUID()}"
            assertTrue(model!!.returnPurchase(supplierId, receiptId, mapOf(productId to 1), "synthetic sealed pack return", returnCommand).contains("return recorded", ignoreCase = true))
            val expenseCommand = "supplier-sync-expense-${UUID.randomUUID()}"
            assertTrue(model!!.recordExpense("transport", 7_500, "cash", "PRIVATE-EXPENSE-NOTE", expenseCommand).startsWith("Expense recorded"))
            val localCustomerId = "local_customer_${UUID.randomUUID().toString().replace("-", "").take(24)}"
            assertTrue(model!!.saveCustomerProfile(CustomerProfile(localCustomerId, "Private Customer Name", "+919812345678", true)).contains("profile saved"))
            val creditCommand = "customer-split-${UUID.randomUUID()}"
            assertTrue(model!!.quickSaleSplit(listOf(productId to 1), 500, localCustomerId, creditCommand).startsWith("Quick sale"))
            val repaymentCommand = "customer-repayment-${UUID.randomUUID()}"
            assertTrue(model!!.repayCredit(localCustomerId, 200, repaymentCommand).contains("allocated"))
            assertEquals(3_000L, model!!.expectedCashPaise(shiftOpenCommand))
            val shiftCloseCommand = "shift-close-${UUID.randomUUID()}"
            assertTrue(model!!.closeCashDrawer(3_000, shiftCloseCommand).contains("Shift ended"))

            val expectedCommands = setOf(openingId, shiftOpenCommand, receiptCommand, paymentCommand, returnCommand, expenseCommand, creditCommand, repaymentCommand, shiftCloseCommand)
            val pending = persistence!!.loadPendingSyncEvents(20)
            assertEquals("Opening stock, drawer session, receipt, settlement, return, expense, credit sale, repayment, and shift close should be sendable", expectedCommands, pending.map { it.commandId }.toSet())
            val sync = SyncRunner(PersistenceSyncJournal(persistence!!), transport).push(session!!.endpointBase, session!!.identity, session!!.bearerToken)
            assertEquals(9, sync.acknowledged)
            assertEquals(9L, sync.serverRevision)
            assertTrue(persistence!!.loadPendingSyncEvents(20).isEmpty())
            assertEquals(300L, model!!.supplierPayablePaise(supplierId))
            assertEquals(3, model!!.stockLevels()[productId])
            assertEquals(7_500L, model!!.totalExpensesPaise())
            assertEquals(300, model!!.creditBalance(localCustomerId))
            model!!.close(); model = null
            persistence!!.close(); persistence = null

            val balances = getJson("${session!!.endpointBase}/v1/dashboard/suppliers?shopId=${session!!.identity.shopId}", session!!.bearerToken)
            assertEquals(9L, balances.getLong("serverRevision"))
            assertEquals(1, balances.getInt("supplierCount"))
            assertEquals(1_000L, balances.getLong("totalReceiptsPaise"))
            assertEquals(200L, balances.getLong("totalPaymentsPaise"))
            assertEquals(500L, balances.getLong("totalReturnsPaise"))
            assertEquals(300L, balances.getLong("netPayablePaise"))
            val summary = balances.getJSONArray("suppliers").getJSONObject(0)
            assertTrue(summary.getString("supplierId").matches(Regex("^[0-9a-fA-F-]{36}$")))
            assertTrue("Owner projection must not expose supplier or product details", !balances.toString().contains("Private Supplier Name") && !balances.toString().contains("PRIVATE-INVOICE-DETAIL") && !balances.toString().contains("Private Tea Detail") && !balances.toString().contains("+919876543210"))
            assertTrue(balances.getJSONObject("evidence").getString("verification").contains("not bank-verified"))
            val activity = getJson("${session!!.endpointBase}/v1/dashboard/activity?shopId=${session!!.identity.shopId}&days=7", session!!.bearerToken)
            assertEquals(9L, activity.getLong("serverRevision"))
            assertEquals(7_500L, activity.getJSONObject("totals").getLong("staffDeclaredExpensesPaise"))
            val category = activity.getJSONArray("expenseCategories").getJSONObject(0)
            assertEquals("transport", category.getString("category"))
            assertEquals(7_500L, category.getLong("amountPaise"))
            assertEquals(1, category.getInt("count"))
            assertFalse("Owner activity projection must not expose private expense notes", activity.toString().contains("PRIVATE-EXPENSE-NOTE"))
            val cashflow = getJson("${session!!.endpointBase}/v1/dashboard/cashflow?shopId=${session!!.identity.shopId}&days=7", session!!.bearerToken)
            assertEquals(9L, cashflow.getLong("serverRevision"))
            assertEquals(200L, cashflow.getJSONObject("totals").getLong("recordedSupplierPaymentsOutPaise"))
            assertTrue("Supplier settlements remain staff-declared rather than bank-verified", cashflow.getJSONObject("evidence").getString("outflows").contains("not bank-verified"))
            val shifts = getJson("${session!!.endpointBase}/v1/dashboard/shifts?shopId=${session!!.identity.shopId}", session!!.bearerToken)
            assertEquals(9L, shifts.getLong("serverRevision"))
            assertEquals(1, shifts.getInt("shiftCount"))
            val shift = shifts.getJSONArray("shifts").getJSONObject(0)
            assertEquals("staff_shift", shift.getString("sessionType"))
            assertEquals(3_000L, shift.getLong("expectedCashPaise"))
            assertEquals(3_000L, shift.getLong("closingCashPaise"))
            assertFalse("Owner shift projection must omit operator labels", shifts.toString().contains("Private E2E Staff"))
            assertTrue(shifts.getJSONObject("evidence").getString("limitation").contains("not attendance"))
            val customers = getJson("${session!!.endpointBase}/v1/dashboard/customers?shopId=${session!!.identity.shopId}", session!!.bearerToken)
            assertEquals(9L, customers.getLong("serverRevision"))
            assertEquals(1, customers.getInt("customerCount"))
            assertEquals(500L, customers.getLong("totalExtendedPaise"))
            assertEquals(200L, customers.getLong("totalRepaymentsPaise"))
            assertEquals(300L, customers.getLong("netReceivablePaise"))
            assertEquals(200L, customers.getJSONArray("customers").getJSONObject(0).getLong("repaidPaise"))
            assertTrue("Customer projection must omit local identity and contact details", !customers.toString().contains(localCustomerId) && !customers.toString().contains("Private Customer Name") && !customers.toString().contains("+919812345678"))
            SyncAccountClient(transport).logout(session!!)
        } finally {
            model?.close()
            persistence?.close()
            instrumentation.runOnMainSync { LocalSessionState.clear() }
            context.deleteDatabase(databaseName)
            context.getSharedPreferences("chai_tapri_secure_key_envelope.$keyNamespace", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            runCatching { SecureDatabaseKeyManager.removeWrappingKeyForTests(keyNamespace) }
            runCatching { SyncSessionVault(context, keyNamespace).clear() }
            HttpsURLConnection.setDefaultSSLSocketFactory(priorSocketFactory)
        }
    }

    private fun resumeAfterProcessDeath(endpointBase: String, context: android.content.Context, instrumentation: android.app.Instrumentation) {
        val processDeathState = File(context.noBackupFilesDir, "cat052-process-death-state.json")
        assertTrue("First phase must leave a synthetic encrypted outbox before the host force-stops the app.", processDeathState.isFile)
        val state = JSONObject(processDeathState.readText(Charsets.UTF_8))
        val databaseName = state.getString("databaseName")
        val keyNamespace = state.getString("keyNamespace")
        val preparedProcessId = state.getInt("preparedProcessId")
        System.out.println("CAT052_PREVIOUS_PROCESS_NAME=${state.getString("preparedProcessName")}")
        assertTrue("The app process must have been restarted between phases.", preparedProcessId != android.os.Process.myPid())
        val priorSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        var persistence: SqliteCounterPersistence? = null
        try {
            configureTestTrust()
            val transport = HttpsSyncHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
            val session = SyncSessionVault(context, keyNamespace).load()
                ?: throw AssertionError("Encrypted test sync session should survive process death.")
            persistence = SqliteCounterPersistence(context, databaseName, keyNamespace)
            val model = CounterModel(persistence!!, requireConfiguredProducts = true)
            assertEquals(2_500, model.allOrders().single().salePaise)
            assertEquals(9, model.stockLevels()["e2e_chai"])
            assertEquals(1L, persistence!!.loadSyncCursor())
            val pending = persistence!!.loadPendingSyncEvents(20)
            assertEquals("The sale remains pending after actual app process termination.", 1, pending.size)
            assertTrue(pending.single().commandId.startsWith("e2e-sale-"))

            val retry = SyncRunner(PersistenceSyncJournal(persistence!!), transport).push(session.endpointBase, session.identity, session.bearerToken)
            assertEquals(1, retry.acknowledged)
            assertEquals(2L, retry.serverRevision)
            assertTrue(persistence!!.loadPendingSyncEvents(20).isEmpty())
            assertEquals(2L, persistence!!.loadSyncCursor())
            model.close()
            persistence!!.close()
            persistence = null

            val overview = getJson("${session.endpointBase}/v1/dashboard/overview?shopId=${session.identity.shopId}", session.bearerToken)
            assertEquals(2L, overview.getLong("serverRevision"))
            assertEquals(2, overview.getInt("acceptedEventCount"))
            assertEquals(1, overview.getInt("recordedOrderCount"))
            assertEquals(2_500, overview.getLong("cashCollectedPaise"))
            assertFalse("A process-death retry must not duplicate the accepted order.", overview.getInt("recordedOrderCount") > 1)
            SyncAccountClient(transport).logout(session)
            instrumentation.runOnMainSync { LocalSessionState.clear() }
        } finally {
            persistence?.close()
            context.deleteDatabase(databaseName)
            context.getSharedPreferences("chai_tapri_secure_key_envelope.$keyNamespace", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            runCatching { SecureDatabaseKeyManager.removeWrappingKeyForTests(keyNamespace) }
            runCatching { SyncSessionVault(context, keyNamespace).clear() }
            instrumentation.runOnMainSync { LocalSessionState.clear() }
            processDeathState.delete()
            HttpsURLConnection.setDefaultSSLSocketFactory(priorSocketFactory)
        }
    }
}
