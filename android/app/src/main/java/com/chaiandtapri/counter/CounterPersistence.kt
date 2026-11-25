package com.chaiandtapri.counter

data class CounterOrderRecord(
    val number: Int,
    val lines: List<CounterLine>,
    val fulfillment: Fulfillment,
    val payment: Payment,
    val paidPaise: Int,
    val salePosted: Boolean,
    val cancelReason: String?,
    val revision: Int,
    val creditPaise: Int = 0,
    val refundedPaise: Int = 0,
    val voided: Boolean = false,
    val cashRefundedPaise: Int = 0,
    val creditReversedPaise: Int = 0,
    /** Epoch milliseconds when this order was first created; 0 denotes a legacy record with unknown date. */
    val createdAtEpochMs: Long = 0L
)

data class StockMovement(
    val movementId: String,
    val productId: String,
    val quantityDelta: Int,
    val reason: String,
    val orderNumber: Int?
)

data class CustomerCredit(
    val creditId: String,
    val customerId: String,
    val orderNumber: Int,
    val amountPaise: Int,
    val repaidPaise: Int,
    val reversedPaise: Int = 0,
    /** Random cross-device record identity; local command IDs never leave encrypted storage. */
    val syncId: String = java.util.UUID.randomUUID().toString()
) {
    val outstandingPaise: Int get() = amountPaise - repaidPaise - reversedPaise
}

