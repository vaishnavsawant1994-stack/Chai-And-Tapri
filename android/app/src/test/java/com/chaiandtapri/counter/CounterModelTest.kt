package com.chaiandtapri.counter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CounterModelTest {
    @Test fun localMutationPolicyBindsActorAndRestrictsCashierFinancialCorrections() {
        assertTrue(runCatching {
            LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "sale.refund #12 amount=500p")
        }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching {
            LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "owner", "order.cancelled #12 reason=test")
        }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching {
            LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "expense.recorded amount=500p")
        }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching {
            LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "stock.physical_count products=tea")
        }.exceptionOrNull() is SecurityException)
        LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "staff_shift.started staff_actor_id=cashier-01")
        LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "staff_shift.ended staff_actor_id=cashier-01")
        assertTrue(runCatching {
            LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "staff_shift.ended staff_actor_id=another-staff")
        }.exceptionOrNull() is SecurityException)
        assertTrue(runCatching {
            LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "cash_drawer.cash_in amount=500p")
        }.exceptionOrNull() is SecurityException)
        LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "quick_sale #12 total=1000p")
        LocalBusinessMutationPolicy.requireAllowed(true, "cashier-01", StaffRole.CASHIER, "cashier-01", "stock.opening_count product_id=tea counted=4")
        LocalBusinessMutationPolicy.requireAllowed(true, "manager-01", StaffRole.MANAGER, "manager-01", "sale.refund #12 amount=500p")
        LocalBusinessMutationPolicy.requireAllowed(false, null, null, null, "sale.refund #12 amount=500p")
    }

    @Test fun estimatedUnitCostRejectsNegativeAndOutOfRangeValues() {
        val model = CounterModel()
        assertTrue(model.saveProduct(TapriProduct("bad_cost", "Bad Cost", "Test", 1000, estimatedUnitCostPaise = -1)).contains("Estimated unit cost"))
        assertTrue(model.saveProduct(TapriProduct("large_cost", "Large Cost", "Test", 1000, estimatedUnitCostPaise = 10_000_001)).contains("Estimated unit cost"))
        model.close()
    }

    @Test fun customerTimelineOrdersEventsAndDoesNotInventMissingOrFutureDates() {
        val events = listOf(
            PaymentEntry("older", null, "c", 100, "cash", "staff_declared", "customer_repayment", createdAtEpochMs = 1_000L),
            PaymentEntry("newer", null, "c", 200, "cash", "staff_declared", "customer_repayment", createdAtEpochMs = 2_000L),
            PaymentEntry("legacy", null, "c", 300, "cash", "staff_declared", "customer_repayment")
        )
        assertEquals(listOf("newer", "older", "legacy"), CustomerTimeline.newestFirst(events).map { it.entryId })
        assertEquals("01 Jan 1970, 00:00 (device local time)", CustomerTimeline.recordedTime(
            1_000L, 2_000L, Locale.US, TimeZone.getTimeZone("UTC")
        ))
        assertEquals("Date unavailable", CustomerTimeline.recordedTime(0L, 2_000L))
        assertEquals("Date unavailable (device clock mismatch)", CustomerTimeline.recordedTime(3_000L, 2_000L))
    }

    @Test fun creditAgingUsesOldestOpenOrderAndNeverInventsOverdueStatus() {
        val day = 24L * 60L * 60L * 1000L
        val now = 10L * day
        val credits = listOf(
            CustomerCredit("closed", "ramesh", 1, 500, 500),
            CustomerCredit("reversed", "ramesh", 2, 400, 0, 400),
            CustomerCredit("older-open", "ramesh", 3, 700, 200),
            CustomerCredit("newer-open", "ramesh", 4, 900, 0),
            CustomerCredit("other-customer", "sita", 5, 300, 0)
        )
        val orders = listOf(
            CounterOrder(1, mutableListOf(), createdAtEpochMs = 2L * day),
            CounterOrder(2, mutableListOf(), createdAtEpochMs = 1L * day),
            CounterOrder(3, mutableListOf(), createdAtEpochMs = 4L * day),
            CounterOrder(4, mutableListOf(), createdAtEpochMs = 8L * day),
            CounterOrder(5, mutableListOf(), createdAtEpochMs = 0L)
        )

        val oldest = CreditAging.oldestOutstandingOrderCreatedAt(credits, orders, "ramesh")
        assertEquals(6L, CreditAging.elapsedDays(oldest!!, now))
        assertTrue(CreditAging.description(oldest, now).contains("Oldest open credit: 6 days since order"))
        assertTrue(CreditAging.description(null, now).contains("Age unavailable"))
        assertNull(CreditAging.elapsedDays(11L * day, now))
        assertFalse(CreditAging.description(oldest, now).contains("overdue"))
    }

    @Test fun nestedMaintenanceRestoresOuterLabelUntilTheWritePauseEnds() {
        assertNull(BusinessWriteBarrier.currentMaintenance())
        val result = BusinessWriteBarrier.duringMaintenance("outer snapshot") {
            assertEquals("outer snapshot", BusinessWriteBarrier.currentMaintenance())
            assertNull(BusinessWriteBarrier.tryBeginCommit())
            val nested = BusinessWriteBarrier.duringMaintenance("database replacement") {
                assertEquals("database replacement", BusinessWriteBarrier.currentMaintenance())
                assertNull(BusinessWriteBarrier.tryBeginCommit())
                "replaced"
            }
            assertEquals("replaced", nested)
            assertEquals("outer snapshot", BusinessWriteBarrier.currentMaintenance())
            assertNull(BusinessWriteBarrier.tryBeginCommit())
            "snapshotted"
        }
        assertEquals("snapshotted", result)
        assertNull(BusinessWriteBarrier.currentMaintenance())
        assertNotNull(BusinessWriteBarrier.tryBeginCommit()?.also { it.close() })
    }

    @Test fun maintenanceExceptionAndFaultyUiListenerDoNotLeaveWritesPaused() {
        val listener: (String?) -> Unit = { throw IllegalStateException("synthetic UI observer failure") }
        BusinessWriteBarrier.addListener(listener)
        try {
            try {
                BusinessWriteBarrier.duringMaintenance("fault-injected export") {
                    assertEquals("fault-injected export", BusinessWriteBarrier.currentMaintenance())
                    throw IllegalArgumentException("synthetic export failure")
                }
                throw AssertionError("Expected the export failure")
            } catch (expected: IllegalArgumentException) {
                assertEquals("synthetic export failure", expected.message)
            }
            assertNull(BusinessWriteBarrier.currentMaintenance())
            val permit = BusinessWriteBarrier.tryBeginCommit()
            assertNotNull(permit)
            permit!!.close()
        } finally {
            BusinessWriteBarrier.removeListener(listener)
        }
    }

    @Test fun barcodeLookupIsExactCaseInsensitiveAndRejectsAmbiguousCodes() {
        val tea = TapriProduct("scan_tea", "Scan Tea", "Test", 1000, scanCode = "Tea-123")
        val other = TapriProduct("scan_other", "Scan Other", "Test", 2000, scanCode = "Other-456")
        assertEquals(tea, ProductScanResolver.resolve(listOf(tea, other), "  tEa-123  "))
        assertNull(ProductScanResolver.resolve(listOf(tea, other), "Tea-12"))
        assertNull(ProductScanResolver.resolve(listOf(tea.copy(active = false)), "Tea-123"))
        assertNull(ProductScanResolver.resolve(listOf(tea, tea.copy(productId = "duplicate")), "Tea-123"))
        assertNull(ProductScanResolver.resolve(listOf(tea), "  "))
    }

    @Test fun catalogueRejectsDuplicateAndUnprintableScannerCodes() {
        val model = CounterModel(MemoryPersistence())
        assertTrue(model.saveProduct(TapriProduct("scan_first", "First Product", "Test", 1000, scanCode = "  BOX-123  ")).startsWith("Product saved"))
        assertTrue(model.products().single().scanCode == "BOX-123")
        assertTrue(model.saveProduct(TapriProduct("scan_second", "Second Product", "Test", 1000, scanCode = "box-123")).contains("already assigned"))
        assertTrue(model.saveProduct(TapriProduct("scan_control", "Control Product", "Test", 1000, scanCode = "ABC\n123")).contains("printable characters"))
        model.close()
    }

    @Test fun maintenanceWaitsForInFlightCommitAndRejectsNewCommitsUntilRelease() {
        val executor = Executors.newSingleThreadExecutor()
        val maintenanceStarted = CountDownLatch(1)
        val maintenanceEntered = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        var commitPermit = BusinessWriteBarrier.tryBeginCommit()
        assertNotNull("Expected an initial commit permit", commitPermit)
        try {
            val maintenance = executor.submit {
                maintenanceStarted.countDown()
                BusinessWriteBarrier.duringMaintenance("snapshot") {
                    maintenanceEntered.countDown()
                    check(releaseMaintenance.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue("Maintenance task did not start", maintenanceStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Snapshot must wait while a commit is in flight", maintenanceEntered.await(100, TimeUnit.MILLISECONDS))

            commitPermit!!.close()
            commitPermit = null
            assertTrue("Snapshot did not acquire the write barrier", maintenanceEntered.await(5, TimeUnit.SECONDS))
            assertEquals("snapshot", BusinessWriteBarrier.currentMaintenance())
            assertNull("New commits must be rejected during snapshot/restore", BusinessWriteBarrier.tryBeginCommit())

            releaseMaintenance.countDown()
            maintenance.get(5, TimeUnit.SECONDS)
            assertNull(BusinessWriteBarrier.currentMaintenance())
            val after = BusinessWriteBarrier.tryBeginCommit()
            assertNotNull("Commit access must resume after maintenance", after)
            after!!.close()
        } finally {
            releaseMaintenance.countDown()
            commitPermit?.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun maintenanceClosesAdmissionBeforeWaitingForAnInFlightBusinessOperation() {
        val executor = Executors.newSingleThreadExecutor()
        val maintenanceStarted = CountDownLatch(1)
        val maintenanceEntered = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        var inFlight = BusinessWriteBarrier.tryBeginBusinessOperation()
        assertNotNull("Expected a shared permit for the in-flight sync operation", inFlight)
        try {
            val maintenance = executor.submit {
                maintenanceStarted.countDown()
                BusinessWriteBarrier.duringMaintenance("encrypted restore") {
                    maintenanceEntered.countDown()
                    check(releaseMaintenance.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue("Maintenance task did not start", maintenanceStarted.await(5, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var admissionClosed = false
            while (!admissionClosed && System.nanoTime() < deadline) {
                val probe = BusinessWriteBarrier.tryBeginBusinessOperation()
                if (probe == null) admissionClosed = true else probe.close()
                if (!admissionClosed) Thread.sleep(5)
            }
            assertTrue("A queued restore must prevent new background sync operations", admissionClosed)
            assertFalse("Restore must wait for the active network/journal operation", maintenanceEntered.await(100, TimeUnit.MILLISECONDS))

            inFlight!!.close()
            inFlight = null
            assertTrue("Restore did not acquire the exclusive barrier after sync released", maintenanceEntered.await(5, TimeUnit.SECONDS))
            assertEquals("encrypted restore", BusinessWriteBarrier.currentMaintenance())
            assertNull(BusinessWriteBarrier.tryBeginBusinessOperation())
            releaseMaintenance.countDown()
            maintenance.get(5, TimeUnit.SECONDS)
            val after = BusinessWriteBarrier.tryBeginBusinessOperation()
            assertNotNull("New business operations must resume after restore", after)
            after!!.close()
        } finally {
            releaseMaintenance.countDown()
            inFlight?.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun businessOperationLeaseCanCloseOnAnotherDispatcherThread() {
        val maintenanceExecutor = Executors.newSingleThreadExecutor()
        val releasingExecutor = Executors.newSingleThreadExecutor()
        val maintenanceEntered = CountDownLatch(1)
        var inFlight = BusinessWriteBarrier.tryBeginBusinessOperation()
        assertNotNull("Expected a shared permit before recovery requests maintenance", inFlight)
        try {
            val maintenance = maintenanceExecutor.submit {
                BusinessWriteBarrier.duringMaintenance("cross-dispatcher recovery") {
                    maintenanceEntered.countDown()
                    "snapshot complete"
                }
            }
            assertFalse("Recovery must wait for the active operation", maintenanceEntered.await(100, TimeUnit.MILLISECONDS))

            val admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var admissionClosed = false
            while (!admissionClosed && System.nanoTime() < admissionDeadline) {
                val probe = BusinessWriteBarrier.tryBeginBusinessOperation()
                if (probe == null) admissionClosed = true else probe.close()
                if (!admissionClosed) Thread.sleep(5)
            }
            assertTrue("New operations should be rejected after recovery is requested", admissionClosed)
            assertNull("A standalone commit should also be rejected during maintenance", BusinessWriteBarrier.tryBeginCommit())
            val admittedCommit = runBlocking {
                withContext(Dispatchers.IO + BusinessWriteBarrier.operationContext(inFlight!!)) {
                    BusinessWriteBarrier.tryBeginCommit()
                }
            }
            assertNotNull("An already-admitted sync operation must persist its acknowledgment while recovery waits", admittedCommit)
            admittedCommit!!.close()

            // CoroutineWorker may acquire on its caller dispatcher and finish on Dispatchers.IO.
            releasingExecutor.submit { inFlight!!.close() }.get(5, TimeUnit.SECONDS)
            inFlight = null
            assertTrue("Recovery must continue after a lease is released from another thread", maintenanceEntered.await(5, TimeUnit.SECONDS))
            maintenance.get(5, TimeUnit.SECONDS)
            val resumed = BusinessWriteBarrier.tryBeginBusinessOperation()
            assertNotNull("Business operations should resume once recovery exits", resumed)
            resumed!!.close()
        } finally {
            inFlight?.close()
            maintenanceExecutor.shutdownNow()
            releasingExecutor.shutdownNow()
            maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS)
            releasingExecutor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private class MemoryPersistence : CounterPersistence {
        var state: CounterSnapshot? = null
        var failNext = false
        var lastSyncPayload: SyncOutboxPayload? = null
        private val products = linkedMapOf<String, TapriProduct>()
        private val profiles = linkedMapOf<String, CustomerProfile>()
        override fun load() = state
        override fun loadAuditRecords(): List<AuditRecord> = state?.auditEvents.orEmpty().map { event ->
            val commandId = event.substringBefore(' ')
            AuditRecord(commandId, commandId, event, 1L)
        }
        override fun loadProducts() = products.values.toList()
        override fun saveProduct(product: TapriProduct, commandId: String): Boolean { products[product.productId] = product; return true }
        override fun loadCustomerProfiles() = profiles.values.toList()
        override fun saveCustomerProfile(profile: CustomerProfile, commandId: String): Boolean { profiles[profile.customerId] = profile; return true }
        override fun commit(snapshot: CounterSnapshot, commandId: String, result: String, event: String, actorId: String?, movements: List<StockMovement>, repayments: List<CreditRepayment>, paymentEntries: List<PaymentEntry>, syncPayload: SyncOutboxPayload): Boolean {
            if (failNext) { failNext = false; throw IllegalStateException("simulated disk failure") }
            if (state?.commandIds?.contains(commandId) == true) return false
            state = snapshot
            lastSyncPayload = syncPayload
            return true
        }
    }

    @Test fun busyCounterEstimateIsAuditedAndNeverChangesRecordedBusinessTotals() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence)
        val summaryBefore = model.localBusinessSummary()
        val ordersBefore = model.allOrders()
        val stockBefore = model.stockMovements()
        val paymentsBefore = model.paymentHistory()

        assertTrue(model.recordUnitemizedSalesEstimate(875, "Rush period count", "estimate-001").contains("estimate only"))
        assertEquals(summaryBefore, model.localBusinessSummary())
        assertEquals(ordersBefore, model.allOrders())
        assertEquals(stockBefore, model.stockMovements())
        assertEquals(paymentsBefore, model.paymentHistory())
        assertEquals("Duplicate command ignored.", model.recordUnitemizedSalesEstimate(875, "Rush period count", "estimate-001"))
        assertTrue(model.recordUnitemizedSalesEstimate(0, "Rush period count", "estimate-invalid").contains("No estimate saved"))

        val reopened = CounterModel(persistence)
        assertEquals(listOf(UnitemizedSalesEstimate("estimate-001", 875, "Rush period count", 1L)), reopened.unitemizedSalesEstimates())
        assertEquals(summaryBefore, reopened.localBusinessSummary())
        assertTrue(reopened.auditEvents().single().contains("classification=estimated amount=875p"))
    }

    @Test fun lowStockAlertsRequireAConfiguredThresholdAndConfirmedPhysicalCount() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        val unconfigured = TapriProduct("tea_pack", "Tea Pack", "Pantry", 500, priceConfirmed = true)
        assertTrue(model.saveProduct(unconfigured).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("tea_pack", 6, "physical count", "low-stock-count").contains("6 units"))
        assertTrue(model.lowStockProducts().isEmpty())

        val configured = unconfigured.copy(reorderThreshold = 6)
        assertTrue(model.saveProduct(configured).startsWith("Product saved"))
        assertEquals(listOf(6), model.lowStockProducts().map { it.second })
        assertTrue(model.saveProduct(configured.copy(reorderThreshold = 0)).contains("threshold must be"))
        assertTrue(model.confirmOpeningStock("tea_pack", 7, "recount above threshold", "low-stock-recount").contains("7 units"))
        assertTrue(model.lowStockProducts().isEmpty())
        assertTrue(model.confirmOpeningStock("tea_pack", 5, "recount at threshold", "low-stock-recount-2").contains("5 units"))
        assertEquals("tea_pack", model.lowStockProducts().single().first.productId)
    }

    @Test fun purchaseOrderDraftDoesNotChangeStockOrPayablesAndPartialReceiptsReconcile() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("tea_pack", "Tea Pack", "Pantry", 1500, priceConfirmed = true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("tea_pack", 10, "opening count", "po-opening-stock").contains("10 units"))
        assertTrue(model.saveSupplier("tea_dealer", "Tea Dealer", "po-supplier").startsWith("Supplier saved"))
        val startingMoves = model.stockMovements().size
        assertTrue(model.createPurchaseOrderDraft("tea_dealer", mapOf("tea_pack" to 5), mapOf("tea_pack" to 1250), "po-001", "po-create").contains("no stock or payable"))
        val draft = model.purchaseOrderDrafts().single()
        assertEquals(PurchaseOrderStatus.DRAFT, draft.status)
        assertEquals(10, model.stockLevels()["tea_pack"])
        assertEquals(0L, model.supplierPayablePaise("tea_dealer"))
        assertEquals(startingMoves, model.stockMovements().size)

        assertTrue(model.receivePurchaseOrder("po-001", mapOf("tea_pack" to 1), "po-before-send").contains("Only a sent"))
        assertTrue(model.markPurchaseOrderSent("po-001", expectedRevision = 9, commandId = "po-stale-send").contains("changed"))
        assertTrue(model.markPurchaseOrderSent("po-001", expectedRevision = 0, commandId = "po-send").contains("marked sent"))
        assertEquals("SENT", model.purchaseOrderState(model.purchaseOrderDrafts().single()))
        assertTrue(model.receivePurchaseOrder("po-001", mapOf("tea_pack" to 6), "po-overreceive").contains("remaining ordered quantity"))
        assertEquals(10, model.stockLevels()["tea_pack"])
        assertEquals(0L, model.supplierPayablePaise("tea_dealer"))

        assertTrue(model.receivePurchaseOrder("po-001", mapOf("tea_pack" to 2), "po-partial").startsWith("Received"))
        assertEquals(12, model.stockLevels()["tea_pack"])
        assertEquals(2500L, model.supplierPayablePaise("tea_dealer"))
        assertEquals("PARTIALLY RECEIVED", model.purchaseOrderState(model.purchaseOrderDrafts().single()))
        assertTrue(model.receivePurchaseOrder("po-001", mapOf("tea_pack" to 3), "po-final", "TEA-INV-100").startsWith("Received"))
        assertEquals(15, model.stockLevels()["tea_pack"])
        assertEquals(6250L, model.supplierPayablePaise("tea_dealer"))
        assertEquals("RECEIVED", model.purchaseOrderState(model.purchaseOrderDrafts().single()))
        assertEquals("Duplicate command ignored.", model.receivePurchaseOrder("po-001", mapOf("tea_pack" to 3), "po-final"))

        val reopened = CounterModel(persistence, requireConfiguredProducts = true)
        assertEquals(15, reopened.stockLevels()["tea_pack"])
        assertEquals(6250L, reopened.supplierPayablePaise("tea_dealer"))
        assertEquals("RECEIVED", reopened.purchaseOrderState(reopened.purchaseOrderDrafts().single()))
        assertEquals(2, reopened.purchaseHistory().size)
        assertEquals(setOf("po-001"), reopened.purchaseHistory().mapNotNull { it.purchaseOrderId }.toSet())
        assertEquals("TEA-INV-100", reopened.purchaseHistory().single { it.receiptId == "po-final" }.supplierInvoiceReference)
        reopened.close()
    }

    @Test fun supplierInvoiceReferencesAreOptionalValidatedUniquePerSupplierAndPersisted() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("invoice_tea", "Invoice Tea", "Pantry", 1000, priceConfirmed = true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("invoice_tea", 2, "invoice reference fixture", "invoice-opening").contains("2 units"))
        assertTrue(model.saveSupplier("invoice_dealer", "Invoice Dealer", "invoice-dealer").startsWith("Supplier saved"))
        assertTrue(model.receivePurchase("invoice_dealer", mapOf("invoice_tea" to 1), mapOf("invoice_tea" to 500), "invoice-receipt-1", supplierInvoiceReference = "  INV-2026-7  ").startsWith("Received"))
        assertEquals("INV-2026-7", model.purchaseHistory().single().supplierInvoiceReference)
        assertTrue(model.receivePurchase("invoice_dealer", mapOf("invoice_tea" to 1), mapOf("invoice_tea" to 500), "invoice-duplicate", supplierInvoiceReference = "inv-2026-7").contains("already recorded"))
        assertTrue(model.receivePurchase("invoice_dealer", mapOf("invoice_tea" to 1), mapOf("invoice_tea" to 500), "invoice-invalid", supplierInvoiceReference = "BAD\nREFERENCE").contains("control characters"))
        assertEquals(3, model.stockLevels()["invoice_tea"])
        assertEquals(500L, model.supplierPayablePaise("invoice_dealer"))
        model.close()

        val reopened = CounterModel(persistence, requireConfiguredProducts = true)
        assertEquals("INV-2026-7", reopened.purchaseHistory().single().supplierInvoiceReference)
        reopened.close()
    }

    @Test fun purchaseOrderCancellationIsAuditedAndCannotCancelReceivedGoods() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("bread", "Bread", "Bakery", 3000, priceConfirmed = true)).startsWith("Product saved"))
        assertTrue(model.saveSupplier("bakery_dealer", "Bakery Dealer", "po-cancel-supplier").startsWith("Supplier saved"))
        assertTrue(model.createPurchaseOrderDraft("bakery_dealer", mapOf("bread" to 2), mapOf("bread" to 1700), "po-cancel-001", "po-cancel-create").contains("draft"))
        val order = model.purchaseOrderDrafts().single()
        assertTrue(model.cancelPurchaseOrder(order.purchaseOrderId, 2, "no longer required", "po-cancel-stale").contains("changed"))
        assertTrue(model.cancelPurchaseOrder(order.purchaseOrderId, 0, "no longer required", "po-cancel").contains("cancelled"))
        val cancelled = model.purchaseOrderDrafts().single()
        assertEquals(PurchaseOrderStatus.CANCELLED, cancelled.status)
        assertEquals(0L, model.supplierPayablePaise("bakery_dealer"))
        assertTrue(model.auditEvents().any { it.contains("purchase_order.cancelled id=po-cancel-001") })
        assertTrue(model.cancelPurchaseOrder(order.purchaseOrderId, 1, "again", "po-cancel-again").contains("already cancelled"))
    }

    @Test fun syncProjectionDeltaUsesAllowListedFieldsAndBlocksUnsupportedEntities() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence)
        assertTrue(model.apply("prefixed-sale-1", ProposedCommand("create", null, "one chai", "chai", 1)).contains("Order"))
        val createPayload = persistence.lastSyncPayload!!
        assertTrue(createPayload.sendable)
        assertTrue(createPayload.json!!.contains("projection_delta.v1"))
        assertTrue(createPayload.json.contains("\"orderNumber\":12"))
        assertFalse(createPayload.json.contains("audit"))
        assertFalse(createPayload.json.contains("cancelReason"))
        model.cancelOrder(12, "private note 9876543210", "prefixed-cancel-1", model.allOrders().single().revision)
        val cancelPayload = persistence.lastSyncPayload!!
        assertTrue(cancelPayload.sendable)
        assertFalse(cancelPayload.json!!.contains("private note"))
        assertFalse(cancelPayload.json.contains("9876543210"))
        model.close()

        val before = CounterSnapshot(12, emptyList(), emptyMap(), emptySet(), emptyList(), emptyList(), emptyList(), emptyList())
        val after = before.copy(expenses = listOf(BusinessExpense("expense-1", "rent", 100, "cash", "staff_declared", "private note 9876543210", "drawer-private")))
        val expensePayload = SyncOutboxPayloadEncoder.between(before, after)
        assertTrue(expensePayload.sendable)
        assertTrue(expensePayload.json!!.contains("\"expenseId\":\"expense-1\""))
        assertTrue(expensePayload.json.contains("\"amountPaise\":100"))
        assertFalse(expensePayload.json.contains("private note"))
        assertFalse(expensePayload.json.contains("9876543210"))
        assertFalse(expensePayload.json.contains("drawer-private"))

        val customerCredit = CustomerCredit("credit-1", "9876543210", 12, 500, 0)
        val identityBefore = before.copy(orders = listOf(CounterOrderRecord(12, emptyList(), Fulfillment.GIVEN, Payment.CREDIT, 0, true, null, 1, creditPaise = 500)))
        val identityAfter = identityBefore.copy(customerCredits = listOf(customerCredit), paymentEntries = listOf(PaymentEntry("credit-entry", 12, "9876543210", 500, "udhar", "receivable", "credit_sale")))
        val missingOpaqueIdentity = SyncOutboxPayloadEncoder.between(identityBefore, identityAfter)
        assertFalse(missingOpaqueIdentity.sendable)
        assertEquals("opaque_customer_sync_identity_required", missingOpaqueIdentity.blockedReason)
        assertNull(missingOpaqueIdentity.json)
        val identityPayload = SyncOutboxPayloadEncoder.between(identityBefore, identityAfter.copy(
            customerSyncIdentities = mapOf("9876543210" to "55555555-5555-4555-8555-555555555555")
        ))
        assertTrue(identityPayload.sendable)
        val customerJson = identityPayload.json!!
        assertTrue(customerJson.contains("\"customerId\":\"55555555-5555-4555-8555-555555555555\""))
        assertFalse(customerJson.contains("9876543210"))
        assertFalse(customerJson.contains("credit-1"))
        assertFalse(customerJson.contains("credit-entry"))

        val costedOrder = CounterOrderRecord(13,
            listOf(CounterLine("tea", "Tea", 1, 1000, deliveredQuantity = 1, estimatedUnitCostPaise = 425)),
            Fulfillment.GIVEN, Payment.STAFF_DECLARED, paidPaise = 1000, salePosted = true, cancelReason = null, revision = 1)
        val costPayload = SyncOutboxPayloadEncoder.between(before, before.copy(orders = listOf(costedOrder)))
        assertTrue(costPayload.sendable)
        assertTrue(costPayload.json!!.contains("\"estimatedUnitCostPaise\":425"))

        val privateShift = CashDrawerSession("local-session-private-id", 1000, 1, staffLabel = "Private Operator", handoverToLabel = "Private Handover", handoverNote = "Private note", staffActorId = "local-private-actor-id")
        val shiftDelta = SyncOutboxPayloadEncoder.between(before, before.copy(cashDrawerSessions = listOf(privateShift)))
        assertTrue(shiftDelta.sendable)
        val shiftJson = shiftDelta.json!!
        assertTrue(shiftJson.contains("\"staffShifts\":[{"))
        assertTrue(shiftJson.contains("\"sessionType\":\"staff_shift\""))
        listOf("local-session-private-id", "Private Operator", "Private Handover", "Private note", "local-private-actor-id").forEach { assertFalse(shiftJson.contains(it)) }
        val shiftClose = SyncOutboxPayloadEncoder.between(before.copy(cashDrawerSessions = listOf(privateShift)), before.copy(
            cashDrawerSessions = listOf(privateShift.copy(closingCashPaise = 900, closedAt = 2000))
        ))
        assertTrue(shiftClose.sendable)
        assertTrue(shiftClose.json!!.contains("\"closingCashPaise\":900"))
    }

    @Test fun supplierFinancialProjectionUsesPersistedOpaqueIdsAndOmitsSupplierPii() {
        val supplier = SupplierRecord(
            "local_tea_dealer", "Private Distributor Name", "+919876543210",
            "11111111-1111-4111-8111-111111111111"
        )
        val before = CounterSnapshot(12, emptyList(), emptyMap(), emptySet(), emptyList(), emptyList(), emptyList(), emptyList(), suppliers = listOf(supplier))
        val profileOnly = SyncOutboxPayloadEncoder.between(before.copy(suppliers = emptyList()), before)
        assertFalse(profileOnly.sendable)
        assertEquals("entity_contract_pending", profileOnly.blockedReason)

        val receipt = PurchaseReceipt(
            "local-invoice-command-9876543210", supplier.supplierId,
            listOf(PurchaseReceiptLine("local_tea", "Private Tea Name", 4, 1000)),
            totalPaise = 4000,
            supplierInvoiceReference = "PRIVATE-INVOICE-REFERENCE",
            syncId = "22222222-2222-4222-8222-222222222222"
        )
        val payment = SupplierPayment(
            "local-payment-command", supplier.supplierId, 1000, "cash", "staff_declared",
            syncId = "33333333-3333-4333-8333-333333333333"
        )
        val returned = PurchaseReturn(
            "local-return-command", supplier.supplierId, receipt.receiptId,
            listOf(PurchaseReturnLine("local_tea", "Private Tea Name", 1, 1000)), 1000,
            syncId = "44444444-4444-4444-8444-444444444444"
        )
        val projection = SyncOutboxPayloadEncoder.between(before, before.copy(
            purchaseReceipts = listOf(receipt),
            supplierPayments = listOf(payment),
            purchaseReturns = listOf(returned)
        ))
        assertTrue(projection.blockedReason.orEmpty(), projection.sendable)
        val json = projection.json!!
        assertTrue(json.contains("\"supplierId\":\"${supplier.syncId}\""))
        assertTrue(json.contains("\"receiptId\":\"${receipt.syncId}\""))
        assertTrue(json.contains("\"paymentId\":\"${payment.syncId}\""))
        assertTrue(json.contains("\"returnId\":\"${returned.syncId}\""))
        assertTrue(json.contains("\"amountPaise\":1000"))
        listOf(supplier.supplierId, supplier.name, supplier.phone!!, receipt.receiptId, receipt.supplierInvoiceReference!!,
            payment.paymentId, returned.returnId, "Private Tea Name", "local_tea")
            .forEach { privateValue -> assertFalse("Sync payload leaked $privateValue", json.contains(privateValue)) }
    }

    @Test fun syncEnvelopeMapperRejectsInvalidIdentityRevisionVersionAndEventIds() {
        val identity = SyncEnvelopeIdentity(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333"
        )
        val event = SyncOutboxEvent("44444444-4444-4444-8444-444444444444", "sale-command-1", 1,
            "{\"kind\":\"projection_delta.v1\",\"orders\":[],\"stock\":[{\"productId\":\"tea\",\"quantity\":2}],\"stockMovements\":[],\"customerCredits\":[],\"repayments\":[],\"paymentEntries\":[],\"expenses\":[]}")
        assertNull(SyncEnvelopeMapper.batch(identity, -1L, listOf(event)))
        assertNull(SyncEnvelopeMapper.batch(identity.copy(shopId = "invalid"), 0L, listOf(event)))
        assertNull(SyncEnvelopeMapper.batch(identity.copy(deviceId = "1-1-1-1-1"), 0L, listOf(event)))
        assertNull(SyncEnvelopeMapper.batch(identity, 0L, listOf(event.copy(payloadVersion = 2))))
        assertNull(SyncEnvelopeMapper.batch(identity, 0L, listOf(event.copy(eventId = "bad-event-id"))))
    }

    @Test fun orderHistoryDateRangeUsesInclusiveBoundsAndExcludesUndatedLegacyOrders() {
        assertTrue(OrderHistoryDateRange.contains(0L, null, null))
        assertFalse(OrderHistoryDateRange.contains(0L, 100L, 200L))
        assertFalse(OrderHistoryDateRange.contains(99L, 100L, 200L))
        assertTrue(OrderHistoryDateRange.contains(100L, 100L, 200L))
        assertTrue(OrderHistoryDateRange.contains(200L, 100L, 200L))
        assertFalse(OrderHistoryDateRange.contains(201L, 100L, 200L))
        assertTrue(OrderHistoryDateRange.contains(150L, null, 200L))
        assertTrue(OrderHistoryDateRange.contains(150L, 100L, null))
    }

    @Test fun newOrdersReceivePersistableCreationTimestamps() {
        val model = CounterModel(MemoryPersistence())
        val before = System.currentTimeMillis()
        model.apply("timestamp-create", ProposedCommand("create", null, "one chai", "chai", 1))
        val order = model.allOrders().single()
        assertTrue(order.createdAtEpochMs >= before)
        assertTrue(OrderHistoryDateRange.contains(order.createdAtEpochMs, before, System.currentTimeMillis()))
    }

    @Test fun multiProductPhysicalCountIsAtomicAuditedAndIdempotent() {
        val storage = MemoryPersistence()
        val model = CounterModel(storage, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("tea_count", "Tea", "Tea", 1000, priceConfirmed = true)).startsWith("Product saved"))
        assertTrue(model.saveProduct(TapriProduct("sugar_count", "Sugar", "Grocery", 2000, priceConfirmed = true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("tea_count", 5, "opening shelf count", "initial-tea-count").contains("5 units"))
        assertTrue(model.confirmOpeningStock("sugar_count", 8, "opening shelf count", "initial-sugar-count").contains("8 units"))

        assertTrue(model.confirmPhysicalStockCounts(mapOf("tea_count" to 3, "sugar_count" to 0), "evening shelf count", "stock-count-session").contains("2 products"))
        assertEquals(3, model.stockLevels()["tea_count"])
        assertEquals(0, model.stockLevels()["sugar_count"])
        assertEquals(-2, model.stockMovements().single { it.movementId == "stock-count-session:tea_count:physical-count" }.quantityDelta)
        assertEquals(-8, model.stockMovements().single { it.movementId == "stock-count-session:sugar_count:physical-count" }.quantityDelta)
        assertTrue(model.auditEvents().any {
            it.contains("stock.physical_count") && it.contains("products=sugar_count,tea_count") && it.contains("counted=sugar_count:0,tea_count:3")
        })
        val movementsBeforeNoOpCount = model.stockMovements().size
        assertTrue(model.confirmPhysicalStockCounts(mapOf("tea_count" to 3), "unchanged shelf count", "stock-count-noop").contains("1 products"))
        assertTrue(model.auditEvents().any { it.contains("stock-count-noop stock.physical_count") && it.contains("counted=tea_count:3") })
        assertEquals("No-variance count is audited without inventing a stock movement", movementsBeforeNoOpCount, model.stockMovements().size)
        assertTrue(model.purchaseHistory().isEmpty())
        assertEquals("Duplicate command ignored.", model.confirmPhysicalStockCounts(mapOf("tea_count" to 99), "duplicate physical count", "stock-count-session"))
        assertEquals(3, model.stockLevels()["tea_count"])
        assertTrue(model.confirmPhysicalStockCounts(mapOf("tea_count" to -1), "invalid count", "stock-count-invalid").contains("0 and 100,000"))

        storage.failNext = true
        assertTrue(model.confirmPhysicalStockCounts(mapOf("tea_count" to 4, "sugar_count" to 2), "rollback physical count", "stock-count-rollback").contains("No change made"))
        assertEquals(3, model.stockLevels()["tea_count"])
        assertEquals(0, model.stockLevels()["sugar_count"])
        CounterSnapshotIntegrity.requireConsistent(storage.state!!)
    }

    @Test fun pendingUpiCanBeResolvedAsStaffDeclaredReceiptWithoutProviderVerification() {
        val storage = MemoryPersistence()
        val model = CounterModel(storage)
        assertTrue(model.saveProduct(TapriProduct("chai", "Chai", "Tea", 1000, priceConfirmed = true)).startsWith("Product saved"))
        assertTrue(model.confirmOpeningStock("chai", 5, "synthetic UPI settlement", "upi-stock").contains("5 units"))
        assertTrue(model.apply("upi-order", ProposedCommand("create", null, "one chai", "chai", 1)).contains("Order"))
        val order = model.allOrders().single().number
        model.apply("upi-ready", ProposedCommand("ready", order, "ready"))
        model.apply("upi-given", ProposedCommand("given", order, "given"))
        assertTrue(model.recordUpiPending(order, "upi-attempt").contains("not verified"))
        val pending = model.paymentHistory().single { it.method == "upi" && it.status == "pending" }

        assertTrue(model.recordUpiReceived(order, 20_000, "upi-too-large").contains("cannot exceed"))
        assertEquals(Payment.UPI_PENDING, model.allOrders().single().payment)
        assertTrue(model.recordUpiReceived(order, 400, "upi-received").contains("not provider-verified"))
        assertEquals(1100, model.allOrders().single().duePaise)
        assertEquals(400, model.allOrders().single().paidPaise)
        val cancellation = model.paymentHistory().single { it.kind == "payment_attempt_cancelled" }
        assertEquals(pending.entryId, cancellation.relatedEntryId)
        val receipt = model.paymentHistory().single { it.entryId == "upi-received" }
        assertEquals("staff_declared", receipt.status)
        assertEquals("order_collection", receipt.kind)
        assertFalse(model.paymentHistory().any { it.status == "verified" })
        assertEquals("Duplicate command ignored.", model.recordUpiReceived(order, 400, "upi-received"))
        assertEquals(400, model.allOrders().single().paidPaise)
    }

    @Test fun purchaseReceiptAndSupplierSettlementAreAtomicAndPersisted() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("tea_packet", "Tea Packet", "Tea", 2500, true, false, true)).startsWith("Product saved"))
        assertTrue(model.saveSupplier("dealer_1", "Local Tea Dealer", "supplier-create").startsWith("Supplier saved"))
        assertTrue(model.receivePurchase("dealer_1", mapOf("tea_packet" to 10), mapOf("tea_packet" to 500), "receipt-before-count").contains("Confirm the physical stock baseline"))
        assertEquals(0, model.stockLevels()["tea_packet"])
        assertEquals(0L, model.supplierPayablePaise("dealer_1"))
        assertTrue(model.confirmOpeningStock("tea_packet", 0, "counted before receiving", "supplier-stock-count").contains("0 units"))

        persistence.failNext = true
        assertTrue(model.receivePurchase("dealer_1", mapOf("tea_packet" to 10), mapOf("tea_packet" to 500), "receipt-once").contains("Could not save this action locally"))
        assertEquals(0, model.stockLevels()["tea_packet"])
        assertTrue(model.purchaseHistory().isEmpty())
        assertEquals(0L, model.supplierPayablePaise("dealer_1"))

        assertTrue(model.receivePurchase("dealer_1", mapOf("tea_packet" to 10), mapOf("tea_packet" to 500), "receipt-once").contains("Received 1 product lines"))
        assertEquals(10, model.stockLevels()["tea_packet"])
        assertEquals(5000L, model.supplierPayablePaise("dealer_1"))
        assertEquals("Duplicate command ignored.", model.receivePurchase("dealer_1", mapOf("tea_packet" to 10), mapOf("tea_packet" to 500), "receipt-once"))
        assertTrue(model.recordSupplierCashPayment("dealer_1", 2000, "dealer-payment-1").startsWith("Staff-declared cash payment"))
        assertEquals(3000L, model.supplierPayablePaise("dealer_1"))
        assertTrue(model.recordSupplierCashPayment("dealer_1", 3001, "dealer-overpay").contains("cannot exceed supplier payable"))
        assertEquals(1, model.stockMovements().count { it.reason == "purchase_receipt" })
        CounterSnapshotIntegrity.requireConsistent(persistence.state!!)

        val reopened = CounterModel(persistence, requireConfiguredProducts = true)
        assertEquals(10, reopened.stockLevels()["tea_packet"])
        assertEquals(3000L, reopened.supplierPayablePaise("dealer_1"))
        assertEquals(1, reopened.purchaseHistory().size)
        assertEquals(1, reopened.supplierPaymentHistory().size)
    }

    @Test fun supplierUpiSettlementIsStaffDeclaredAndDoesNotChangeCashDrawer() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("tea_packet", "Tea Packet", "Tea", 2500, true, false, true)).startsWith("Product saved"))
        assertTrue(model.saveSupplier("dealer_1", "Local Tea Dealer", "upi-dealer-create").startsWith("Supplier saved"))
        assertTrue(model.confirmOpeningStock("tea_packet", 0, "count before receipt", "upi-dealer-stock").contains("0 units"))
        assertTrue(model.receivePurchase("dealer_1", mapOf("tea_packet" to 5), mapOf("tea_packet" to 500), "upi-dealer-receipt").startsWith("Received"))
        assertTrue(model.openCashDrawer(10000, "upi-dealer-drawer").startsWith("Cash drawer opened"))

        assertTrue(model.recordSupplierPayment("dealer_1", 750, "upi", "upi-dealer-payment").contains("Staff-declared UPI payment"))
        assertEquals(1750L, model.supplierPayablePaise("dealer_1"))
        assertEquals(10000L, model.expectedCashPaise(model.activeCashDrawer()!!.sessionId))
        val payment = model.supplierPaymentHistory().single()
        assertEquals("upi", payment.method)
        assertEquals("staff_declared", payment.status)
        assertEquals(null, payment.drawerSessionId)
        assertEquals("Duplicate command ignored.", model.recordSupplierPayment("dealer_1", 750, "upi", "upi-dealer-payment"))
        assertTrue(model.recordSupplierPayment("dealer_1", 500, "card", "upi-dealer-unsupported").contains("Choose cash or UPI"))
        CounterSnapshotIntegrity.requireConsistent(persistence.state!!)

        val reopened = CounterModel(persistence, requireConfiguredProducts = true)
        assertEquals(1750L, reopened.supplierPayablePaise("dealer_1"))
        assertEquals("upi", reopened.supplierPaymentHistory().single().method)
        assertEquals("staff_declared", reopened.supplierPaymentHistory().single().status)
    }

    @Test fun multiProductReceiptPostsEachStockMovementAndOnePayable() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        model.saveProduct(TapriProduct("tea_packet", "Tea Packet", "Tea", 2500, true, false, true))
        model.saveProduct(TapriProduct("sugar", "Sugar", "Grocery", 3000, true, false, true))
        model.saveSupplier("dealer_1", "Local Dealer", "multi-supplier")
        model.confirmOpeningStock("tea_packet", 4, "count", "multi-count-tea")
        model.confirmOpeningStock("sugar", 8, "count", "multi-count-sugar")

        val result = model.receivePurchase(
            "dealer_1", mapOf("tea_packet" to 10, "sugar" to 5),
            mapOf("tea_packet" to 500, "sugar" to 700), "multi-receipt"
        )

        assertTrue(result.contains("Received 2 product lines"))
        assertEquals(14, model.stockLevels()["tea_packet"])
        assertEquals(13, model.stockLevels()["sugar"])
        assertEquals(8500L, model.supplierPayablePaise("dealer_1"))
        assertEquals(setOf("tea_packet", "sugar"), model.purchaseHistory().single().lines.map { it.productId }.toSet())
        assertEquals(2, model.stockMovements().count { it.reason == "purchase_receipt" })
        CounterSnapshotIntegrity.requireConsistent(persistence.state!!)
    }

    @Test fun supplierReturnReducesAvailableStockAndOnlyUnpaidPayable() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        model.saveProduct(TapriProduct("tea_packet", "Tea Packet", "Tea", 2500, true, false, true))
        model.saveSupplier("dealer_1", "Local Dealer", "return-supplier")
        model.confirmOpeningStock("tea_packet", 0, "count", "return-stock")
        model.receivePurchase("dealer_1", mapOf("tea_packet" to 10), mapOf("tea_packet" to 500), "return-receipt")

        assertTrue(model.recordSupplierCashPayment("dealer_1", 1000, "return-prior-payment").startsWith("Staff-declared cash payment"))
        assertEquals(4000L, model.supplierPayablePaise("dealer_1"))
        assertTrue(model.returnPurchase("dealer_1", "return-receipt", mapOf("tea_packet" to 2), "damaged sealed packs", "return-once").contains("Purchase return recorded"))
        assertEquals(8, model.stockLevels()["tea_packet"])
        assertEquals(3000L, model.supplierPayablePaise("dealer_1"))
        assertEquals(1000, model.supplierPaymentHistory().single().amountPaise)
        assertEquals(1000L, model.purchaseReturnHistory().single().totalPaise)
        assertEquals(1, model.stockMovements().count { it.reason == "purchase_return" })
        assertEquals("Duplicate command ignored.", model.returnPurchase("dealer_1", "return-receipt", mapOf("tea_packet" to 2), "damaged sealed packs", "return-once"))
        assertTrue(model.returnPurchase("dealer_1", "return-receipt", mapOf("tea_packet" to 9), "too many", "return-too-many").contains("exceeds the unreturned"))
        assertTrue(model.returnPurchase("dealer_1", "return-receipt", mapOf("tea_packet" to 1), "x", "return-no-reason").contains("Enter returned products and a reason"))
        assertEquals(8, model.stockLevels()["tea_packet"])
        assertEquals(3000L, model.supplierPayablePaise("dealer_1"))
        CounterSnapshotIntegrity.requireConsistent(persistence.state!!)
        assertEquals(8, model.stockLevels()["tea_packet"])
        assertEquals(3000L, model.supplierPayablePaise("dealer_1"))
        assertEquals(-2, model.stockMovements().single { it.reason == "purchase_return" }.quantityDelta)
        CounterSnapshotIntegrity.requireConsistent(persistence.state!!)
    }

    @Test fun operatingExpensesAreValidatedIdempotentAndRollbackOnPersistenceFailure() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence)
        assertTrue(model.recordExpense("transport", 3450, "cash", "vegetable delivery", "expense-one").startsWith("Expense recorded"))
        assertEquals(3450L, model.totalExpensesPaise())
        assertEquals("Duplicate command ignored.", model.recordExpense("transport", 3450, "cash", "vegetable delivery", "expense-one"))
        assertTrue(model.recordExpense("rent", 0, "cash", commandId = "expense-zero").contains("positive"))
        assertTrue(model.recordExpense("rent", 1000, "unknown", commandId = "expense-bad-method").contains("Choose cash, UPI or card"))
        persistence.failNext = true
        assertTrue(model.recordExpense("electricity", 5000, "upi", "", "expense-failed").contains("Could not save this action locally"))
        assertEquals(3450L, model.totalExpensesPaise())
        assertEquals(1, model.expenseHistory().size)
        CounterSnapshotIntegrity.requireConsistent(persistence.state!!)
    }

    @Test fun quickSaleSplitPostsCashAndCustomerCreditAtomicallyAndIsIdempotent() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("split_tea", "Split Tea", "Chai", 1500, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("split_tea", 4, "physical opening count", "split-opening").contains("4 units"))
        assertTrue(model.saveCustomerProfile(CustomerProfile("split_customer", "Ravi")).contains("profile saved"))

        assertTrue(model.quickSaleSplit(listOf("split_tea" to 2), 700, "split_customer", "split-sale").startsWith("Quick sale"))
        val order = model.allOrders().single()
        assertEquals(3000, order.salePaise)
        assertEquals(700, order.paidPaise)
        assertEquals(2300, order.creditPaise)
        assertEquals(0, order.duePaise)
        assertEquals(Payment.SPLIT, order.payment)
        assertEquals(2, model.paymentHistory().count { it.orderNumber == order.number })
        assertEquals("staff_declared", model.paymentHistory().single { it.method == "cash" }.status)
        assertEquals("receivable", model.paymentHistory().single { it.method == "udhar" }.status)
        assertEquals("split_customer", model.customerCredits().single().customerId)
        assertEquals(2300, model.creditBalance("split_customer"))
        assertEquals(2, model.stockLevels()["split_tea"])
        assertEquals(-2, model.stockMovements().single { it.reason == "quick_sale" }.quantityDelta)
        assertEquals("Duplicate command ignored.", model.quickSaleSplit(listOf("split_tea" to 2), 700, "split_customer", "split-sale"))
        assertEquals(2, model.paymentHistory().count { it.orderNumber == order.number })
        CounterSnapshotIntegrity.requireConsistent(persistence.state!!)
    }

    @Test fun quickSaleSplitRejectsInvalidTenderAndRollsBackStorageFailure() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence, requireConfiguredProducts = true)
        assertTrue(model.saveProduct(TapriProduct("split_tea", "Split Tea", "Chai", 1500, priceConfirmed = true)).contains("Product saved"))
        assertTrue(model.confirmOpeningStock("split_tea", 2, "physical opening count", "split-failure-opening").contains("2 units"))
        assertTrue(model.quickSaleSplit(listOf("split_tea" to 1), 1500, "split_customer", "split-equal").contains("less than the sale total"))
        assertTrue(model.quickSaleSplit(listOf("split_tea" to 1), 500, "", "split-no-customer").contains("Select a customer"))
        persistence.failNext = true
        assertTrue(model.quickSaleSplit(listOf("split_tea" to 1), 500, "split_customer", "split-failure").contains("Could not save this action locally"))
        assertTrue(model.allOrders().isEmpty())
        assertEquals(2, model.stockLevels()["split_tea"])
        assertTrue(model.paymentHistory().isEmpty())
        assertTrue(model.customerCredits().isEmpty())
    }

    @Test fun cashDrawerSessionReconcilesLinkedCashEventsAndKeepsVarianceUnresolved() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence)
        assertTrue(model.openCashDrawer(5_000, "drawer-open").contains("opened"))
        assertTrue(model.quickSale(listOf("chai" to 1), "cash", commandId = "drawer-sale").startsWith("Quick sale"))
        assertTrue(model.recordExpense("cleaning", 200, "cash", "soap", "drawer-expense").startsWith("Expense recorded"))
        assertEquals(6_300L, model.expectedCashPaise("drawer-open"))
        assertTrue(model.recordCashDrawerMovement("cash_out", 500, "owner change", "drawer-out").contains("Cash removed"))
        assertEquals(5_800L, model.expectedCashPaise("drawer-open"))
        assertTrue(model.recordCashDrawerMovement("cash_out", 5_801, "too much cash", "drawer-overdraw").contains("cannot exceed"))
        val closed = model.closeCashDrawer(5_700, "drawer-close")
        assertTrue(closed.contains("unresolved variance"))
        assertEquals(5_800L, model.expectedCashPaise("drawer-open"))
        assertEquals(5_700, model.cashDrawerHistory().first().closingCashPaise)
        assertTrue(model.recordCashClosingReview("drawer-open", "owner to recount float", "drawer-review").contains("review recorded"))
        assertTrue(model.recordCashClosingReview("drawer-open", commandId = "drawer-review-again").contains("already recorded"))
        assertEquals("Duplicate command ignored.", model.closeCashDrawer(5_700, "drawer-close"))
        CounterSnapshotIntegrity.requireConsistent(persistence.state!!)

        val reopened = CounterModel(persistence)
        assertNull(reopened.activeCashDrawer())
        assertEquals(5_800L, reopened.expectedCashPaise("drawer-open"))
        assertEquals(1, reopened.cashDrawerMovements("drawer-open").size)
        assertEquals("owner to recount float", reopened.closingReview("drawer-open")?.note)
        assertTrue(reopened.openCashDrawer(1_000, "drawer-next").contains("opened"))
        assertEquals(1_000L, reopened.expectedCashPaise("drawer-next"))
    }

    @Test fun backupReconciliationDetectsCreditProjectionMismatchWithoutRepairingIt() {
        val persistence = MemoryPersistence()
        val model = CounterModel(persistence)
        model.apply("reconcile-create", ProposedCommand("create", null, "one chai", "chai", 1))
        val order = model.allOrders().single()
        model.apply("reconcile-ready", ProposedCommand("ready", order.number, "ready", expectedRevision = order.revision))
        val ready = model.allOrders().single()
        model.apply("reconcile-given", ProposedCommand("given", ready.number, "given", expectedRevision = ready.revision))
        model.recordUdhar(ready.number, "reconcile-customer", "reconcile-credit")
        val consistent = persistence.state!!
        CounterSnapshotIntegrity.requireConsistent(consistent)

        val broken = consistent.copy(orders = consistent.orders.map { it.copy(creditPaise = it.creditPaise + 1) })
        try {
            CounterSnapshotIntegrity.requireConsistent(broken)
            throw AssertionError("A mismatched receivable projection must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("receivable projection 1501p differs from credit ledger 1500p"))
        }
        assertEquals(1500, persistence.state!!.orders.single().creditPaise)
    }

    @Test fun criticalOrderSequenceKeepsPaymentSeparateFromHandover() {
        val model = CounterModel()
        val create = model.parse("Naya order, do cutting chai")!!
        assertTrue(model.apply("create-12", create).contains("Order #12"))
        assertTrue(model.apply("ready-12", model.parse("Order 12 ready hai")!!).contains("ready"))
        val given = model.apply("given-12", model.parse("Order 12 de diya")!!)
        assertTrue(given.contains("payment remains unpaid"))
        assertTrue(model.apply("cash-12", model.parse("Order 12 cash payment mila")!!).contains("Cash declared"))
        val order = model.allOrders().single()
        assertEquals(Fulfillment.GIVEN, order.fulfillment)
        assertEquals(Payment.STAFF_DECLARED, order.payment)
        val cashEntry = model.paymentHistory().single()
        assertEquals(PaymentEntry("cash-12", 12, null, 2000, "cash", "staff_declared", "order_collection").copy(syncId = cashEntry.syncId), cashEntry)
    }

    @Test fun unknownOrderDoesNotMutateBusinessState() {
        val model = CounterModel()
        val command = model.parse("Order 99 ready hai")!!
        assertEquals("Order not found. No change made.", model.apply("unknown-99", command))
        assertTrue(model.allOrders().isEmpty())
    }

    @Test fun duplicateCommandIdIsIdempotent() {
        val model = CounterModel()
        val command = model.parse("Naya order, do cutting chai")!!
        model.apply("same-command-id", command)
        assertEquals("Duplicate command ignored.", model.apply("same-command-id", command))
        assertEquals(1, model.allOrders().size)
    }

    @Test fun invalidTransitionsAndDuplicateCashCannotChangePaymentTwice() {
        val model = CounterModel()
        val create = model.parse("Naya order, do chai")!!
        model.apply("create", create)
        assertTrue(model.apply("cash-too-early", model.parse("Order 12 cash mila")!!).startsWith("Cash not recorded"))
        model.apply("ready", model.parse("Order 12 ready hai")!!)
        model.apply("given", model.parse("Order 12 de diya")!!)
        model.apply("cash", model.parse("Order 12 cash payment mila")!!)
        assertTrue(model.apply("cash-again", model.parse("Order 12 cash payment mila")!!).startsWith("Cash not recorded"))
        assertEquals(Payment.STAFF_DECLARED, model.allOrders().single().payment)
    }

    @Test fun unrecognizedAndUnknownProductCommandsAreRejected() {
        val model = CounterModel()
        assertNull(model.parse("background conversation about chai"))
        assertNull(model.parse("Naya order, one unknown item"))
        assertNotNull(model.parse("New order, one chai"))
    }

    @Test fun devanagariAndMarathiAliasesCreateTheExpectedOrder() {
        val hindi = CounterModel()
        val command = hindi.parse("नया ऑर्डर दो कटिंग चाय")!!
        hindi.apply("hindi-1",command)
        assertEquals("2 × Cutting Chai",hindi.allOrders().single().items)
        val marathi = CounterModel()
        marathi.apply("marathi-1",marathi.parse("नवीन ऑर्डर दोन चहा")!!)
        assertEquals("2 × Regular Chai",marathi.allOrders().single().items)
        assertEquals(3000,marathi.allOrders().single().totalPaise)
    }

    @Test fun explicitCashAmountIsInPaiseAndOverpaymentIsRejected() {
        val model = CounterModel()
        model.apply("create",model.parse("Naya order, do cutting chai")!!)
        model.apply("ready",model.parse("Order 12 ready hai")!!)
        model.apply("given",model.parse("Order 12 de diya")!!)
        val cash = model.parse("Order १२ ka पन्नास cash mila")!!
        assertEquals(5000,cash.amountPaise)
        assertTrue(model.apply("overpay",cash).startsWith("Cash amount exceeds"))
        assertEquals(0,model.allOrders().single().paidPaise)
    }

    @Test fun handoverPostsConfiguredStockOnceAndPaymentRemainsIndependent() {
        val model=CounterModel()
        model.apply("create",model.parse("Naya order, do cutting chai")!!)
        model.apply("ready",model.parse("Order 12 ready hai")!!)
        val result=model.apply("given",model.parse("Order 12 de diya")!!)
        assertTrue(result.contains("payment remains unpaid"))
        assertEquals(98,model.stockLevels()["cutting_chai"])
        assertTrue(model.apply("given-again",model.parse("Order 12 de diya")!!).contains("ready"))
        assertEquals(98,model.stockLevels()["cutting_chai"])
        assertTrue(model.allOrders().single().salePosted)
    }

    @Test fun partialCashCollectionsUsePaiseAndDoNotOvercollect() {
        val model=CounterModel()
        model.apply("create",model.parse("Naya order, do cutting chai")!!)
        model.apply("ready",model.parse("Order 12 ready hai")!!)
        model.apply("given",model.parse("Order 12 de diya")!!)
        assertTrue(model.apply("part1",model.parse("Order 12 ka 5 cash mila")!!).contains("balance ₹15.00"))
        assertEquals(Payment.PARTIALLY_PAID,model.allOrders().single().payment)
        assertEquals(1500,model.allOrders().single().duePaise)
        assertEquals(500, model.paymentHistory().single().amountPaise)
    }

    @Test fun committedOrdersAndCommandIdsSurviveModelRecreation() {
        val storage = MemoryPersistence()
        val first = CounterModel(storage)
        first.apply("create-persisted", first.parse("Naya order, do cutting chai")!!)
        first.apply("ready-persisted", first.parse("Order 12 ready hai")!!)
        first.apply("given-persisted", first.parse("Order 12 de diya")!!)
        assertEquals(98, first.stockLevels()["cutting_chai"])
        val restarted = CounterModel(storage)
        assertEquals(Fulfillment.GIVEN, restarted.allOrders().single().fulfillment)
        assertEquals(98, restarted.stockLevels()["cutting_chai"])
        assertEquals("Duplicate command ignored.", restarted.apply("given-persisted", restarted.parse("Order 12 de diya")!!))
        assertEquals(1, restarted.stockMovements().size)
    }

    @Test fun persistenceFailureRollsBackBusinessAndStockMutation() {
        val storage = MemoryPersistence()
        val model = CounterModel(storage)
        model.apply("create", model.parse("Naya order, do chai")!!)
        model.apply("ready", model.parse("Order 12 ready hai")!!)
        val stockBefore = model.stockLevels()
        storage.failNext = true
        assertTrue(model.apply("given-fails", model.parse("Order 12 de diya")!!).startsWith("Could not save"))
        assertEquals(stockBefore, model.stockLevels())
        assertEquals(Fulfillment.READY, model.allOrders().single().fulfillment)
        assertTrue(model.stockMovements().isEmpty())
    }

    @Test fun removingLastOrderLineCannotLeaveAnEmptyDraft() {
        val model = CounterModel()
        model.apply("create", model.parse("Naya order, one samosa")!!)
        assertTrue(model.apply("remove-last", ProposedCommand("remove", 12, "remove", "samosa", 1)).contains("cannot be empty"))
        assertEquals(1, model.allOrders().single().lines.size)
    }

    @Test fun partialHandoverPostsOnlyDeliveredStockAndThenTheRemainder() {
        val model = CounterModel()
        model.apply("create", model.parse("Naya order, do chai")!!)
        model.apply("ready", model.parse("Order 12 ready hai")!!)
        assertTrue(model.apply("partial", model.parse("Order 12 ek chai de diya")!!).contains("more items remain"))
        assertEquals(Fulfillment.PARTIALLY_GIVEN, model.allOrders().single().fulfillment)
        assertEquals(99, model.stockLevels()["chai"])
        assertEquals(1, model.stockMovements().single().quantityDelta * -1)
        assertTrue(model.apply("remainder", model.parse("Order 12 de diya")!!).contains("sale recorded"))
        assertEquals(Fulfillment.GIVEN, model.allOrders().single().fulfillment)
        assertEquals(98, model.stockLevels()["chai"])
        assertEquals(2, model.stockMovements().sumOf { -it.quantityDelta })
    }

    @Test fun multiLinePartialHandoverIsAtomicAuditedAndIdempotent() {
        val model = CounterModel()
        model.apply("batch-create", model.parse("Naya order, do chai")!!)
        model.apply("batch-add", ProposedCommand("add", 12, "add snacks", "samosa", 2))
        model.apply("batch-ready", ProposedCommand("ready", 12, "mark ready", expectedRevision = model.allOrders().single().revision))
        val revision = model.allOrders().single().revision
        val beforeInvalidBatch = model.allOrders().single().let { it.copy(lines = it.lines.toMutableList()) }
        val stockBeforeInvalidBatch = model.stockLevels()
        val movementsBeforeInvalidBatch = model.stockMovements()
        assertTrue(model.partialHandover(12, mapOf("chai" to 1, "samosa" to 3), "invalid-batch", revision).contains("exceeds remaining"))
        assertEquals(beforeInvalidBatch, model.allOrders().single())
        assertEquals(stockBeforeInvalidBatch, model.stockLevels())
        assertEquals(movementsBeforeInvalidBatch, model.stockMovements())

        val result = model.partialHandover(12, mapOf("chai" to 1, "samosa" to 2), "batch-handover", revision)
        assertTrue(result.contains("1 × Regular Chai"))
        assertTrue(result.contains("2 × Samosa"))
        val order = model.allOrders().single()
        assertEquals(Fulfillment.PARTIALLY_GIVEN, order.fulfillment)
        assertFalse(order.salePosted)
        assertEquals(5500, order.salePaise)
        assertEquals(1, order.lines.single { it.productId == "chai" }.deliveredQuantity)
        assertEquals(2, order.lines.single { it.productId == "samosa" }.deliveredQuantity)
        assertEquals(99, model.stockLevels()["chai"])
        assertEquals(98, model.stockLevels()["samosa"])
        assertEquals(2, model.stockMovements().count { it.orderNumber == 12 })
        assertTrue(model.auditEvents().any { it.contains("order.partial_handover #12") })

        val snapshot = model.allOrders().single().let { it to model.stockLevels().toMap() to model.stockMovements().toList() }
        assertEquals("Duplicate command ignored.", model.partialHandover(12, mapOf("chai" to 1), "batch-handover", order.revision))
        assertEquals(snapshot.first.first, model.allOrders().single())
        assertEquals(snapshot.first.second, model.stockLevels())
        assertEquals(snapshot.second, model.stockMovements())
        assertTrue(model.partialHandover(12, mapOf("chai" to 1), "stale-batch", revision).contains("revision conflict"))
    }

    @Test fun udharAndRepaymentsMaintainCustomerBalanceAndOrderPaymentState() {
        val model = CounterModel()
        model.apply("create", model.parse("Naya order, one chai")!!)
        model.apply("ready", model.parse("Order 12 ready hai")!!)
        model.apply("given", model.parse("Order 12 de diya")!!)
        assertTrue(model.recordUdhar(12,"customer-1","udhar-1").contains("Udhar"))
        assertEquals(Payment.CREDIT, model.allOrders().single().payment)
        assertEquals(1500, model.creditBalance("customer-1"))
        assertTrue(model.repayCredit("customer-1",500,"repay-1").contains("allocated"))
        assertEquals(1000, model.creditBalance("customer-1"))
        assertEquals(Payment.SPLIT, model.allOrders().single().payment)
        assertTrue(model.repayCredit("customer-1",1000,"repay-2").contains("allocated"))
        assertEquals(0, model.creditBalance("customer-1"))
        assertEquals(Payment.STAFF_DECLARED, model.allOrders().single().payment)
        assertTrue(model.repayCredit("customer-1",1,"overpay").contains("exceeds"))
    }

    @Test fun paymentScreenSplitCollectionAtomicallyPostsCashAndUdhar() {
        val storage = MemoryPersistence()
        val model = CounterModel(storage)
        model.apply("split-create", model.parse("Naya order, one chai")!!)
        model.apply("split-ready", model.parse("Order 12 ready hai")!!)
        model.apply("split-given", model.parse("Order 12 de diya")!!)
        val before = model.allOrders().single().let { it.copy(lines = it.lines.toMutableList()) }

        assertTrue(model.recordCashUdharSplit(12, 500, "ramesh", "payment-split-1", before.revision).contains("Split recorded"))
        val order = model.allOrders().single()
        assertEquals(0, order.duePaise)
        assertEquals(500, order.paidPaise)
        assertEquals(1000, order.creditPaise)
        assertEquals(Payment.SPLIT, order.payment)
        assertEquals(1000, model.creditBalance("ramesh"))
        assertEquals(listOf("cash", "udhar"), model.paymentHistory().map { it.method })
        assertEquals("staff_declared", model.paymentHistory().single { it.method == "cash" }.status)
        assertEquals("receivable", model.paymentHistory().single { it.method == "udhar" }.status)
        assertEquals(1, model.auditEvents().count { it.startsWith("payment-split-1 ") })
        assertEquals(setOf("payment-split-1-cash", "payment-split-1-udhar"), model.paymentHistory().map { it.entryId }.toSet())
        assertEquals("Duplicate command ignored.", model.recordCashUdharSplit(12, 500, "ramesh", "payment-split-1", order.revision))
        assertEquals(2, model.paymentHistory().size)
        assertTrue(storage.lastSyncPayload?.sendable == true)
        assertTrue(storage.lastSyncPayload?.json?.contains("ramesh") == false)
    }

    @Test fun cashUpiPendingSplitSeparatesCollectedCashFromPendingAndIsIdempotent() {
        val storage = MemoryPersistence()
        val model = CounterModel(storage)
        model.apply("cash-upi-create", model.parse("Naya order, one chai")!!)
        model.apply("cash-upi-ready", model.parse("Order 12 ready hai")!!)
        model.apply("cash-upi-given", model.parse("Order 12 de diya")!!)
        val revision = model.allOrders().single().revision

        val splitResult = model.recordCashUpiPendingSplit(12, 500, "cash-upi-split", revision)
        assertTrue("Unexpected split result: $splitResult", splitResult.contains("Split recorded") && splitResult.contains("UPI pending"))
        var order = model.allOrders().single()
        assertEquals(Payment.UPI_PENDING, order.payment)
        assertEquals(500, order.paidPaise)
        assertEquals(1000, order.duePaise)
        assertEquals(500, model.paymentHistory().single { it.method == "cash" }.amountPaise)
        val pending = model.paymentHistory().single { it.method == "upi" && it.status == "pending" }
        assertEquals(1000, pending.amountPaise)
        assertEquals("Duplicate command ignored.", model.recordCashUpiPendingSplit(12, 500, "cash-upi-split", order.revision))
        assertEquals(2, model.paymentHistory().size)

        assertTrue(model.recordUpiReceived(12, 1000, "cash-upi-received", order.revision).contains("not provider-verified"))
        order = model.allOrders().single()
        assertEquals(Payment.STAFF_DECLARED, order.payment)
        assertEquals(1500, order.paidPaise)
        assertEquals(0, order.duePaise)
        assertEquals(pending.entryId, model.paymentHistory().single { it.kind == "payment_attempt_cancelled" }.relatedEntryId)
        assertEquals(0, model.paymentHistory().count { it.status == "verified" })
        assertEquals(1, model.auditEvents().count { it.startsWith("cash-upi-split ") })
    }

    @Test fun directUpiReceiptIsStaffDeclaredAndCannotReplacePendingAttempt() {
        val model = CounterModel()
        model.apply("direct-upi-create", model.parse("Naya order, one chai")!!)
        model.apply("direct-upi-ready", model.parse("Order 12 ready hai")!!)
        model.apply("direct-upi-given", model.parse("Order 12 de diya")!!)
        var order = model.allOrders().single()
        assertTrue(model.recordUpiDirect(12, 700, "direct-upi-1", order.revision).contains("not provider-verified"))
        order = model.allOrders().single()
        assertEquals(Payment.PARTIALLY_PAID, order.payment)
        assertEquals(700, order.paidPaise)
        assertEquals(800, order.duePaise)
        val entry = model.paymentHistory().single()
        assertEquals("upi", entry.method)
        assertEquals("staff_declared", entry.status)
        assertEquals(0, model.paymentHistory().count { it.status == "verified" })
        assertEquals("Duplicate command ignored.", model.recordUpiDirect(12, 700, "direct-upi-1", order.revision))

        assertTrue(model.recordUpiPending(12, "direct-upi-pending", order.revision).contains("UPI pending"))
        order = model.allOrders().single()
        assertTrue(model.recordUpiDirect(12, 100, "direct-upi-blocked", order.revision).contains("Resolve any pending UPI"))
        assertEquals(700, model.allOrders().single().paidPaise)
        assertEquals(1, model.paymentHistory().count { it.status == "staff_declared" })
    }

    @Test fun splitCollectionRejectsInvalidAmountsStaleRevisionAndDifferentCreditOwner() {
        val model = CounterModel()
        model.apply("split-invalid-create", model.parse("Naya order, one chai")!!)
        model.apply("split-invalid-ready", model.parse("Order 12 ready hai")!!)
        model.apply("split-invalid-given", model.parse("Order 12 de diya")!!)
        val revision = model.allOrders().single().revision
        assertTrue(model.recordCashUdharSplit(12, 0, "ramesh", "split-zero", revision).contains("Cash must be positive"))
        assertTrue(model.recordCashUdharSplit(12, 1500, "ramesh", "split-full", revision).contains("Cash must be positive"))
        assertTrue(model.recordCashUdharSplit(12, 500, "", "split-no-customer", revision).contains("Select a customer"))
        assertEquals(0, model.paymentHistory().size)
        assertTrue(model.recordUdhar(12, "other", "existing-credit", 200, revision).contains("Udhar"))
        val creditedRevision = model.allOrders().single().revision
        assertTrue(model.recordCashUdharSplit(12, 500, "ramesh", "split-wrong-customer", creditedRevision).contains("another customer"))
        assertTrue(model.recordCashUdharSplit(12, 500, "other", "split-stale", revision).contains("revision conflict"))
        assertEquals(1, model.paymentHistory().size)
    }

    @Test fun splitCollectionPersistenceFailureRollsBackBothTendersAndReceivable() {
        val storage = MemoryPersistence()
        val model = CounterModel(storage)
        model.apply("split-fail-create", model.parse("Naya order, one chai")!!)
        model.apply("split-fail-ready", model.parse("Order 12 ready hai")!!)
        model.apply("split-fail-given", model.parse("Order 12 de diya")!!)
        val before = model.allOrders().single().let { it.copy(lines = it.lines.toMutableList()) }
        val oldAudit = model.auditEvents()
        storage.failNext = true
        assertTrue(model.recordCashUdharSplit(12, 500, "ramesh", "split-fail-save", before.revision).contains("No change made"))
        assertEquals(before, model.allOrders().single())
        assertTrue(model.paymentHistory().isEmpty())
        assertTrue(model.customerCredits().isEmpty())
        assertEquals(oldAudit, model.auditEvents())
        assertTrue(model.recordCashUdharSplit(12, 500, "ramesh", "split-fail-save", before.revision).contains("Split recorded"))
    }

    @Test fun creditAndRepaymentLedgerSurviveModelRecreation() {
        val storage=MemoryPersistence()
        val first=CounterModel(storage)
        first.apply("create",first.parse("Naya order, one samosa")!!)
        first.apply("ready",first.parse("Order 12 ready hai")!!)
        first.apply("given",first.parse("Order 12 de diya")!!)
        first.recordUdhar(12,"shop-customer-7","credit")
        first.repayCredit("shop-customer-7",500,"repayment")
        val restarted=CounterModel(storage)
        assertEquals(1500,restarted.creditBalance("shop-customer-7"))
        assertEquals(500,restarted.creditRepayments().single().amountPaise)
        assertEquals(Payment.SPLIT,restarted.allOrders().single().payment)
        assertEquals(listOf("credit_sale", "customer_repayment"), restarted.paymentHistory().map { it.kind })
    }

    @Test fun upiIsRecordedAsPendingPaymentEventAndNeverAsVerified() {
        val model = CounterModel()
        model.apply("create", model.parse("Naya order, one chai")!!)
        model.apply("ready", model.parse("Order 12 ready hai")!!)
        model.apply("given", model.parse("Order 12 de diya")!!)
        model.apply("upi-pending", model.parse("Order 12 UPI pending")!!)
        val entry = model.paymentHistory().single()
        assertEquals("upi", entry.method)
        assertEquals("pending", entry.status)
        assertEquals(1500, entry.amountPaise)
        assertEquals(Payment.UPI_PENDING, model.allOrders().single().payment)
    }

    @Test fun parserMatchesSharedCommandContractFixtures() {
        val stream = javaClass.classLoader!!.getResourceAsStream("voice-command-contract.tsv")
        assertNotNull("shared contract fixture is on the test classpath", stream)
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.trimStart('\uFEFF').startsWith("#") }.forEach { row ->
                val fields = row.split('\t', limit = 6)
                assertEquals("fixture columns: $row", 6, fields.size)
                val transcript = fields[0]
                val expectedType = fields[1]
                val orderText = fields[2]
                val productText = fields[3]
                val quantityText = fields[4]
                val amountText = fields[5]
                val command = CounterModel().parse(transcript)
                assertNotNull("command parsed: $transcript", command)
                assertEquals("type: $transcript", expectedType, command!!.type)
                assertEquals("order: $transcript", orderText.toIntOrNull(), command.orderNumber)
                assertEquals("product: $transcript", productText.ifBlank { null }, command.productId)
                assertEquals("quantity: $transcript", quantityText.toInt(), command.quantity)
                assertEquals("amount: $transcript", amountText.toIntOrNull(), command.amountPaise)
            }
        }
    }

    @Test fun nativeOutcomesMatchSharedTransactionBehaviorFixtures() {
        val stream = javaClass.classLoader!!.getResourceAsStream("transaction-parity.tsv")
        assertNotNull("shared business fixture is on the test classpath", stream)
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.trimStart('\uFEFF').startsWith("#") }.forEach { row ->
                val fields = row.split('\t')
                val scenario = fields[0]
                val items = fields[1]
                val totalText = fields[2]
                val stockText = fields[3]
                val creditText = fields[4]
                val stateText = fields[5]
                val quantity = items.substringBefore('_').toInt()
                val model = CounterModel(returnableProducts = setOf("cutting_chai"))
                val openingStock = model.stockLevels()["cutting_chai"]!!
                model.apply("$scenario-create", ProposedCommand("create", null, "fixture order", "cutting_chai", quantity))
                val order = model.allOrders().single()
                assertEquals("total for $scenario", totalText.toInt(), order.totalPaise)
                model.apply("$scenario-ready", ProposedCommand("ready", order.number, "ready"))
                if (scenario == "mixed_repayment_refund") {
                    model.apply("$scenario-given", ProposedCommand("given", order.number, "given"))
                    model.recordCash(order.number, 1200, "$scenario-cash")
                    model.recordUdhar(order.number, "ramesh", "$scenario-credit", 800)
                    model.repayCredit("ramesh", 300, "$scenario-repayment")
                    assertEquals(Payment.SPLIT, model.allOrders().single().payment)
                    assertEquals("stock after fulfillment", openingStock - quantity, model.stockLevels()["cutting_chai"])
                    val refundResult = model.refundOrder(order.number, "fixture reversal", "restock", true, "$scenario-refund")
                    assertTrue(refundResult, refundResult.contains("Refund"))
                    assertEquals(stateText, model.allOrders().single().payment.name)
                    assertEquals("credit balance", creditText.toInt(), model.creditBalance("ramesh"))
                    assertEquals("stock delta", stockText.toInt(), model.stockLevels()["cutting_chai"]!! - openingStock)
                    assertTrue(model.auditEvents().any { it.contains("sale.refund") })
                    assertTrue(model.paymentHistory().any { it.kind == "cash_refund" && it.amountPaise == -1500 })
                } else {
                    model.apply("$scenario-partial", ProposedCommand("partial_given", order.number, "partial", "cutting_chai", 1))
                    model.cancelOrder(order.number, "fixture remainder cancelled", "$scenario-cancel")
                    assertEquals(Fulfillment.CANCELLED, model.allOrders().single().fulfillment)
                    assertEquals(stockText.toInt(), model.stockLevels()["cutting_chai"]!! - openingStock)
                    assertEquals(1, model.stockMovements().sumOf { -it.quantityDelta })
                }
            }
        }
    }

    @Test fun staleOrderRevisionIsRejectedWithoutMutation() {
        val model = CounterModel()
        model.apply("rev-create", ProposedCommand("create", null, "create", "chai", 1))
        val order = model.allOrders().single()
        model.apply("rev-ready", ProposedCommand("ready", order.number, "ready", expectedRevision = order.revision))
        val current = model.allOrders().single()
        val before = current.lines.toList()
        val result = model.apply("rev-stale", ProposedCommand("add", current.number, "stale", "samosa", 1, expectedRevision = 0))
        assertTrue(result.contains("revision conflict"))
        assertEquals(before, model.allOrders().single().lines)
    }

    @Test fun partialRefundOutcomesMatchSharedFinancialAndStockFixtures() {
        val stream = javaClass.classLoader!!.getResourceAsStream("refund-parity.tsv")
        assertNotNull("shared refund fixture is on the test classpath", stream)
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.trimStart('\uFEFF').startsWith("#") }.forEach { row ->
                val fields = row.split('\t')
                val scenario = fields[0]
                val expectedTotal = fields[1].toInt()
                val expectedRefund = fields[2].toInt()
                val expectedCash = fields[3].toInt()
                val expectedCredit = fields[4].toInt()
                val expectedCustomerBalance = fields[5].toInt()
                val expectedRefunded = fields[6].toInt()
                val expectedPayment = Payment.valueOf(fields[7])
                val model = CounterModel(returnableProducts = setOf("samosa"))
                val opening = model.stockLevels()
                val order = if (scenario == "multi_item_partial_return") {
                    model.apply("$scenario-create", ProposedCommand("create", null, "cutting chai", "cutting_chai", 1))
                    val created = model.allOrders().single()
                    model.apply("$scenario-add", ProposedCommand("add", created.number, "samosa", "samosa", 1, expectedRevision = created.revision))
                    created.number
                } else {
                    model.apply("$scenario-create", ProposedCommand("create", null, "two chai", "cutting_chai", 2))
                    model.allOrders().single().number
                }
                val current = { model.allOrders().single { it.number == order } }
                model.apply("$scenario-ready", ProposedCommand("ready", order, "ready", expectedRevision = current().revision))
                model.apply("$scenario-given", ProposedCommand("given", order, "given", expectedRevision = current().revision))
                assertEquals("total $scenario", expectedTotal, current().salePaise)
                if (scenario == "multi_item_partial_return") {
                    model.recordCash(order, 1000, "$scenario-cash", current().revision)
                    model.recordUdhar(order, "ramesh", "$scenario-credit", 2000, current().revision)
                    model.repayCredit("ramesh", 500, "$scenario-repayment")
                    val result = model.refundItems(order, mapOf("samosa" to 1), "returned samosa", mapOf("samosa" to "restock"), true, "$scenario-refund", current().revision)
                    assertTrue(result, result.contains("Refund"))
                    assertEquals(expectedCash, current().paidPaise)
                    assertEquals(expectedCredit, current().creditPaise)
                    assertEquals(expectedCustomerBalance, model.creditBalance("ramesh"))
                    assertEquals(expectedRefund, current().refundedPaise)
                    assertEquals(fields[9].toInt(), model.stockLevels()["samosa"]!! - opening["samosa"]!!)
                    assertEquals(fields[8].toInt(), model.stockLevels()["cutting_chai"]!! - opening["cutting_chai"]!!)
                    assertEquals(-500, model.paymentHistory().single { it.kind == "cash_refund" }.amountPaise)
                    assertEquals(-1500, model.paymentHistory().single { it.kind == "credit_reversal" }.amountPaise)
                    assertEquals(1500, model.customerCredits().sumOf { it.reversedPaise })
                    assertEquals(Payment.STAFF_DECLARED, current().payment)
                    assertTrue(model.auditEvents().any { it.contains("sale.refund") })
                } else {
                    model.recordUpiPending(order, "$scenario-upi", current().revision)
                    val result = model.refundOrder(order, "cancel pending UPI sale", "not_restocked", false, "$scenario-refund", current().revision)
                    assertTrue(result, result.contains("Refund"))
                    assertEquals(expectedRefunded, current().refundedPaise)
                    assertEquals(expectedPayment, current().payment)
                    assertEquals(0, current().paidPaise)
                    assertEquals(0, current().creditPaise)
                    assertEquals(-2, model.stockLevels()["cutting_chai"]!! - opening["cutting_chai"]!!)
                    assertEquals("pending", model.paymentHistory().first { it.method == "upi" }.status)
                    assertTrue(model.paymentHistory().any { it.status == "cancelled" && it.kind == "payment_attempt_cancelled" })
                    val cancelledAttempt = model.paymentHistory().single { it.kind == "payment_attempt_cancelled" }
                    assertEquals(model.paymentHistory().single { it.method == "upi" && it.status == "pending" }.entryId, cancelledAttempt.relatedEntryId)
                    assertFalse(model.paymentHistory().any { it.kind == "cash_refund" || it.kind == "credit_reversal" })
                }
            }
        }
    }

    @Test fun amountOnlyRefundsAreBoundedConfirmedAndDoNotRestock() {
        val model = CounterModel()
        model.apply("amount-create", ProposedCommand("create", null, "one cutting chai", "cutting_chai", 1))
        val order = model.allOrders().single()
        model.apply("amount-ready", ProposedCommand("ready", order.number, "ready", expectedRevision = order.revision))
        model.apply("amount-given", ProposedCommand("given", order.number, "given", expectedRevision = model.allOrders().single().revision))
        model.recordCash(order.number, 1000, "amount-cash", model.allOrders().single().revision)
        val beforeStock = model.stockLevels()["cutting_chai"]
        assertTrue(model.refundAmount(order.number, 300, "quality adjustment", false, "amount-refund-unconfirmed").contains("Confirm"))
        assertTrue(model.refundAmount(order.number, 300, "quality adjustment", true, "amount-refund-1", model.allOrders().single().revision).contains("Refund"))
        assertEquals(700, model.allOrders().single().paidPaise)
        assertEquals(300, model.allOrders().single().refundedPaise)
        assertTrue(model.refundAmount(order.number, 701, "over refund", true, "amount-refund-too-large", model.allOrders().single().revision).contains("exceeds"))
        assertTrue(model.refundAmount(order.number, 700, "remaining adjustment", true, "amount-refund-2", model.allOrders().single().revision).contains("Refund"))
        assertTrue(model.allOrders().single().voided)
        assertEquals(0, model.allOrders().single().paidPaise)
        assertEquals(1000, model.allOrders().single().refundedPaise)
        assertEquals(beforeStock, model.stockLevels()["cutting_chai"])
        assertEquals(2, model.paymentHistory().count { it.kind == "cash_refund" })
    }

    @Test fun refundPersistenceFailureRollsBackMoneyCreditAndAudit() {
        val storage = MemoryPersistence()
        val model = CounterModel(storage)
        model.apply("refund-rollback-create", ProposedCommand("create", null, "one chai", "cutting_chai", 1))
        val number = model.allOrders().single().number
        model.apply("refund-rollback-ready", ProposedCommand("ready", number, "ready", expectedRevision = model.allOrders().single().revision))
        model.apply("refund-rollback-given", ProposedCommand("given", number, "given", expectedRevision = model.allOrders().single().revision))
        model.recordCash(number, 1000, "refund-rollback-cash", model.allOrders().single().revision)
        val beforeOrder = model.allOrders().single().copy(lines = model.allOrders().single().lines.toMutableList())
        val beforePayments = model.paymentHistory()
        val beforeAudit = model.auditEvents()
        storage.failNext = true
        assertTrue(model.refundAmount(number, 300, "failed refund", true, "refund-rollback-command").startsWith("Could not save"))
        assertEquals(beforeOrder, model.allOrders().single())
        assertEquals(beforePayments, model.paymentHistory())
        assertEquals(beforeAudit, model.auditEvents())
        assertTrue(model.refundAmount(number, 300, "retry refund", true, "refund-rollback-command").contains("Refund"))
        assertEquals(300, model.allOrders().single().refundedPaise)
    }

    @Test fun backupExportPolicyRemovesPartialDocumentAndPreservesWriteFailure() {
        var deleteCalls = 0
        val failure = try {
            BackupExportPolicy.write<String>(
                writeDocument = { throw java.io.IOException("synthetic destination write failure") },
                deletePartialDocument = { deleteCalls++; true }
            )
            null
        } catch (error: BackupExportException) { error }
        assertNotNull(failure)
        assertTrue(failure!!.partialDocumentRemoved)
        assertEquals("synthetic destination write failure", failure.cause?.message)
        assertEquals(1, deleteCalls)

        val cleanupFailure = try {
            BackupExportPolicy.write<String>(
                writeDocument = { throw java.io.IOException("second synthetic write failure") },
                deletePartialDocument = { throw SecurityException("provider refused deletion") }
            )
            null
        } catch (error: BackupExportException) { error }
        assertNotNull(cleanupFailure)
        assertFalse(cleanupFailure!!.partialDocumentRemoved)
        assertEquals("second synthetic write failure", cleanupFailure.cause?.message)

        var deletedAfterSuccess = false
        assertEquals("complete", BackupExportPolicy.write(
            writeDocument = { "complete" },
            deletePartialDocument = { deletedAfterSuccess = true; true }
        ))
        assertFalse(deletedAfterSuccess)
    }
}