/** Optional local CRM identity. Contact use requires recorded customer consent. */
data class CustomerProfile(
    val customerId: String,
    val displayName: String,
    val phone: String? = null,
    val contactConsent: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class CreditRepayment(
    val repaymentId: String,
    val customerId: String,
    val amountPaise: Int,
    val syncId: String = java.util.UUID.randomUUID().toString()
)

data class PaymentEntry(
    val entryId: String,
    val orderNumber: Int?,
    val customerId: String?,
    val amountPaise: Int,
    val method: String,
    val status: String,
    val kind: String,
    val relatedEntryId: String? = null,
    val drawerSessionId: String? = null,
    /** Local persistence time; zero denotes a legacy or unavailable timestamp. */
    val createdAtEpochMs: Long = 0L,
    /** Random cross-device event identity; the local command/ledger ID stays encrypted on-device. */
    val syncId: String = java.util.UUID.randomUUID().toString()
)

data class SupplierRecord(
    val supplierId: String,
    val name: String,
    val phone: String? = null,
    /** Random opaque cross-device key; the user-entered local account ID is never sent to sync. */
    val syncId: String = java.util.UUID.randomUUID().toString()
)
data class PurchaseReceiptLine(val productId: String, val productName: String, val quantity: Int, val unitCostPaise: Int) {
    val lineCostPaise: Long get() = quantity.toLong() * unitCostPaise
}
data class PurchaseReceipt(
    val receiptId: String,
    val supplierId: String,
    val lines: List<PurchaseReceiptLine>,
    val totalPaise: Long = lines.sumOf { it.lineCostPaise },
    val purchaseOrderId: String? = null,
    val supplierInvoiceReference: String? = null,
    val syncId: String = java.util.UUID.randomUUID().toString()
)
enum class PurchaseOrderStatus { DRAFT, SENT, CANCELLED }
data class PurchaseOrderDraftLine(val productId: String, val productName: String, val orderedQuantity: Int, val unitCostPaise: Int) {
    val lineCostPaise: Long get() = orderedQuantity.toLong() * unitCostPaise
}
data class PurchaseOrderDraft(
    val purchaseOrderId: String,
    val supplierId: String,
    val lines: List<PurchaseOrderDraftLine>,
    val status: PurchaseOrderStatus,
    val revision: Int = 0,
    val createdAtEpochMs: Long = 0L
) {
    val totalPaise: Long get() = lines.sumOf { it.lineCostPaise }
}
data class SupplierPayment(
    val paymentId: String,
    val supplierId: String,
    val amountPaise: Int,
    val method: String,
    val status: String,
    val drawerSessionId: String? = null,
    val syncId: String = java.util.UUID.randomUUID().toString()
)
data class PurchaseReturnLine(val productId: String, val productName: String, val quantity: Int, val unitCostPaise: Int) {
    val lineValuePaise: Long get() = quantity.toLong() * unitCostPaise
}
data class PurchaseReturn(
    val returnId: String,
    val supplierId: String,
    val receiptId: String,
    val lines: List<PurchaseReturnLine>,
    val totalPaise: Long = lines.sumOf { it.lineValuePaise },
    val syncId: String = java.util.UUID.randomUUID().toString()
)
data class BusinessExpense(
    val expenseId: String,
    val category: String,
    val amountPaise: Int,
    val method: String,
    val status: String,
    val note: String,
    val drawerSessionId: String? = null,
    /** Device-clock time when recorded; null means the original date is unavailable. */
    val recordedAtEpochMs: Long? = null
)
data class CashDrawerSession(val sessionId: String, val openingCashPaise: Int, val openedAt: Long, val closingCashPaise: Int? = null, val closedAt: Long? = null, val staffLabel: String? = null, val handoverToLabel: String? = null, val handoverNote: String = "", val staffActorId: String? = null)
data class CashDrawerMovement(val movementId: String, val sessionId: String, val kind: String, val amountPaise: Int, val note: String)
data class CashClosingReview(val reviewId: String, val sessionId: String, val reviewedAt: Long, val note: String)
data class AuditRecord(val eventId: String, val commandId: String, val event: String, val createdAtEpochMs: Long, val actorId: String? = null)

data class ShopProfile(
    val shopName: String = "",
    val businessType: String = "chai_tapri",
    val languageTag: String = "hi-IN",
    val currencyCode: String = "INR"
)

data class LocalOwnerCredential(
    val username: String,
    val displayName: String,
    val salt: ByteArray,
    val verifier: ByteArray,
    val iterations: Int,
    val failedAttempts: Int,
    val lockedUntilMs: Long,
    val enabled: Boolean
)

enum class StaffRole { CASHIER, MANAGER }
data class StaffAccount(val staffId: String, val displayName: String, val role: StaffRole, val enabled: Boolean, val createdAtEpochMs: Long)
data class StaffCredential(
    val account: StaffAccount,
    val salt: ByteArray,
    val verifier: ByteArray,
    val iterations: Int,
    val failedAttempts: Int,
    val lockedUntilMs: Long
)

data class TapriProduct(
    val productId: String,
    val name: String,
    val category: String,
    val unitPricePaise: Int,
    val active: Boolean = true,
    val favorite: Boolean = false,
    val priceConfirmed: Boolean = false,
    val aliases: List<String> = emptyList(),
    val reorderThreshold: Int? = null,
    /** Owner-entered estimate in paise; not a verified purchase cost or accounting valuation. */
    val estimatedUnitCostPaise: Int? = null,
    /** Exact product identifier entered by an optional keyboard-wedge barcode/QR scanner. */
    val scanCode: String? = null,
    /** Configured base unit used for sale, count, and stock quantities; no pack conversion is implied. */
    val unitLabel: String = "units"
)

data class CounterSnapshot(
    val nextNumber: Int,
    val orders: List<CounterOrderRecord>,
    val stock: Map<String, Int>,
    val commandIds: Set<String>,
    val auditEvents: List<String>,
    val stockMovements: List<StockMovement>,
    val customerCredits: List<CustomerCredit>,
    val creditRepayments: List<CreditRepayment>,
    val paymentEntries: List<PaymentEntry> = emptyList(),
    val stockConfirmedProductIds: Set<String> = emptySet(),
    val suppliers: List<SupplierRecord> = emptyList(),
    val purchaseReceipts: List<PurchaseReceipt> = emptyList(),
    val supplierPayments: List<SupplierPayment> = emptyList(),
    val purchaseReturns: List<PurchaseReturn> = emptyList(),
    val expenses: List<BusinessExpense> = emptyList(),
    val cashDrawerSessions: List<CashDrawerSession> = emptyList(),
    val cashDrawerMovements: List<CashDrawerMovement> = emptyList(),
    val cashClosingReviews: List<CashClosingReview> = emptyList(),
    val purchaseOrderDrafts: List<PurchaseOrderDraft> = emptyList(),
    /** Encrypted local-key to opaque UUID mapping used for privacy-minimized cloud ledger sync. */
    val customerSyncIdentities: Map<String, String> = emptyMap(),
    /** Local catalogue metadata projected only alongside allow-listed product ledger deltas. */
    val productUnitLabels: Map<String, String> = emptyMap()
)

/** Commits one validated command and its complete resulting state atomically. */
interface CounterPersistence : AutoCloseable {
    fun load(): CounterSnapshot?
    /** Ordered immutable audit records with their locally recorded timestamps. */
    fun loadAuditRecords(): List<AuditRecord> = emptyList()
    /** Locally pending, contract-approved events only; blocked historical rows never leave the device. */
    fun loadPendingSyncEvents(limit: Int = 1): List<SyncOutboxEvent> = emptyList()
    fun loadSyncCursor(): Long = 0L
    /** Atomically persist one accepted/duplicate acknowledgement or a conflict without rewriting business history. */
    fun recordSyncOutcome(eventId: String, outcome: String, serverRevision: Long): Boolean = false
    fun loadShopProfile(): ShopProfile? = null
    fun saveShopProfile(profile: ShopProfile, commandId: String): Boolean = false
    fun loadVoiceLanguageTag(): String? = null
    fun saveVoiceLanguageTag(languageTag: String, commandId: String): Boolean = false
    fun loadLocalOwnerCredential(username: String): LocalOwnerCredential? = null
    fun localOwnerExists(): Boolean = false
    fun createLocalOwnerCredential(credential: LocalOwnerCredential, commandId: String): Boolean = false
    fun recordLocalOwnerLogin(username: String, succeeded: Boolean, commandId: String, nowMs: Long): Boolean = false
    fun upgradeLocalOwnerVerifier(username: String, salt: ByteArray, verifier: ByteArray, iterations: Int, commandId: String): Boolean = false
    fun listStaffAccounts(): List<StaffAccount> = emptyList()
    fun loadStaffCredential(staffId: String): StaffCredential? = null
    fun createStaffCredential(credential: StaffCredential, commandId: String): Boolean = false
    fun setStaffAccountEnabled(staffId: String, enabled: Boolean, commandId: String): Boolean = false
    fun recordStaffLogin(staffId: String, succeeded: Boolean, commandId: String, nowMs: Long): Boolean = false
    fun hasStaffAccounts(): Boolean = listStaffAccounts().isNotEmpty()
    fun loadProducts(): List<TapriProduct> = emptyList()
    fun saveProduct(product: TapriProduct, commandId: String): Boolean = false
    fun loadCustomerProfiles(): List<CustomerProfile> = emptyList()
    fun saveCustomerProfile(profile: CustomerProfile, commandId: String): Boolean = false
    fun updateCustomerProfile(profile: CustomerProfile, commandId: String): Boolean = false
    /** Returns false if this command ID has already been committed. */
    fun commit(snapshot: CounterSnapshot, commandId: String, result: String, event: String, actorId: String?, movements: List<StockMovement>, repayments: List<CreditRepayment>, paymentEntries: List<PaymentEntry>, syncPayload: SyncOutboxPayload): Boolean
    override fun close() = Unit
}
