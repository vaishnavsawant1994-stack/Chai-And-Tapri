package com.chaiandtapri.counter

import java.util.Locale

data class CounterLine(val productId: String,val name: String,val quantity: Int,val unitPricePaise: Int,val deliveredQuantity: Int = 0,val refundedQuantity: Int = 0,
    /** Immutable per-line estimate captured at sale entry; null means cost is unknown. */
    val estimatedUnitCostPaise: Int? = null) {
    val amountPaise: Int get() = quantity * unitPricePaise
    val estimatedCostPaise: Long? get() = estimatedUnitCostPaise?.let { quantity.toLong() * it }
}
data class CounterOrder(
    val number: Int,
    val lines: MutableList<CounterLine>,
    var fulfillment: Fulfillment = Fulfillment.TAKEN,
    var payment: Payment = Payment.UNPAID,
    var paidPaise: Int = 0,
    var creditPaise: Int = 0,
    var refundedPaise: Int = 0,
    var salePosted: Boolean = false,
    var voided: Boolean = false,
    var cancelReason: String? = null,
    var revision: Int = 0,
    var cashRefundedPaise: Int = 0,
    var creditReversedPaise: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val items: String get() = lines.joinToString(" + ") { "${it.quantity} × ${it.name}" }
    val totalPaise: Int get() = lines.sumOf { it.amountPaise }
    val salePaise: Int get() = lines.sumOf { it.deliveredQuantity * it.unitPricePaise }
    val duePaise: Int get() = if (voided) 0 else (salePaise - refundedPaise - paidPaise - creditPaise).coerceAtLeast(0)
}
enum class Fulfillment { TAKEN, PREPARING, READY, PARTIALLY_GIVEN, GIVEN, CANCELLED }
enum class Payment { UNPAID, PARTIALLY_PAID, STAFF_DECLARED, UPI_PENDING, CREDIT, SPLIT, REFUNDED, CANCELLED }
data class ProposedCommand(val type: String,val orderNumber: Int?,val description: String,val productId: String?=null,val quantity: Int=1,val amountPaise: Int?=null,val customerId: String?=null,val expectedRevision: Int?=null,val auditReason: String?=null)
data class LocalBusinessSummary(
    val recordedSalesPaise: Long,
    val cashRecordedPaise: Long,
    val upiPendingPaise: Long,
    val customerReceivablePaise: Long,
    val openOrderCount: Int,
    val productCount: Int,
    val stockConfirmedProductCount: Int
)
data class UnitemizedSalesEstimate(val commandId: String, val amountPaise: Int, val reason: String, val recordedAtEpochMs: Long)

/** Native transaction prototype. A configured store persists commands atomically; this remains a subset of CAT-047. */
class CounterModel(private val persistence: CounterPersistence? = null, private val returnableProducts: Set<String> = emptySet(), private val requireConfiguredProducts: Boolean = false) : AutoCloseable {
    private val orders = linkedMapOf<Int,CounterOrder>()
    private val stock = mutableMapOf("cutting_chai" to 100,"chai" to 100,"samosa" to 100,"coffee" to 100)
    private val stockConfirmedProductIds = mutableSetOf<String>()
    private val appliedCommandIds = mutableSetOf<String>()
    private val events = mutableListOf<String>()
    private val movements = mutableListOf<StockMovement>()
    private val credits = mutableListOf<CustomerCredit>()
    private val repayments = mutableListOf<CreditRepayment>()
    private val paymentEntries = mutableListOf<PaymentEntry>()
    private val customerSyncIdentities = mutableMapOf<String, String>()
    private val supplierRecords = mutableListOf<SupplierRecord>()
    private val purchaseReceipts = mutableListOf<PurchaseReceipt>()
    private val purchaseOrderRecords = mutableListOf<PurchaseOrderDraft>()
    private val supplierPayments = mutableListOf<SupplierPayment>()
    private val purchaseReturns = mutableListOf<PurchaseReturn>()
    private val expenseRecords = mutableListOf<BusinessExpense>()
    private val cashDrawerSessions = mutableListOf<CashDrawerSession>()
    private val cashDrawerMovements = mutableListOf<CashDrawerMovement>()
    private val cashClosingReviews = mutableListOf<CashClosingReview>()
    private var nextNumber = 12
    init { persistence?.load()?.let { CounterSnapshotIntegrity.requireConsistent(it); restore(it) } }
    fun allOrders(): List<CounterOrder> = orders.values.toList().reversed()
    /** Current local-ledger totals only; no date attribution, expenses, costs, or verified-UPI claim. */
    fun localBusinessSummary(): LocalBusinessSummary {
        val activeOrders = orders.values.filterNot { it.voided }
        val recordedSales = activeOrders.sumOf { (it.salePaise - it.refundedPaise).coerceAtLeast(0).toLong() }
        val cashRecorded = paymentEntries.filter { it.method == "cash" && it.status.startsWith("staff_declared") }
            .sumOf { it.amountPaise.toLong() }.coerceAtLeast(0)
        val pendingUpi = activeOrders.filter { it.payment == Payment.UPI_PENDING }.sumOf { it.duePaise.toLong() }
        val receivable = credits.sumOf { it.outstandingPaise.toLong() }.coerceAtLeast(0)
        val openOrders = orders.values.count { !it.voided && it.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) }
        val products = persistence?.loadProducts().orEmpty()
        val sellableProducts = products.filter { it.active && it.priceConfirmed }
        return LocalBusinessSummary(recordedSales, cashRecorded, pendingUpi, receivable, openOrders,
            sellableProducts.size, sellableProducts.count { it.productId in stockConfirmedProductIds })
    }
    fun shopProfile(): ShopProfile = persistence?.loadShopProfile() ?: ShopProfile()
    fun voiceLanguageTag(): String = persistence?.loadVoiceLanguageTag()?.takeIf { it in setOf("hi-IN", "mr-IN", "en-IN") }
        ?: shopProfile().languageTag
    fun saveVoiceLanguageTag(languageTag: String): String {
        if (languageTag !in setOf("hi-IN", "mr-IN", "en-IN")) return "Choose a supported speech language."
        return try {
            if (persistence?.saveVoiceLanguageTag(languageTag, "voice-language-${java.util.UUID.randomUUID()}") == true)
                "Voice language saved in encrypted local storage."
            else "Voice language could not be saved."
        } catch (error: Exception) { "Voice language was not saved (${error.javaClass.simpleName})." }
    }
    fun products(): List<TapriProduct> = persistence?.loadProducts().orEmpty()
    /** Returns low-stock products only when the physical stock baseline is confirmed. */
    fun lowStockProducts(): List<Pair<TapriProduct, Int>> = persistence?.loadProducts().orEmpty()
        .filter { it.active && it.reorderThreshold != null && it.productId in stockConfirmedProductIds }
        .mapNotNull { product -> stock[product.productId]?.let { product to it } }
        .filter { (product, quantity) -> quantity <= (product.reorderThreshold ?: Int.MAX_VALUE) }
        .sortedBy { it.second }
    fun saveProduct(product: TapriProduct): String {
        barrierRejectionIfActive()?.let { return it }
        if (!product.productId.matches(Regex("[a-z][a-z0-9_]{1,39}"))) return "Product ID must use 2–40 lowercase letters, numbers or underscores."
        if (product.name.trim().length !in 2..60 || product.category.trim().length !in 2..40) return "Enter a product name and category."
        if (product.unitLabel.trim().length !in 1..24 || product.unitLabel.any(Char::isISOControl)) return "Enter a stock/selling unit label of 1 to 24 printable characters."
        val catalogue = configuredProductsOrNull() ?: return maintenanceRejection()
        val existingProduct = catalogue.firstOrNull { it.productId == product.productId }
        val normalizedUnit = product.unitLabel.trim()
        if (existingProduct != null && existingProduct.unitLabel != normalizedUnit &&
            (allOrders().any { order -> order.lines.any { it.productId == product.productId } } ||
                stockMovements().any { it.productId == product.productId })) {
            return "This product's unit cannot change after an order or stock movement exists. Create a new product ID; unit conversion is not supported."
        }
        if (product.unitPricePaise !in 0..10_000_000) return "Enter a valid price in rupees."
        if (product.active && product.priceConfirmed && product.unitPricePaise <= 0) return "A sellable product needs a price above ₹0."
        if (product.estimatedUnitCostPaise != null && product.estimatedUnitCostPaise !in 0..10_000_000) return "Estimated unit cost must be 0 to ₹100000, or blank."
        if (product.aliases.any { it.trim().length > 40 }) return "Product aliases must be 40 characters or less."
        if (product.reorderThreshold != null && product.reorderThreshold !in 1..100_000) return "Low-stock threshold must be 1 to 100000 units, or blank."
        val scanCode = product.scanCode?.trim()?.takeIf(String::isNotEmpty)
        if (scanCode != null && (scanCode.length > 128 || scanCode.any(Char::isISOControl))) return "Scan code must be 1 to 128 printable characters, or blank."
        if (scanCode != null && catalogue.any { it.productId != product.productId && it.scanCode?.equals(scanCode, ignoreCase = true) == true }) {
            return "That scan code is already assigned to another product."
        }
        return try {
            if (persistence?.saveProduct(product.copy(name = product.name.trim(), category = product.category.trim(), scanCode = scanCode, unitLabel = normalizedUnit), "product-${java.util.UUID.randomUUID()}") == true) {
                stock.putIfAbsent(product.productId, 0)
                "Product saved in encrypted catalogue."
            } else "Product could not be saved."
        } catch (error: Exception) { "Product could not be saved. No change made. (${error.javaClass.simpleName})" }
    }

    /** One atomic counter sale: canonical catalogue price, stock, order and tender share one commit. */
    fun quickSale(items: List<Pair<String, Int>>, tender: String, customerId: String? = null, commandId: String = java.util.UUID.randomUUID().toString()): String {
        barrierRejectionIfActive()?.let { return it }
        if (commandId.isBlank()) return "Command ID is required."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (items.isEmpty()) return "Add at least one product. No sale recorded."
        if (tender !in setOf("cash", "upi_pending", "udhar")) return "Choose cash, UPI pending, or Udhar."
        val normalized = items.groupBy { it.first }.map { (id, entries) -> id to entries.sumOf { it.second } }
        if (normalized.any { it.second !in 1..99 }) return "Quantity must be between 1 and 99. No sale recorded."
        val lines = normalized.map { (id, quantity) -> line(id, quantity) ?: return "Product price is unconfirmed or product is unavailable. No sale recorded." }
        if (requireConfiguredProducts && lines.any { it.productId !in stockConfirmedProductIds }) return "Opening stock has not been physically confirmed for every item. No sale recorded."
        if (lines.sumOf { it.unitPricePaise.toLong() * it.quantity } > Int.MAX_VALUE) return "Sale total exceeds the supported amount. No sale recorded."
        if (lines.any { (stock[it.productId] ?: 0) < it.quantity }) return "Insufficient stock. No sale or payment recorded."
        val resolvedCustomer = customerId?.trim()?.takeIf(String::isNotEmpty)
        if (tender == "udhar" && resolvedCustomer == null) return "Select a customer account before recording Udhar. No sale recorded."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()

        val deliveredLines = lines.map { it.copy(deliveredQuantity = it.quantity) }
        val order = CounterOrder(nextNumber++, deliveredLines.toMutableList(), Fulfillment.GIVEN, salePosted = true)
        val total = order.salePaise
        order.payment = when (tender) {
            "cash" -> { order.paidPaise = total; Payment.STAFF_DECLARED }
            "upi_pending" -> Payment.UPI_PENDING
            else -> { order.creditPaise = total; Payment.CREDIT }
        }
        orders[order.number] = order
        lines.forEach { item ->
            stock[item.productId] = (stock[item.productId] ?: 0) - item.quantity
            movements += StockMovement("$commandId:${item.productId}", item.productId, -item.quantity, "quick_sale", order.number)
        }
        when (tender) {
            "cash" -> paymentEntries += PaymentEntry(commandId, order.number, null, total, "cash", "staff_declared", "order_collection", drawerSessionId = activeCashDrawer()?.sessionId)
            "upi_pending" -> paymentEntries += PaymentEntry(commandId, order.number, null, total, "upi", "pending", "payment_attempt")
            else -> {
                credits += CustomerCredit("$commandId-credit", resolvedCustomer!!, order.number, total, 0)
                paymentEntries += PaymentEntry(commandId, order.number, resolvedCustomer, total, "udhar", "receivable", "credit_sale")
            }
        }
        return commitMutation(before, commandId, "Quick sale · order #${order.number} · ${money(total)} · ${tender.replace('_', ' ')}", "quick_sale #${order.number} tender=$tender total=${total}p", order)
    }
    /** One atomic immediate sale split between declared cash and a customer receivable. */
    fun quickSaleSplit(items: List<Pair<String, Int>>, cashPaise: Int, customerId: String, commandId: String = java.util.UUID.randomUUID().toString()): String {
        barrierRejectionIfActive()?.let { return it }
        if (commandId.isBlank()) return "Command ID is required."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (items.isEmpty()) return "Add at least one product. No sale recorded."
        val normalized = items.groupBy { it.first }.map { (id, entries) -> id to entries.sumOf { it.second } }
        if (normalized.any { it.second !in 1..99 }) return "Quantity must be between 1 and 99. No sale recorded."
        val lines = normalized.map { (id, quantity) -> line(id, quantity) ?: return "Product price is unconfirmed or product is unavailable. No sale recorded." }
        if (requireConfiguredProducts && lines.any { it.productId !in stockConfirmedProductIds }) return "Opening stock has not been physically confirmed for every item. No sale recorded."
        val totalLong = lines.sumOf { it.unitPricePaise.toLong() * it.quantity }
        if (totalLong > Int.MAX_VALUE) return "Sale total exceeds the supported amount. No sale recorded."
        val total = totalLong.toInt()
        if (cashPaise <= 0 || cashPaise >= total) return "Cash must be positive and less than the sale total for a cash-plus-Udhar split. No sale recorded."
        if (lines.any { (stock[it.productId] ?: 0) < it.quantity }) return "Insufficient stock. No sale, payment, or credit recorded."
        val resolvedCustomer = customerId.trim().takeIf(String::isNotEmpty)
            ?: return "Select a customer account before recording the Udhar balance. No sale recorded."

        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()

        val creditPaise = total - cashPaise
        val order = CounterOrder(nextNumber++, lines.map { it.copy(deliveredQuantity = it.quantity) }.toMutableList(), Fulfillment.GIVEN, salePosted = true)
        order.paidPaise = cashPaise
        order.creditPaise = creditPaise
        updatePaymentState(order)
        orders[order.number] = order
        lines.forEach { item ->
            stock[item.productId] = (stock[item.productId] ?: 0) - item.quantity
            movements += StockMovement("$commandId:${item.productId}", item.productId, -item.quantity, "quick_sale", order.number)
        }
        paymentEntries += PaymentEntry("$commandId:cash", order.number, null, cashPaise, "cash", "staff_declared", "order_collection", drawerSessionId = activeCashDrawer()?.sessionId)
        credits += CustomerCredit("$commandId-credit", resolvedCustomer, order.number, creditPaise, 0)
        paymentEntries += PaymentEntry("$commandId:udhar", order.number, resolvedCustomer, creditPaise, "udhar", "receivable", "credit_sale")
        return commitMutation(before, commandId, "Quick sale · order #${order.number} · cash ${money(cashPaise)} · Udhar ${money(creditPaise)}", "quick_sale #${order.number} tender=split_cash_udhar total=${total}p cash=${cashPaise}p credit=${creditPaise}p", order)
    }

    fun saveShopProfile(profile: ShopProfile): String {
        if (profile.shopName.trim().length !in 2..80) return "Enter a shop name between 2 and 80 characters."
        if (profile.businessType !in setOf("chai_tapri", "paan_tapri", "tea_snacks", "cafe", "convenience")) return "Choose a supported business type."
        if (profile.languageTag !in setOf("hi-IN", "mr-IN", "en-IN")) return "Choose a supported language."
        if (profile.currencyCode != "INR") return "This prototype currently supports INR only."
        return try {
            val commandId = "shop-profile-${java.util.UUID.randomUUID()}"
            if (persistence?.saveShopProfile(profile.copy(shopName = profile.shopName.trim()), commandId) == true) "Shop setup saved in encrypted local storage."
            else "Shop setup could not be saved to persistent storage."
        } catch (error: Exception) {
            "Shop setup could not be saved. No change made. (${error.javaClass.simpleName})"
        }
    }
    fun stockLevels(): Map<String,Int> = stock.toMap()
    fun isStockConfirmed(productId: String): Boolean = !requireConfiguredProducts || productId in stockConfirmedProductIds
    fun confirmOpeningStock(productId: String, quantity: Int, reason: String, commandId: String = "opening-stock-${java.util.UUID.randomUUID()}"): String {
        barrierRejectionIfActive()?.let { return it }
        if (persistence == null) return "Opening stock requires durable shop storage."
        if (commandId.isBlank()) return "A stock-count command ID is required. No change made."
        if (quantity !in 0..100_000) return "Count must be between 0 and 100,000 units. No change made."
        if (reason.trim().length !in 4..120) return "Enter a short reason for this opening count. No change made."
        val product = (configuredProductsOrNull() ?: return maintenanceRejection()).firstOrNull { it.productId == productId && it.active }
            ?: return "Active product not found. No change made."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        val old = stock[productId] ?: 0
        val delta = quantity - old
        stock[productId] = quantity
        stockConfirmedProductIds += productId
        if (delta != 0) movements += StockMovement("$commandId:$productId:opening-count", productId, delta, "opening_count:${reason.trim()}", null)
        return commitMutation(before, commandId, "Opening stock confirmed for ${product.name}: $quantity ${product.unitLabel}.", "stock.opening_count product_id=$productId counted=$quantity unit=${product.unitLabel} delta=$delta reason=${reason.trim()}")
    }
    /** Commits one multi-product physical count atomically; no purchase or payable is created. */
    fun confirmPhysicalStockCounts(counts: Map<String, Int>, reason: String, commandId: String): String {
        barrierRejectionIfActive()?.let { return it }
        val storage = persistence ?: return "Physical stock counts require encrypted local storage."
        if (commandId.isBlank()) return "A stock-count command ID is required. No change made."
        if (reason.trim().length !in 4..120) return "Enter a count reason between 4 and 120 characters. No change made."
        if (counts.isEmpty()) return "Select at least one product to count. No change made."
        if (counts.size > 200) return "A count session can include at most 200 products. No change made."
        if (counts.values.any { it !in 0..100_000 }) return "Each count must be between 0 and 100,000 units. No change made."
        val activeProducts = (configuredProductsOrNull() ?: return maintenanceRejection()).filter { it.active }.associateBy { it.productId }
        if (counts.keys.any { it !in activeProducts }) return "One or more selected products are missing or inactive. No change made."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        counts.toSortedMap().forEach { (productId, quantity) ->
            val old = stock[productId] ?: 0
            stock[productId] = quantity
            stockConfirmedProductIds += productId
            val delta = quantity - old
            if (delta != 0) movements += StockMovement(
                "$commandId:$productId:physical-count", productId, delta,
                "physical_count:${reason.trim()}", null
            )
        }
        val productIds = counts.keys.sorted().joinToString(",")
        val countedQuantities = counts.toSortedMap().entries.joinToString(",") { (productId, quantity) -> "$productId:$quantity" }
        return commitMutation(
            before, commandId, "Physical count saved for ${counts.size} products.",
            "stock.physical_count products=$productIds counted=$countedQuantities reason=${reason.trim()}"
        )
    }
    fun auditEvents(): List<String> = events.toList()
    fun auditRecords(): List<AuditRecord> = persistence?.loadAuditRecords().orEmpty()
    fun unitemizedSalesEstimates(): List<UnitemizedSalesEstimate> = auditRecords().mapNotNull { record ->
        val prefix = "busy_counter.estimate classification=estimated "
        val normalizedEvent = record.event.removePrefix("${record.commandId} ")
        if (!normalizedEvent.startsWith(prefix)) return@mapNotNull null
        val fields = normalizedEvent.removePrefix(prefix)
        val amount = fields.substringAfter("amount=", "").substringBefore(' ').removeSuffix("p").toIntOrNull()
            ?: return@mapNotNull null
        val reason = fields.substringAfter("reason=", "").trim()
        if (amount <= 0 || reason.isBlank()) return@mapNotNull null
        UnitemizedSalesEstimate(record.commandId, amount, reason, record.createdAtEpochMs)
    }.asReversed()
    /** Stores an explicitly estimated, unitemized amount in the audit journal only. It is never a sale or collection. */
    fun recordUnitemizedSalesEstimate(amountPaise: Int, reason: String, commandId: String = java.util.UUID.randomUUID().toString()): String {
        if (persistence == null) return "Busy-counter estimates require encrypted local storage. No estimate saved."
        if (commandId.isBlank()) return "Estimate command ID is required. No estimate saved."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val note = reason.trim().replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s{2,}"), " ")
        if (amountPaise !in 1..100_000_000) return "Enter an estimate from ₹0.01 to ₹1,000,000. No estimate saved."
        if (note.length !in 4..120) return "Enter an estimate reason between 4 and 120 characters. No estimate saved."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        return commitMutation(before, commandId,
            "Unitemized amount saved as an estimate only: ${money(amountPaise)}. It is not recorded sales or a collection.",
            "busy_counter.estimate classification=estimated amount=${amountPaise}p reason=$note")
    }
    fun stockMovements(): List<StockMovement> = movements.toList()
    fun creditBalance(customerId: String): Int = credits.filter { it.customerId == customerId }.sumOf { it.outstandingPaise }
    fun customerCredits(): List<CustomerCredit> = credits.toList()
    fun customerProfiles(): List<CustomerProfile> = persistence?.loadCustomerProfiles().orEmpty()
    fun saveCustomerProfile(profile: CustomerProfile): String {
        if (persistence == null) return "Customer profiles require encrypted local storage."
        if (!profile.customerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{1,59}"))) return "Enter a customer account ID using at least 2 letters or numbers."
        if (profile.displayName.trim().length !in 2..80) return "Enter a customer name between 2 and 80 characters."
        val phone = profile.phone?.trim()?.takeIf(String::isNotEmpty)
        if (phone != null && (!profile.contactConsent || !phone.matches(Regex("\\+?[0-9]{8,15}")))) return "Enter a valid phone number and record the customer's contact consent."
        if (phone == null && profile.contactConsent) return "Contact consent can only be recorded when a phone number is stored."
        return try {
            val normalized = profile.copy(customerId = profile.customerId.trim(), displayName = profile.displayName.trim(), phone = phone)
            if (persistence.loadCustomerProfiles().any { it.customerId == normalized.customerId }) return "A customer profile already uses this account ID. No change made."
            if (persistence.saveCustomerProfile(normalized, "customer-profile-${java.util.UUID.randomUUID()}")) "Customer profile saved in encrypted local storage."
            else "Customer profile already exists or the request was already recorded. No change made."
        } catch (error: Exception) {
            "Customer profile could not be saved. No change made. (${error.javaClass.simpleName})"
        }
    }
    fun updateCustomerProfile(profile: CustomerProfile): String {
        if (persistence == null) return "Customer profiles require encrypted local storage."
        if (!profile.customerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{1,59}"))) return "Customer account ID is invalid. No change made."
        if (profile.displayName.trim().length !in 2..80) return "Enter a customer name between 2 and 80 characters. No change made."
        val phone = profile.phone?.trim()?.takeIf(String::isNotEmpty)
        if (phone != null && (!profile.contactConsent || !phone.matches(Regex("\\+?[0-9]{8,15}")))) return "Enter a valid phone number and record the customer's contact consent. No change made."
        if (phone == null && profile.contactConsent) return "Contact consent can only be recorded when a phone number is stored. No change made."
        return try {
            val normalized = profile.copy(customerId = profile.customerId.trim(), displayName = profile.displayName.trim(), phone = phone)
            if (customerProfiles().none { it.customerId == normalized.customerId }) return "Customer profile was not found. No change made."
            if (persistence.updateCustomerProfile(normalized, "customer-profile-update-${java.util.UUID.randomUUID()}")) "Customer profile updated in encrypted local storage."
            else "Customer profile could not be updated or the request was already recorded. No change made."
        } catch (error: Exception) {
            "Customer profile could not be updated. No change made. (${error.javaClass.simpleName})"
        }
    }
    fun creditRepayments(): List<CreditRepayment> = repayments.toList()
    fun paymentHistory(): List<PaymentEntry> = paymentEntries.toList()
    fun suppliers(): List<SupplierRecord> = supplierRecords.toList()
    fun purchaseHistory(): List<PurchaseReceipt> = purchaseReceipts.toList()
    fun supplierPaymentHistory(): List<SupplierPayment> = supplierPayments.toList()
    fun purchaseReturnHistory(): List<PurchaseReturn> = purchaseReturns.toList()
    fun expenseHistory(): List<BusinessExpense> = expenseRecords.toList()
    fun totalExpensesPaise(): Long = expenseRecords.sumOf { it.amountPaise.toLong() }
    fun cashDrawerHistory(): List<CashDrawerSession> = cashDrawerSessions.toList().reversed()
    fun closedCashDrawerHistory(): List<CashDrawerSession> = cashDrawerSessions.filter { it.closedAt != null }.reversed()
    fun activeCashDrawer(): CashDrawerSession? = cashDrawerSessions.lastOrNull()?.takeIf { it.closedAt == null }
    fun cashDrawerMovements(sessionId: String): List<CashDrawerMovement> = cashDrawerMovements.filter { it.sessionId == sessionId }
    fun closingReview(sessionId: String): CashClosingReview? = cashClosingReviews.firstOrNull { it.sessionId == sessionId }
    fun sessionPaymentEntries(sessionId: String): List<PaymentEntry> = paymentEntries.filter { it.drawerSessionId == sessionId }
    fun sessionCashExpenses(sessionId: String): List<BusinessExpense> = expenseRecords.filter { it.drawerSessionId == sessionId && it.method == "cash" }
    fun sessionCashSupplierPayments(sessionId: String): List<SupplierPayment> = supplierPayments.filter { it.drawerSessionId == sessionId && it.method == "cash" }
    fun currentOpenOrderCount(): Int = orders.values.count { !it.voided && it.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) }
    fun currentPendingUpiPaise(): Long = orders.values.filter { !it.voided && it.payment == Payment.UPI_PENDING }.sumOf { it.duePaise.toLong() }
    fun currentCustomerReceivablePaise(): Long = credits.sumOf { it.outstandingPaise.toLong() }.coerceAtLeast(0)
    fun expectedCashPaise(sessionId: String): Long? {
        val session = cashDrawerSessions.firstOrNull { it.sessionId == sessionId } ?: return null
        val collections = paymentEntries.filter { it.drawerSessionId == sessionId && it.method == "cash" && it.status in setOf("staff_declared", "staff_declared_refund") }.sumOf { it.amountPaise.toLong() }
        val expenses = expenseRecords.filter { it.drawerSessionId == sessionId && it.method == "cash" && it.status == "staff_declared" }.sumOf { it.amountPaise.toLong() }
        val supplierOut = supplierPayments.filter { it.drawerSessionId == sessionId && it.method == "cash" && it.status == "staff_declared" }.sumOf { it.amountPaise.toLong() }
        val adjustments = cashDrawerMovements.filter { it.sessionId == sessionId }.sumOf { if (it.kind == "cash_in") it.amountPaise.toLong() else -it.amountPaise.toLong() }
        return session.openingCashPaise.toLong() + collections - expenses - supplierOut + adjustments
    }
    fun openCashDrawer(openingCashPaise: Int, commandId: String = "drawer-open-${java.util.UUID.randomUUID()}", staffLabel: String? = null, staffActorId: String? = null): String {
        if (persistence == null) return "Cash drawer requires durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No drawer opened."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (activeCashDrawer() != null) return "A cash drawer session is already open. Close it before opening another."
        if (staffLabel != null && staffLabel.trim().length !in 2..60) return "Enter a staff label between 2 and 60 characters. No shift started."
        if (staffActorId != null && (!LocalSessionState.authenticated || LocalSessionState.actorId != staffActorId || LocalSessionState.ownerDisplayName != staffLabel?.trim())) return "Shift identity must match the signed-in local account. No shift started."
        if (LocalSessionState.authenticated && staffLabel != null && staffActorId != LocalSessionState.actorId) return "Authenticated shifts must be linked to the signed-in account. No shift started."
        if (openingCashPaise !in 0..100_000_000) return "Opening cash must be between Rs 0 and Rs 1,000,000. No drawer opened."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        cashDrawerSessions += CashDrawerSession(commandId, openingCashPaise, System.currentTimeMillis(), staffLabel = staffLabel?.trim(), staffActorId = staffActorId)
        val kind = if (staffLabel == null) "cash_drawer.opened" else "staff_shift.started"
        val result = if (staffLabel == null) "Cash drawer opened with ${money(openingCashPaise)} counted opening cash." else "Shift started for ${staffLabel.trim()} with ${money(openingCashPaise)} opening cash${if (staffActorId == null) " (legacy label is not authenticated)" else " (signed-in local account)"}."
        return commitMutation(before, commandId, result, "$kind session=$commandId opening=${openingCashPaise}p staff_label=${staffLabel?.trim() ?: "none"} staff_actor_id=${staffActorId ?: "unverified"}")
    }
    fun startStaffShift(staffLabel: String, openingCashPaise: Int, commandId: String = "shift-start-${java.util.UUID.randomUUID()}"): String {
        val actorId = if (LocalSessionState.authenticated) {
            if (staffLabel.trim() != LocalSessionState.ownerDisplayName) return "Shift name must match the signed-in account. No shift started."
            LocalSessionState.actorId ?: return "Sign in again before starting a shift. No shift started."
        } else null
        return openCashDrawer(openingCashPaise, commandId, staffLabel, actorId)
    }
    fun startAuthenticatedStaffShift(openingCashPaise: Int, commandId: String = "shift-start-${java.util.UUID.randomUUID()}"): String {
        if (!LocalSessionState.authenticated) return "Sign in before starting a staff shift. No shift started."
        val actorId = LocalSessionState.actorId ?: return "Sign in again before starting a shift. No shift started."
        val displayName = LocalSessionState.ownerDisplayName ?: return "Sign in again before starting a shift. No shift started."
        return openCashDrawer(openingCashPaise, commandId, displayName, actorId)
    }
    fun recordCashDrawerMovement(kind: String, amountPaise: Int, note: String, commandId: String = "drawer-move-${java.util.UUID.randomUUID()}"): String {
        if (persistence == null) return "Cash drawer requires durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No cash movement recorded."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val session = activeCashDrawer() ?: return "Open a cash drawer session first. No movement recorded."
        if (kind !in setOf("cash_in", "cash_out") || amountPaise !in 1..100_000_000 || note.trim().length !in 3..120) return "Enter cash in/out, a positive amount, and a short reason. No movement recorded."
        if (kind == "cash_out" && amountPaise.toLong() > (expectedCashPaise(session.sessionId) ?: 0L)) return "Cash out cannot exceed the currently expected drawer balance. No movement recorded."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        cashDrawerMovements += CashDrawerMovement(commandId, session.sessionId, kind, amountPaise, note.trim())
        return commitMutation(before, commandId, "${if (kind == "cash_in") "Cash added" else "Cash removed"}: ${money(amountPaise)}.", "cash_drawer.$kind session=${session.sessionId} amount=${amountPaise}p reason=${note.trim()}")
    }
    fun closeCashDrawer(countedCashPaise: Int, commandId: String = "drawer-close-${java.util.UUID.randomUUID()}", handoverToLabel: String? = null, handoverNote: String = ""): String {
        if (persistence == null) return "Cash drawer requires durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No drawer closed."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val session = activeCashDrawer() ?: return "There is no open cash drawer session."
        if (countedCashPaise !in 0..100_000_000) return "Counted cash must be between Rs 0 and Rs 1,000,000. No drawer closed."
        if (handoverToLabel?.trim()?.length?.let { it !in 2..60 } == true || handoverNote.trim().length > 160) return "Check the handover name and note lengths. No drawer closed."
        if ((handoverToLabel != null || handoverNote.isNotBlank()) && session.staffLabel == null) return "Handover details require a staff shift. No drawer closed."
        val expected = expectedCashPaise(session.sessionId) ?: return "Cash session is unavailable. No drawer closed."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        val index = cashDrawerSessions.indexOfFirst { it.sessionId == session.sessionId }
        cashDrawerSessions[index] = session.copy(closingCashPaise = countedCashPaise, closedAt = System.currentTimeMillis(), handoverToLabel = handoverToLabel?.trim(), handoverNote = handoverNote.trim())
        val variance = countedCashPaise.toLong() - expected
        val kind = if (session.staffLabel == null) "cash_drawer.closed" else "staff_shift.ended"
        val result = if (session.staffLabel == null) "Cash drawer closed. Expected ${money(expected)}; counted ${money(countedCashPaise)}; ${if (variance == 0L) "matched" else "unresolved variance ${money(variance)}"}." else "Shift ended for ${session.staffLabel}. Expected ${money(expected)}; handed over ${money(countedCashPaise)}; ${if (variance == 0L) "matched" else "unresolved variance ${money(variance)}"}."
        return commitMutation(before, commandId, result, "$kind session=${session.sessionId} expected=${expected}p counted=${countedCashPaise}p variance=${variance}p staff_label=${session.staffLabel ?: "none"} staff_actor_id=${session.staffActorId ?: "unverified"} handover_to=${handoverToLabel?.trim() ?: "none"} note=${handoverNote.trim()}")
    }
    fun endStaffShift(countedCashPaise: Int, handoverToLabel: String, note: String = "", commandId: String = "shift-end-${java.util.UUID.randomUUID()}"): String {
        val shift = activeCashDrawer()?.takeIf { it.staffLabel != null } ?: return "There is no active staff shift. No handover recorded."
        if (LocalSessionState.authenticated && !LocalSessionState.isManager && shift.staffActorId != LocalSessionState.actorId) return "Only the shift owner or a manager can close this shift. No handover recorded."
        if (handoverToLabel.trim().length !in 2..60) return "Enter the receiving staff label (2 to 60 characters). No shift ended."
        if (note.trim().length > 160) return "Handover note must be 160 characters or fewer. No shift ended."
        return closeCashDrawer(countedCashPaise, commandId, handoverToLabel, note)
    }
    fun recordCashClosingReview(sessionId: String, note: String = "", commandId: String = "cash-close-review-${java.util.UUID.randomUUID()}"): String {
        if (persistence == null) return "Closing review requires durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No review recorded."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val session = cashDrawerSessions.firstOrNull { it.sessionId == sessionId && it.closedAt != null } ?: return "Choose a closed cash session. No review recorded."
        if (cashClosingReviews.any { it.sessionId == sessionId }) return "This session's closing review is already recorded."
        val details = note.trim()
        if (details.length > 240) return "Closing note must be 240 characters or fewer. No review recorded."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        cashClosingReviews += CashClosingReview(commandId, session.sessionId, System.currentTimeMillis(), details)
        return commitMutation(before, commandId, "Closing review recorded for cash session.", "cash_closing.reviewed session=$sessionId variance=${session.closingCashPaise!!.toLong() - (expectedCashPaise(sessionId) ?: 0L)}p open_orders=${currentOpenOrderCount()} pending_upi=${currentPendingUpiPaise()}p")
    }
    fun supplierPayablePaise(supplierId: String): Long = purchaseReceipts.filter { it.supplierId == supplierId }.sumOf { it.totalPaise } -
        supplierPayments.filter { it.supplierId == supplierId }.sumOf { it.amountPaise.toLong() } - purchaseReturns.filter { it.supplierId == supplierId }.sumOf { it.totalPaise }

    fun purchaseOrderDrafts(): List<PurchaseOrderDraft> = purchaseOrderRecords.toList().asReversed()

    fun purchaseOrderReceivedQuantities(purchaseOrderId: String): Map<String, Int> = purchaseReceipts
        .filter { it.purchaseOrderId == purchaseOrderId }.flatMap { it.lines }.groupBy { it.productId }
        .mapValues { (_, lines) -> lines.sumOf { it.quantity } }

    fun purchaseOrderState(order: PurchaseOrderDraft): String {
        if (order.status == PurchaseOrderStatus.CANCELLED) return "CANCELLED"
        val received = purchaseOrderReceivedQuantities(order.purchaseOrderId)
        val ordered = order.lines.sumOf { it.orderedQuantity }
        val receivedTotal = received.values.sum()
        return when {
            ordered > 0 && receivedTotal >= ordered -> "RECEIVED"
            receivedTotal > 0 -> "PARTIALLY RECEIVED"
            order.status == PurchaseOrderStatus.SENT -> "SENT"
            else -> "DRAFT"
        }
    }

    /** Saves a plan only; no inventory, payable, cash, or purchase receipt is created. */
    fun createPurchaseOrderDraft(
        supplierId: String,
        quantities: Map<String, Int>,
        unitCostsPaise: Map<String, Int>,
        purchaseOrderId: String = "po-${java.util.UUID.randomUUID()}",
        commandId: String = "purchase-order-${java.util.UUID.randomUUID()}"
    ): String {
        barrierRejectionIfActive()?.let { return it }
        if (persistence == null) return "Purchase orders require durable shop storage."
        if (commandId.isBlank() || purchaseOrderId.isBlank()) return "Purchase order and command IDs are required. No order saved."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (purchaseOrderRecords.any { it.purchaseOrderId == purchaseOrderId }) return "Purchase order ID already exists. No order saved."
        if (supplierRecords.none { it.supplierId == supplierId }) return "Choose an existing supplier. No order saved."
        if (quantities.isEmpty() || quantities.size !in 1..50 || quantities.keys != unitCostsPaise.keys) return "Add 1 to 50 products with quantity and estimated unit cost. No order saved."
        val products = (configuredProductsOrNull() ?: return maintenanceRejection()).associateBy { it.productId }
        val lines = quantities.toSortedMap().map { (productId, quantity) ->
            val product = products[productId]?.takeIf { it.active } ?: return "Active catalogue product $productId was not found. No order saved."
            val cost = unitCostsPaise[productId] ?: return "Estimated unit cost is missing. No order saved."
            if (quantity !in 1..100_000 || cost !in 1..50_000_000) return "Enter valid quantities and positive estimated costs. No order saved."
            PurchaseOrderDraftLine(productId, product.name, quantity, cost)
        }
        val total = lines.sumOf { it.lineCostPaise }
        if (total !in 1..Int.MAX_VALUE.toLong()) return "Estimated order total is outside the supported range. No order saved."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        purchaseOrderRecords += PurchaseOrderDraft(purchaseOrderId, supplierId, lines, PurchaseOrderStatus.DRAFT, createdAtEpochMs = System.currentTimeMillis())
        return commitMutation(before, commandId, "Purchase order draft $purchaseOrderId saved; no stock or payable was changed.",
            "purchase_order.draft_saved id=$purchaseOrderId supplier=$supplierId estimated_total=${total}p")
    }

    fun markPurchaseOrderSent(purchaseOrderId: String, expectedRevision: Int, commandId: String = "purchase-order-sent-${java.util.UUID.randomUUID()}"): String {
        if (persistence == null) return "Purchase orders require durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No change made."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val index = purchaseOrderRecords.indexOfFirst { it.purchaseOrderId == purchaseOrderId }
        if (index < 0) return "Purchase order was not found. No change made."
        val order = purchaseOrderRecords[index]
        if (order.revision != expectedRevision) return "Purchase order changed; refresh before updating it. No change made."
        if (order.status != PurchaseOrderStatus.DRAFT) return "Only a draft can be marked sent. No change made."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        purchaseOrderRecords[index] = order.copy(status = PurchaseOrderStatus.SENT, revision = order.revision + 1)
        return commitMutation(before, commandId, "Purchase order marked sent by the owner; no supplier message was sent by the app.",
            "purchase_order.marked_sent id=$purchaseOrderId")
    }

    fun cancelPurchaseOrder(purchaseOrderId: String, expectedRevision: Int, reason: String, commandId: String = "purchase-order-cancel-${java.util.UUID.randomUUID()}"): String {
        if (persistence == null) return "Purchase orders require durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No change made."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val index = purchaseOrderRecords.indexOfFirst { it.purchaseOrderId == purchaseOrderId }
        if (index < 0) return "Purchase order was not found. No change made."
        val order = purchaseOrderRecords[index]
        if (order.revision != expectedRevision) return "Purchase order changed; refresh before updating it. No change made."
        if (order.status == PurchaseOrderStatus.CANCELLED || purchaseOrderReceivedQuantities(purchaseOrderId).values.sum() > 0) return "An already cancelled or partially received order cannot be cancelled through this path. No change made."
        val note = reason.trim()
        if (note.length !in 3..120) return "Enter a cancellation reason from 3 to 120 characters. No change made."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        purchaseOrderRecords[index] = order.copy(status = PurchaseOrderStatus.CANCELLED, revision = order.revision + 1)
        return commitMutation(before, commandId, "Purchase order cancelled; no stock or payable was changed.",
            "purchase_order.cancelled id=$purchaseOrderId reason=$note")
    }

    fun receivePurchaseOrder(purchaseOrderId: String, receivedQuantities: Map<String, Int>, commandId: String = "purchase-receipt-${java.util.UUID.randomUUID()}", supplierInvoiceReference: String? = null): String {
        val order = purchaseOrderRecords.firstOrNull { it.purchaseOrderId == purchaseOrderId }
            ?: return "Purchase order was not found. No receipt recorded."
        if (order.status != PurchaseOrderStatus.SENT) return "Only a sent purchase order can be received. No receipt recorded."
        val costs = order.lines.associate { it.productId to it.unitCostPaise }.filterKeys { it in receivedQuantities }
        return receivePurchase(order.supplierId, receivedQuantities, costs, commandId, purchaseOrderId, supplierInvoiceReference)
    }

    fun saveSupplier(supplierId: String, name: String, commandId: String = "supplier-${java.util.UUID.randomUUID()}", phone: String? = null): String {
        if (persistence == null) return "Supplier records require durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No change made."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val id = supplierId.trim().lowercase(Locale.ROOT)
        val displayName = name.trim()
        val normalizedPhone = phone?.trim()?.takeIf { it.isNotEmpty() }
        if (!id.matches(Regex("[a-z][a-z0-9_]{1,39}"))) return "Supplier ID must use 2 to 40 lowercase letters, numbers or underscores."
        if (displayName.length !in 2..80) return "Enter a supplier name between 2 and 80 characters."
        if (normalizedPhone != null && (!normalizedPhone.matches(Regex("\\+?[0-9][0-9 ()-]{5,19}")) || normalizedPhone.count(Char::isDigit) !in 7..15)) return "Enter a valid supplier phone number or leave it blank."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        val index = supplierRecords.indexOfFirst { it.supplierId == id }
        val existingPhone = supplierRecords.getOrNull(index)?.phone
        val existing = supplierRecords.getOrNull(index)
        val record = SupplierRecord(id, displayName, if (phone == null) existingPhone else normalizedPhone, existing?.syncId ?: java.util.UUID.randomUUID().toString())
        if (index >= 0) supplierRecords[index] = record else supplierRecords += record
        return commitMutation(before, commandId, "Supplier saved: $displayName.", "supplier.saved id=$id name=$displayName contact=${if (normalizedPhone == null) "none" else "set"}")
    }

    /** Direct verified receipt. It requires a confirmed physical stock baseline and posts payable + stock atomically. */
    fun receivePurchase(supplierId: String, quantities: Map<String, Int>, unitCostsPaise: Map<String, Int>, commandId: String = "purchase-${java.util.UUID.randomUUID()}", purchaseOrderId: String? = null, supplierInvoiceReference: String? = null): String {
        barrierRejectionIfActive()?.let { return it }
        if (persistence == null) return "Purchase receiving requires durable shop storage."
        if (commandId.isBlank()) return "Purchase receipt command ID is required. No change made."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (supplierRecords.none { it.supplierId == supplierId }) return "Choose an existing supplier. No receipt recorded."
        val normalizedInvoiceReference = supplierInvoiceReference?.trim()?.takeIf { it.isNotEmpty() }
        if (supplierInvoiceReference != null && normalizedInvoiceReference != null &&
            (normalizedInvoiceReference.length > 80 || normalizedInvoiceReference.any { it.isISOControl() })) {
            return "Supplier invoice reference must be 80 characters or fewer and contain no control characters. No receipt recorded."
        }
        if (normalizedInvoiceReference != null && purchaseReceipts.any {
                it.supplierId == supplierId && it.supplierInvoiceReference.equals(normalizedInvoiceReference, ignoreCase = true)
            }) return "This supplier invoice reference is already recorded. No receipt recorded."
        val linkedOrder = purchaseOrderId?.let { id -> purchaseOrderRecords.firstOrNull { it.purchaseOrderId == id } }
        if (purchaseOrderId != null && (linkedOrder == null || linkedOrder.supplierId != supplierId || linkedOrder.status != PurchaseOrderStatus.SENT)) return "Choose an open sent purchase order for this supplier. No receipt recorded."
        if (quantities.isEmpty() || quantities.size !in 1..50 || quantities.keys != unitCostsPaise.keys) return "Add 1 to 50 products with quantity and unit cost. No receipt recorded."
        val products = (configuredProductsOrNull() ?: return maintenanceRejection()).associateBy { it.productId }
        val lines = mutableListOf<PurchaseReceiptLine>()
        for ((productId, quantity) in quantities) {
            val product = products[productId]?.takeIf { it.active } ?: return "Active catalogue product $productId was not found. No receipt recorded."
            val unitCost = unitCostsPaise[productId] ?: return "Unit cost is missing. No receipt recorded."
            if (quantity !in 1..100_000 || unitCost !in 1..50_000_000) return "Enter a valid quantity and positive cost. No receipt recorded."
            if (linkedOrder != null) {
                val ordered = linkedOrder.lines.firstOrNull { it.productId == productId } ?: return "Product $productId is not on this purchase order. No receipt recorded."
                val alreadyReceived = purchaseOrderReceivedQuantities(linkedOrder.purchaseOrderId)[productId] ?: 0
                if (unitCost != ordered.unitCostPaise || quantity > ordered.orderedQuantity - alreadyReceived) return "Receipt exceeds the remaining ordered quantity or cost. No receipt recorded."
            }
            if (requireConfiguredProducts && productId !in stockConfirmedProductIds) return "Confirm the physical stock baseline for ${product.name} before receiving more stock. No receipt recorded."
            if ((stock[productId] ?: 0) > Int.MAX_VALUE - quantity) return "Stock quantity would exceed the supported range. No receipt recorded."
            lines += PurchaseReceiptLine(productId, product.name, quantity, unitCost)
        }
        val total = lines.sumOf { it.lineCostPaise }
        if (total !in 1..Int.MAX_VALUE.toLong()) return "Receipt total is outside the supported financial range. No receipt recorded."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        lines.forEach { line ->
            stock[line.productId] = (stock[line.productId] ?: 0) + line.quantity
            movements += StockMovement("$commandId:${line.productId}:purchase", line.productId, line.quantity, "purchase_receipt", null)
        }
        purchaseReceipts += PurchaseReceipt(commandId, supplierId, lines, total, purchaseOrderId, normalizedInvoiceReference)
        return commitMutation(before, commandId, "Received ${lines.size} product lines; supplier payable ${money(total.toInt())}.", "purchase.received receipt=$commandId supplier=$supplierId purchase_order=${purchaseOrderId ?: "direct"} invoice_reference=${normalizedInvoiceReference ?: "none"} total=${total}p")
    }

    fun recordSupplierCashPayment(supplierId: String, amountPaise: Int, commandId: String = "supplier-payment-${java.util.UUID.randomUUID()}"): String {
        return recordSupplierPayment(supplierId, amountPaise, "cash", commandId)
    }

    /** Records operator-declared cash or UPI settlement; UPI is never provider-verified here. */
    fun recordSupplierPayment(supplierId: String, amountPaise: Int, method: String, commandId: String = "supplier-payment-${java.util.UUID.randomUUID()}"): String {
        if (persistence == null) return "Supplier payments require durable shop storage."
        if (commandId.isBlank()) return "Supplier payment command ID is required. No change made."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (method !in setOf("cash", "upi")) return "Choose cash or UPI. No payment recorded."
        if (supplierRecords.none { it.supplierId == supplierId }) return "Supplier was not found. No payment recorded."
        val balance = supplierPayablePaise(supplierId)
        if (amountPaise <= 0 || amountPaise.toLong() > balance) return "Payment must be positive and cannot exceed supplier payable ${money(balance.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}. No change made."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        supplierPayments += SupplierPayment(commandId, supplierId, amountPaise, method, "staff_declared", if (method == "cash") activeCashDrawer()?.sessionId else null)
        val methodLabel = if (method == "cash") "cash" else "UPI"
        return commitMutation(before, commandId, "Staff-declared $methodLabel payment ${money(amountPaise)} recorded for supplier $supplierId; provider verification is not available.", "supplier.payment_recorded supplier=$supplierId amount=${amountPaise}p method=$method status=staff_declared")
    }

    /** Return unused received stock to its original supplier; only unpaid receipt value may be credited. */
    fun returnPurchase(supplierId: String, receiptId: String, quantities: Map<String, Int>, reason: String, commandId: String = "purchase-return-${java.util.UUID.randomUUID()}"): String {
        if (persistence == null) return "Purchase returns require durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No change made."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val receipt = purchaseReceipts.firstOrNull { it.receiptId == receiptId && it.supplierId == supplierId }
            ?: return "Original supplier receipt was not found. No return recorded."
        val note = reason.trim()
        if (note.length !in 3..120 || quantities.isEmpty()) return "Enter returned products and a reason. No return recorded."
        val payable = supplierPayablePaise(supplierId)
        if (payable <= 0) return "There is no outstanding supplier payable to credit. No return recorded."
        val alreadyReturned = purchaseReturns.filter { it.receiptId == receiptId }.flatMap { it.lines }.groupBy { it.productId }
            .mapValues { (_, lines) -> lines.sumOf { it.quantity } }
        val receiptLines = receipt.lines.associateBy { it.productId }
        val lines = mutableListOf<PurchaseReturnLine>()
        for ((productId, quantity) in quantities) {
            val original = receiptLines[productId] ?: return "Product was not part of the selected receipt. No return recorded."
            if (quantity !in 1..100_000 || quantity > original.quantity - (alreadyReturned[productId] ?: 0)) return "Return quantity exceeds the unreturned receipt quantity. No return recorded."
            if ((stock[productId] ?: 0) < quantity) return "Available stock is insufficient; unavailable units cannot be returned. No return recorded."
            lines += PurchaseReturnLine(productId, original.productName, quantity, original.unitCostPaise)
        }
        val total = lines.sumOf { it.lineValuePaise }
        if (total > payable) return "Return value cannot exceed this supplier's outstanding payable. No return recorded."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        lines.forEach { line ->
            stock[line.productId] = (stock[line.productId] ?: 0) - line.quantity
            movements += StockMovement("$commandId:${line.productId}:purchase_return", line.productId, -line.quantity, "purchase_return", null)
        }
        purchaseReturns += PurchaseReturn(commandId, supplierId, receiptId, lines, total)
        return commitMutation(before, commandId, "Purchase return recorded; supplier payable reduced ${money(total.toInt())}.", "purchase.returned receipt=$receiptId supplier=$supplierId reason=$note total=${total}p")
    }

    /** Records a declared operating expense; a supplier settlement is not an expense here. */
    fun recordExpense(category: String, amountPaise: Int, method: String, note: String = "", commandId: String = "expense-${java.util.UUID.randomUUID()}"): String {
        if (persistence == null) return "Expense recording requires durable shop storage."
        if (commandId.isBlank()) return "Command ID is required. No expense recorded."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val normalizedCategory = category.trim().lowercase(Locale.ROOT)
        val details = note.trim()
        if (normalizedCategory.length !in 2..40 || !normalizedCategory.matches(Regex("[a-z][a-z0-9 _-]*"))) return "Enter a category using simple letters and numbers. No expense recorded."
        if (amountPaise !in 1..100_000_000) return "Enter a positive amount no greater than Rs 1,000,000. No expense recorded."
        if (method !in setOf("cash", "upi", "card")) return "Choose cash, UPI or card as the declared payment method. No expense recorded."
        if (details.length > 160) return "Expense note must be 160 characters or fewer. No expense recorded."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        expenseRecords += BusinessExpense(commandId, normalizedCategory, amountPaise, method, "staff_declared", details,
            if (method == "cash") activeCashDrawer()?.sessionId else null, System.currentTimeMillis())
        return commitMutation(before, commandId, "Expense recorded: $normalizedCategory ${money(amountPaise)} via $method.", "expense.recorded category=$normalizedCategory amount=${amountPaise}p method=$method status=staff_declared")
    }

    fun parse(transcript: String): ProposedCommand? {
        val text = normalize(transcript)
        if (text.isBlank()) return null
        val startsOrder = Regex("\\b(new order|naya order|nava order|order banao)\\b").containsMatchIn(text)
        val product = productFor(text)
        if (startsOrder) {
            if (product == null) return null
            val qty = quantityBefore(text,product.first)
            return ProposedCommand("create",null,"Create order · $qty × ${product.second}",product.first,qty)
        }
        val orderNumber = Regex("\\border\\s*#?\\s*(\\d+)\\b").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (Regex("\\b(cancel|cancelled|cancel karo|cancel kar|radd)\\b").containsMatchIn(text)) return ProposedCommand("cancel",orderNumber,"Cancel order #$orderNumber with reason")
        if (Regex("\\b(remove|hatao|nikalo)\\b").containsMatchIn(text)) {
            if (product == null) return null
            return ProposedCommand("remove",orderNumber,"Remove ${product.second} from order #$orderNumber",product.first,quantityBefore(text,product.first))
        }
        if (Regex("\\b(add|jodo|add karo)\\b").containsMatchIn(text)) {
            if (product == null) return null
            return ProposedCommand("add",orderNumber,"Add ${product.second} to order #$orderNumber",product.first,quantityBefore(text,product.first))
        }
        return when {
            Regex("\\b(de diya|de di|given|handed over|customer le gaya)\\b").containsMatchIn(text) && product != null -> ProposedCommand("partial_given",orderNumber,"Hand over ${product.second} for order #$orderNumber",product.first,quantityBefore(text,product.first))
            Regex("\\b(preparing|ban raha|bana rahe|making)\\b").containsMatchIn(text) -> ProposedCommand("preparing",orderNumber,"Mark order #$orderNumber preparing")
            Regex("\\b(ready|taiyar|tayyar)\\b").containsMatchIn(text) -> ProposedCommand("ready",orderNumber,"Mark order #$orderNumber ready")
            Regex("\\b(de diya|de di|given|handed over|customer le gaya)\\b").containsMatchIn(text) -> ProposedCommand("given",orderNumber,"Hand over order #$orderNumber")
            Regex("\\b(upi|gpay|phonepe|paytm)\\b").containsMatchIn(text) -> ProposedCommand("upi",orderNumber,"Mark UPI pending for order #$orderNumber")
            Regex("\\b(cash|nagdi|cash mila|cash received)\\b").containsMatchIn(text) -> {
                val amountText = text.replace(Regex("\\border\\s*#?\\s*\\d+\\b"),"")
                val rupees = Regex("\\b(\\d+)\\s*(rupees?|rs)?\\s*(cash|mila|received)\\b").find(amountText)?.groupValues?.get(1)?.toIntOrNull()
                ProposedCommand("cash",orderNumber,"Record staff-declared cash for order #$orderNumber",amountPaise=rupees?.times(100))
            }
            else -> null
        }
    }

    fun apply(commandId: String,command: ProposedCommand): String {
        if (commandId.isBlank()) return "Command ID is required. No change made."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val correctionReason = if (command.type == "remove") command.auditReason?.trim()?.takeIf { it.length in 3..120 && it.none(Char::isISOControl) }
            ?: return "A correction reason of 3–120 characters is required. No change made." else null
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()

        if (command.type == "create") {
            val line = line(command.productId ?: return "Unknown product.",command.quantity) ?: return "Unknown product or quantity."
            val order = CounterOrder(nextNumber++,mutableListOf(line))
            orders[order.number]=order
            val result = "Order #${order.number} created · ${order.items} · ${money(order.totalPaise)}"
            return commitMutation(before, commandId, result, "order.created #${order.number}", order)
        }
        val order = command.orderNumber?.let(orders::get) ?: return "Order not found. No change made."
        if (command.expectedRevision != null && command.expectedRevision != order.revision) return "Order revision conflict. Refresh and review the order before retrying."
        val result = when(command.type) {
            "add" -> {
                if (order.fulfillment !in setOf(Fulfillment.TAKEN,Fulfillment.PREPARING,Fulfillment.READY)) return "Order is closed to item changes."
                val added=line(command.productId ?: return "Unknown product.",command.quantity) ?: return "Unknown product or quantity."
                val existing=order.lines.indexOfFirst { it.productId==added.productId }
                if(existing>=0){val old=order.lines[existing]; order.lines[existing]=old.copy(quantity=old.quantity+added.quantity)} else order.lines += added
                "Added ${added.quantity} × ${added.name} · new total ${money(order.totalPaise)}"
            }
            "remove" -> {
                if(order.fulfillment !in setOf(Fulfillment.TAKEN,Fulfillment.PREPARING,Fulfillment.READY,Fulfillment.PARTIALLY_GIVEN)) return "Order is closed to item changes."
                val idx=order.lines.indexOfFirst { it.productId==command.productId && (it.quantity-it.deliveredQuantity)>=command.quantity }
                if(idx<0) return "Item/quantity not found. No change made."
                val old=order.lines[idx]
                if (old.quantity-old.deliveredQuantity == command.quantity && order.lines.size == 1) return "An order cannot be empty; cancel it instead."
                if(old.quantity==command.quantity) order.lines.removeAt(idx) else order.lines[idx]=old.copy(quantity=old.quantity-command.quantity)
                "Item removed · new total ${money(order.totalPaise)}"
            }
            "ready" -> if(order.fulfillment==Fulfillment.TAKEN || order.fulfillment==Fulfillment.PREPARING) { order.fulfillment=Fulfillment.READY; "Order #${order.number} ready." } else "Invalid fulfillment transition from ${order.fulfillment}."
            "preparing" -> if(order.fulfillment==Fulfillment.TAKEN) { order.fulfillment=Fulfillment.PREPARING; "Order #${order.number} preparing." } else "Invalid fulfillment transition from ${order.fulfillment}."
            "given" -> {
                if(order.fulfillment!=Fulfillment.READY && order.fulfillment!=Fulfillment.PARTIALLY_GIVEN) return "Mark the order ready before handover."
                val remaining = order.lines.map { it to (it.quantity-it.deliveredQuantity) }.filter { it.second > 0 }
                if(remaining.isEmpty()) return "All order items were already handed over."
                if (requireConfiguredProducts && remaining.any { (line, _) -> line.productId !in stockConfirmedProductIds }) return "Opening stock is not confirmed for every order item. No handover or sale recorded."
                if(remaining.any{(line,qty)->(stock[line.productId]?:0)<qty}) return "Insufficient stock. No sale or stock movement recorded."
                remaining.forEach{(line,qty)->
                    stock[line.productId]=(stock[line.productId]?:0)-qty
                    movements += StockMovement("$commandId:${line.productId}", line.productId, -qty, "sale_handover", order.number)
                }
                order.lines.indices.forEach { i -> order.lines[i]=order.lines[i].copy(deliveredQuantity=order.lines[i].quantity) }
                order.fulfillment=Fulfillment.GIVEN;order.salePosted=true
                "Order #${order.number} given · ${money(order.totalPaise)} sale recorded · payment remains ${order.payment.name.lowercase()}."
            }
            "partial_given" -> {
                if(order.fulfillment!=Fulfillment.READY && order.fulfillment!=Fulfillment.PARTIALLY_GIVEN) return "Mark the order ready before handover."
                val index=order.lines.indexOfFirst { it.productId==command.productId }
                if(index<0) return "Item not found. No change made."
                val line=order.lines[index]
                if(command.quantity !in 1..(line.quantity-line.deliveredQuantity)) return "Handover quantity exceeds remaining items. No change made."
                if (requireConfiguredProducts && line.productId !in stockConfirmedProductIds) return "Opening stock is not confirmed for this item. No handover or sale recorded."
                if((stock[line.productId]?:0)<command.quantity) return "Insufficient stock. No sale or stock movement recorded."
                order.lines[index]=line.copy(deliveredQuantity=line.deliveredQuantity+command.quantity)
                stock[line.productId]=(stock[line.productId]?:0)-command.quantity
                movements += StockMovement("$commandId:${line.productId}",line.productId,-command.quantity,"partial_handover",order.number)
                val allGiven=order.lines.all { it.deliveredQuantity==it.quantity }
                order.fulfillment=if(allGiven) Fulfillment.GIVEN else Fulfillment.PARTIALLY_GIVEN
                order.salePosted=allGiven
                "Handed over ${command.quantity} × ${line.name} · ${if(allGiven) "order complete" else "more items remain"}."
            }
            "cash" -> {
                if(order.fulfillment !in setOf(Fulfillment.GIVEN,Fulfillment.CANCELLED) || order.salePaise<=0) return "Cash not recorded: finish or cancel the order before collection."
                if(order.voided || order.duePaise <= 0 || order.payment in setOf(Payment.UPI_PENDING,Payment.REFUNDED,Payment.CANCELLED)) return "Cash not recorded: payment is already settled, pending, or cancelled."
                val amount=command.amountPaise ?: order.duePaise
                if(amount<=0 || amount>order.duePaise) return "Cash amount exceeds the due amount. No change made."
                order.paidPaise += amount
                updatePaymentState(order)
                paymentEntries += PaymentEntry(commandId, order.number, null, amount, "cash", "staff_declared", "order_collection", drawerSessionId = activeCashDrawer()?.sessionId)
                "Cash declared ${money(amount)} · balance ${money(order.duePaise)}"
            }
            "udhar" -> {
                val customerId=command.customerId?.trim()?.takeIf { it.isNotEmpty() } ?: return "Select a customer account before recording Udhar. No change made."
                if(order.fulfillment !in setOf(Fulfillment.GIVEN,Fulfillment.CANCELLED) || order.salePaise<=0) return "Udhar requires delivered items on a finished order. No change made."
                if(order.voided || order.payment in setOf(Payment.UPI_PENDING,Payment.REFUNDED,Payment.CANCELLED) || order.duePaise<=0) return "Order has no eligible balance for Udhar. No change made."
                if (credits.any { it.orderNumber == order.number && it.outstandingPaise > 0 && it.customerId != customerId }) return "This order already has credit assigned to another customer. No change made."
                val amount=command.amountPaise ?: order.duePaise
                if(amount<=0 || amount>order.duePaise) return "Udhar amount exceeds the due amount. No change made."
                credits += CustomerCredit("$commandId-credit",customerId,order.number,amount,0)
                order.creditPaise += amount
                updatePaymentState(order)
                paymentEntries += PaymentEntry(commandId, order.number, customerId, amount, "udhar", "receivable", "credit_sale")
                "Udhar ${money(amount)} recorded for customer $customerId · balance ${money(order.duePaise)}."
            }
            "cash_udhar_split" -> {
                val customerId = command.customerId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return "Select a customer account before recording Udhar. No change made."
                if (order.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) || order.salePaise <= 0)
                    return "Split collection requires a finished order. No change made."
                if (order.voided || order.payment in setOf(Payment.UPI_PENDING, Payment.REFUNDED, Payment.CANCELLED) || order.duePaise <= 0)
                    return "Order has no eligible balance for a split collection. No change made."
                if (credits.any { it.orderNumber == order.number && it.outstandingPaise > 0 && it.customerId != customerId })
                    return "This order already has credit assigned to another customer. No change made."
                val cashPaise = command.amountPaise ?: return "Enter the cash portion of the split collection. No change made."
                if (cashPaise <= 0 || cashPaise >= order.duePaise)
                    return "Cash must be positive and less than the due so a Udhar balance remains. No change made."
                val creditPaise = order.duePaise - cashPaise
                order.paidPaise += cashPaise
                order.creditPaise += creditPaise
                credits += CustomerCredit("$commandId-credit", customerId, order.number, creditPaise, 0)
                paymentEntries += PaymentEntry("$commandId-cash", order.number, null, cashPaise, "cash", "staff_declared", "order_collection", drawerSessionId = activeCashDrawer()?.sessionId)
                paymentEntries += PaymentEntry("$commandId-udhar", order.number, customerId, creditPaise, "udhar", "receivable", "credit_sale")
                updatePaymentState(order)
                "Split recorded · cash declared ${money(cashPaise)} · Udhar ${money(creditPaise)} for $customerId."
            }
            "cash_upi_pending_split" -> {
                if (order.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) || order.salePaise <= 0)
                    return "Split collection requires a finished order. No change made."
                if (order.voided || order.payment in setOf(Payment.UPI_PENDING, Payment.REFUNDED, Payment.CANCELLED) || order.duePaise <= 0)
                    return "Order has no eligible balance for a split collection. No change made."
                val cashPaise = command.amountPaise ?: return "Enter the cash portion of the split collection. No change made."
                if (cashPaise <= 0 || cashPaise >= order.duePaise)
                    return "Cash must be positive and less than the due so a UPI balance remains. No change made."
                val upiPaise = order.duePaise - cashPaise
                order.paidPaise += cashPaise
                order.payment = Payment.UPI_PENDING
                paymentEntries += PaymentEntry("$commandId-cash", order.number, null, cashPaise, "cash", "staff_declared", "order_collection", drawerSessionId = activeCashDrawer()?.sessionId)
                paymentEntries += PaymentEntry("$commandId-upi-attempt", order.number, null, upiPaise, "upi", "pending", "order_collection")
                "Split recorded · cash declared ${money(cashPaise)} · UPI pending ${money(upiPaise)}; not verified."
            }
            "upi_direct" -> {
                if (order.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) || order.salePaise <= 0)
                    return "UPI receipt requires a finished order. No change made."
                if (order.voided || order.payment == Payment.UPI_PENDING || order.payment in setOf(Payment.REFUNDED, Payment.CANCELLED) || order.duePaise <= 0)
                    return "Resolve any pending UPI attempt first; no direct receipt was recorded."
                val amount = command.amountPaise ?: order.duePaise
                if (amount <= 0 || amount > order.duePaise) return "UPI amount exceeds the due amount. No change made."
                order.paidPaise += amount
                paymentEntries += PaymentEntry(commandId, order.number, null, amount, "upi", "staff_declared", "order_collection")
                updatePaymentState(order)
                "UPI receipt ${money(amount)} recorded as staff-declared; it is not provider-verified. Balance ${money(order.duePaise)}."
            }
            "upi" -> {
                if(order.fulfillment !in setOf(Fulfillment.GIVEN,Fulfillment.CANCELLED) || order.salePaise<=0) return "UPI not recorded: finish or cancel the order first."
                if(order.voided || order.duePaise<=0 || order.payment in setOf(Payment.UPI_PENDING,Payment.REFUNDED,Payment.CANCELLED)) return "UPI not recorded: resolve the current payment state first."
                order.payment=Payment.UPI_PENDING
                paymentEntries += PaymentEntry(commandId, order.number, null, order.duePaise, "upi", "pending", "order_collection")
                "UPI pending for order #${order.number}; not verified."
            }
            "upi_received" -> {
                if (order.fulfillment !in setOf(Fulfillment.GIVEN, Fulfillment.CANCELLED) || order.voided || order.payment != Payment.UPI_PENDING) return "No pending UPI attempt is available to confirm. No collection recorded."
                val attempt = paymentEntries.lastOrNull { it.orderNumber == order.number && it.method == "upi" && it.status == "pending" }
                    ?: return "Pending UPI attempt was not found. No collection recorded."
                val amount = command.amountPaise ?: minOf(order.duePaise, attempt.amountPaise)
                if (amount <= 0 || amount > order.duePaise || amount > attempt.amountPaise) return "Staff-declared UPI amount must be positive and cannot exceed the pending attempt or current due. No collection recorded."
                paymentEntries += PaymentEntry("$commandId-attempt-cancel", order.number, null, 0, "upi", "cancelled", "payment_attempt_cancelled", attempt.entryId)
                order.paidPaise += amount
                paymentEntries += PaymentEntry(commandId, order.number, null, amount, "upi", "staff_declared", "order_collection")
                updatePaymentState(order)
                "UPI receipt ${money(amount)} recorded as staff-declared; it is not provider-verified. Balance ${money(order.duePaise)}."
            }
            "cancel" -> return "Cancellation requires a reason through the correction control."
            else -> return "Unsupported command."
        }
        if(result.startsWith("Invalid fulfillment") || result.startsWith("Mark the order") || result.startsWith("Order revision conflict")) return result
        val auditEvent = if (correctionReason != null) {
            "remove #${order.number} product=${command.productId} quantity=${command.quantity} reason=$correctionReason"
        } else "${command.type} #${order.number}"
        return commitMutation(before, commandId, result, auditEvent, order)
    }

    fun cancelOrder(number: Int,reason: String,commandId: String = "cancel-${System.nanoTime()}",expectedRevision: Int? = null): String {
        if (commandId.isBlank()) return "Command ID is required."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        val order=orders[number] ?: return "Order not found."
        if(expectedRevision != null && expectedRevision != order.revision) return "Order revision conflict. Refresh and review the order before retrying."
        if(order.fulfillment==Fulfillment.GIVEN) return "A handed-over order requires an authorized refund. No change made."
        if(order.fulfillment==Fulfillment.CANCELLED || order.voided) return "Order is already cancelled. No change made."
        val correctionReason = reason.trim()
        if(correctionReason.length !in 3..120 || correctionReason.any(Char::isISOControl)) return "A correction reason of 3–120 characters is required. No change made."
        order.fulfillment=Fulfillment.CANCELLED; order.cancelReason=correctionReason; order.salePosted=order.salePaise>0
        return commitMutation(before, commandId, "Order #$number cancelled with correction reason.", "order.cancelled #$number reason=$correctionReason", order)
    }

    /** Manager correction of a price before any fulfillment or financial event has been recorded. */
    fun adjustUnhandedUnitPrice(
        number: Int,
        productId: String,
        newUnitPricePaise: Int,
        reason: String,
        commandId: String,
        expectedRevision: Int? = null
    ): String {
        if (commandId.isBlank()) return "Command ID is required. No change made."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (newUnitPricePaise !in 1..10_000_000) return "Enter a unit price from 1 to 10000000 paise. No change made."
        val correctionReason = reason.trim()
        if (correctionReason.length !in 3..120 || correctionReason.any(Char::isISOControl)) return "A correction reason of 3–120 characters is required. No change made."
        val order = orders[number] ?: return "Order not found. No change made."
        if (expectedRevision != null && expectedRevision != order.revision) return "Order revision conflict. Refresh and review the order before retrying."
        if (order.voided || order.fulfillment !in setOf(Fulfillment.TAKEN, Fulfillment.PREPARING, Fulfillment.READY)) return "Price changes require an open order before handover. No change made."
        if (order.lines.any { it.deliveredQuantity > 0 } || order.salePosted || order.paidPaise != 0 || order.creditPaise != 0 || order.refundedPaise != 0 || paymentEntries.any { it.orderNumber == number }) {
            return "Price changes are unavailable after fulfillment or financial activity. No change made."
        }
        val index = order.lines.indexOfFirst { it.productId == productId }
        if (index < 0) return "Product is not in this order. No change made."
        val oldLine = order.lines[index]
        if (oldLine.unitPricePaise == newUnitPricePaise) return "The price is unchanged. No change made."
        val updatedTotal = order.lines.sumOf { line ->
            (if (line.productId == productId) newUnitPricePaise else line.unitPricePaise).toLong() * line.quantity
        }
        if (updatedTotal !in 1..Int.MAX_VALUE.toLong()) return "Updated order total exceeds the supported amount. No change made."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        order.lines[index] = oldLine.copy(unitPricePaise = newUnitPricePaise)
        return commitMutation(
            before,
            commandId,
            "Unit price updated for ${oldLine.name}; new order total ${money(updatedTotal.toInt())}.",
            "order.price_adjusted #$number product=$productId old=${oldLine.unitPricePaise}p new=${newUnitPricePaise}p reason=$correctionReason",
            order
        )
    }

    /** Refunds delivered line quantities only. Returnability is shop policy; omitted products are never restocked. */
    fun refundItems(number: Int, items: Map<String,Int>, reason: String, stockDispositionByProduct: Map<String,String>, cashRefundConfirmed: Boolean, commandId: String, expectedRevision: Int? = null): String {
        if (commandId.isBlank()) return "Command ID is required."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (items.isEmpty() || items.values.any { it <= 0 }) return "Choose positive returned quantities. No change made."
        val order = orders[number] ?: return "Order not found."
        val amount = items.entries.sumOf { (productId, quantity) ->
            val line = order.lines.find { it.productId == productId } ?: return "Returned item is not in this order."
            if (quantity > line.deliveredQuantity - line.refundedQuantity) return "Return quantity exceeds delivered, unrefunded items. No change made."
            if (stockDispositionByProduct[productId] !in setOf("restock", "not_restocked")) return "Choose a stock disposition for every returned item."
            if (stockDispositionByProduct[productId] == "restock" && productId !in returnableProducts) return "Shop policy does not allow this product to be restocked. No change made."
            line.unitPricePaise * quantity
        }
        return applyRefund(number, amount, reason, cashRefundConfirmed, commandId, expectedRevision, items, stockDispositionByProduct)
    }

    /** Goodwill or price correction without returning inventory. */
    fun refundAmount(number: Int, amountPaise: Int, reason: String, cashRefundConfirmed: Boolean, commandId: String, expectedRevision: Int? = null): String =
        applyRefund(number, amountPaise, reason, cashRefundConfirmed, commandId, expectedRevision, emptyMap(), emptyMap())

    /** Full remaining delivered-sale reversal. A cash refund needs explicit staff confirmation. */
    fun refundOrder(
        number: Int,
        reason: String,
        stockDisposition: String,
        cashRefundConfirmed: Boolean,
        commandId: String,
        expectedRevision: Int? = null
    ): String {
        if (commandId.isBlank()) return "Command ID is required."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        val order = orders[number] ?: return "Order not found."
        if (order.voided) return "Order is already fully refunded or cancelled. No change made."
        val items = order.lines.mapNotNull { line ->
            val refundable = line.deliveredQuantity - line.refundedQuantity
            if (refundable <= 0) null else line.productId to refundable
        }.toMap()
        val dispositions = items.keys.associateWith { stockDisposition }
        return refundItems(number, items, reason, dispositions, cashRefundConfirmed, commandId, expectedRevision)
    }

    private fun applyRefund(number: Int, amountPaise: Int, reason: String, cashRefundConfirmed: Boolean, commandId: String, expectedRevision: Int?, returnedItems: Map<String,Int>, dispositions: Map<String,String>): String {
        if (commandId.isBlank()) return "Command ID is required."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (reason.isBlank()) return "A correction reason is required."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        val order = orders[number] ?: return "Order not found."
        if (expectedRevision != null && expectedRevision != order.revision) return "Order revision conflict. Refresh and review the order before retrying."
        if (order.fulfillment !in setOf(Fulfillment.GIVEN,Fulfillment.CANCELLED) || order.voided) return "Refunds require a finished order with delivered items. No change made."
        val refundableRemaining = order.salePaise - order.refundedPaise
        if (amountPaise <= 0 || amountPaise > refundableRemaining) return "Refund exceeds delivered, unrefunded sale value. No change made."

        val creditRefund = minOf(amountPaise, order.creditPaise)
        val cashRefund = minOf(amountPaise - creditRefund, order.paidPaise)
        val uncollectedReversal = amountPaise - creditRefund - cashRefund
        if (cashRefund > 0 && !cashRefundConfirmed) return "Confirm the staff-declared cash refund. No change made."
        if (credits.filter { it.orderNumber == number }.sumOf { it.outstandingPaise } < creditRefund) return "Credit ledger cannot support this reversal. No change made."
        val creditCustomerId = credits.firstOrNull { it.orderNumber == number && it.outstandingPaise > 0 }?.customerId

        var creditToAllocate = creditRefund
        for (index in credits.indices) {
            val entry = credits[index]
            if (entry.orderNumber != number || entry.outstandingPaise <= 0 || creditToAllocate == 0) continue
            val allocation = minOf(entry.outstandingPaise, creditToAllocate)
            credits[index] = entry.copy(reversedPaise = entry.reversedPaise + allocation)
            creditToAllocate -= allocation
        }
        check(creditToAllocate == 0) { "Validated credit allocation did not reconcile." }

        val nowReason = reason.trim()
        if (cashRefund > 0) paymentEntries += PaymentEntry("$commandId-cash-refund", number, null, -cashRefund, "cash", "staff_declared_refund", "cash_refund", drawerSessionId = activeCashDrawer()?.sessionId)
        if (creditRefund > 0) paymentEntries += PaymentEntry("$commandId-credit-reversal", number, creditCustomerId, -creditRefund, "udhar", "reversed", "credit_reversal")
        if (order.payment == Payment.UPI_PENDING) {
            val pendingAttempt = paymentEntries.lastOrNull { it.orderNumber == number && it.method == "upi" && it.status == "pending" }
            paymentEntries += PaymentEntry("$commandId-upi-cancel", number, null, 0, "upi", "cancelled", "payment_attempt_cancelled", pendingAttempt?.entryId)
        }
        returnedItems.forEach { (productId, quantity) ->
            val index = order.lines.indexOfFirst { it.productId == productId }
            val line = order.lines[index]
            order.lines[index] = line.copy(refundedQuantity = line.refundedQuantity + quantity)
            if (dispositions[productId] == "restock") {
                stock[productId] = (stock[productId] ?: 0) + quantity
                movements += StockMovement("$commandId:$productId:restock", productId, quantity, "sale_refund_restock", number)
            }
        }
        order.refundedPaise += amountPaise
        order.cashRefundedPaise += cashRefund
        order.creditReversedPaise += creditRefund
        order.paidPaise -= cashRefund
        order.creditPaise -= creditRefund
        order.cancelReason = nowReason
        order.voided = order.refundedPaise >= order.salePaise
        if (order.voided) order.payment = if (cashRefund + creditRefund > 0) Payment.REFUNDED else Payment.CANCELLED
        else updatePaymentState(order)
        if (order.payment == Payment.UPI_PENDING) order.payment = Payment.UNPAID
        val event = "sale.refund #$number reason=$nowReason amount=${amountPaise}p cash=${cashRefund}p credit=${creditRefund}p uncollected=${uncollectedReversal}p items=${returnedItems.entries.joinToString { "${it.key}:${it.value}" }}"
        events += "$commandId $event"
        returnedItems.forEach { (productId, quantity) -> events += "$commandId inventory.return $productId quantity=$quantity disposition=${dispositions[productId]}" }
        return commitMutation(before, commandId, "Refund ${money(amountPaise)} recorded; cash ${money(cashRefund)}, credit reversal ${money(creditRefund)}, uncollected balance reduced ${money(uncollectedReversal)}.", event, order)
    }

    fun recordCash(orderNumber: Int, amountPaise: Int?, commandId: String, expectedRevision: Int? = null): String =
        apply(commandId, ProposedCommand("cash", orderNumber, "Record staff-declared cash", amountPaise = amountPaise, expectedRevision = expectedRevision))

    /** Atomically hands over selected quantities across one or more order lines. */
    fun partialHandover(orderNumber: Int, selectedQuantities: Map<String, Int>, commandId: String, expectedRevision: Int? = null): String {
        if (commandId.isBlank()) return "Command ID is required. No handover recorded."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (selectedQuantities.isEmpty() || selectedQuantities.keys.any(String::isBlank)) return "Select at least one valid item. No handover recorded."
        val order = orders[orderNumber] ?: return "Order not found. No handover recorded."
        if (expectedRevision != null && order.revision != expectedRevision) return "Order revision conflict. Refresh and review the order before retrying."
        if (order.voided || order.fulfillment !in setOf(Fulfillment.READY, Fulfillment.PARTIALLY_GIVEN)) return "Mark the order ready before handover."
        val selected = mutableListOf<Pair<Int, Int>>()
        for ((productId, quantity) in selectedQuantities) {
            val index = order.lines.indexOfFirst { it.productId == productId }
            if (index < 0) return "Selected item is not part of this order. No handover recorded."
            val line = order.lines[index]
            val remaining = line.quantity - line.deliveredQuantity
            if (quantity !in 1..remaining) return "Handover quantity exceeds remaining ${line.name}. No handover recorded."
            if (requireConfiguredProducts && productId !in stockConfirmedProductIds) return "Opening stock is not confirmed for ${line.name}. No handover recorded."
            if ((stock[productId] ?: 0) < quantity) return "Insufficient stock for ${line.name}. No handover or stock movement recorded."
            selected += index to quantity
        }
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        selected.forEach { (index, quantity) ->
            val line = order.lines[index]
            order.lines[index] = line.copy(deliveredQuantity = line.deliveredQuantity + quantity)
            stock[line.productId] = (stock[line.productId] ?: 0) - quantity
            movements += StockMovement("$commandId:${line.productId}", line.productId, -quantity, "partial_handover", order.number)
        }
        val allGiven = order.lines.all { it.deliveredQuantity == it.quantity }
        order.fulfillment = if (allGiven) Fulfillment.GIVEN else Fulfillment.PARTIALLY_GIVEN
        order.salePosted = allGiven
        val summary = selected.joinToString("; ") { (index, quantity) -> "${quantity} × ${order.lines[index].name}" }
        val result = "Handed over $summary · ${if (allGiven) "order complete; sale recorded ${money(order.salePaise)}" else "more items remain"}."
        return commitMutation(before, commandId, result, "order.partial_handover #${order.number} items=${selected.joinToString(",") { (index, quantity) -> "${order.lines[index].productId}:$quantity" }}", order)
    }

    fun recordUpiPending(orderNumber: Int, commandId: String, expectedRevision: Int? = null): String =
        apply(commandId, ProposedCommand("upi", orderNumber, "Record UPI pending", expectedRevision = expectedRevision))

    fun recordUpiReceived(orderNumber: Int, amountPaise: Int?, commandId: String, expectedRevision: Int? = null): String =
        apply(commandId, ProposedCommand("upi_received", orderNumber, "Record staff-declared UPI receipt", amountPaise = amountPaise, expectedRevision = expectedRevision))

    /** Records an operator-confirmed UPI receipt without claiming provider verification. */
    fun recordUpiDirect(orderNumber: Int, amountPaise: Int?, commandId: String, expectedRevision: Int? = null): String =
        apply(commandId, ProposedCommand("upi_direct", orderNumber, "Record direct staff-declared UPI receipt", amountPaise = amountPaise, expectedRevision = expectedRevision))

    fun recordUdhar(orderNumber: Int, customerId: String, commandId: String, amountPaise: Int? = null, expectedRevision: Int? = null): String =
        apply(commandId, ProposedCommand("udhar",orderNumber,"Record customer credit",amountPaise=amountPaise,customerId=customerId,expectedRevision=expectedRevision))

    /** Atomically allocate the entire remaining due between staff-declared cash and one customer's receivable. */
    fun recordCashUdharSplit(orderNumber: Int, cashPaise: Int, customerId: String, commandId: String, expectedRevision: Int? = null): String =
        apply(commandId, ProposedCommand("cash_udhar_split", orderNumber, "Record cash plus Udhar split", amountPaise = cashPaise, customerId = customerId, expectedRevision = expectedRevision))

    /** Atomically records staff-declared cash and an unverified UPI attempt for the remaining due. */
    fun recordCashUpiPendingSplit(orderNumber: Int, cashPaise: Int, commandId: String, expectedRevision: Int? = null): String =
        apply(commandId, ProposedCommand("cash_upi_pending_split", orderNumber, "Record cash plus UPI pending split", amountPaise = cashPaise, expectedRevision = expectedRevision))

    fun repayCredit(customerId: String, amountPaise: Int, commandId: String): String {
        if (commandId.isBlank()) return "Command ID is required."
        if (commandId in appliedCommandIds) return "Duplicate command ignored."
        if (customerId.isBlank() || amountPaise <= 0) return "Customer and positive repayment amount are required."
        val balance=creditBalance(customerId)
        if (amountPaise>balance) return "Repayment exceeds customer balance. No change made."
        val before = mutationSnapshotOrNull() ?: return maintenanceRejection()
        var remaining=amountPaise
        val affected=mutableListOf<CounterOrder>()
        for (index in credits.indices) {
            val entry=credits[index]
            if (entry.customerId!=customerId || entry.outstandingPaise<=0 || remaining==0) continue
            val allocation=minOf(remaining,entry.outstandingPaise)
            credits[index]=entry.copy(repaidPaise=entry.repaidPaise+allocation)
            orders[entry.orderNumber]?.let { order ->
                order.paidPaise+=allocation
                order.creditPaise=(order.creditPaise-allocation).coerceAtLeast(0)
                updatePaymentState(order)
                affected += order
            }
            remaining-=allocation
        }
        repayments += CreditRepayment(commandId,customerId,amountPaise)
        paymentEntries += PaymentEntry(commandId, null, customerId, amountPaise, "cash", "staff_declared", "customer_repayment", drawerSessionId = activeCashDrawer()?.sessionId)
        return commitMutation(before,commandId,"Repayment ${money(amountPaise)} allocated to $customerId.","customer.credit_repaid $customerId ${amountPaise}p",affectedOrders=affected)
    }

    private fun updatePaymentState(order: CounterOrder) {
        if (order.voided) {
            order.payment = Payment.REFUNDED
        } else if (order.duePaise == 0) {
            order.payment = when {
                order.paidPaise > 0 && order.creditPaise > 0 -> Payment.SPLIT
                order.creditPaise > 0 -> Payment.CREDIT
                order.paidPaise > 0 -> Payment.STAFF_DECLARED
                else -> Payment.UNPAID
            }
        } else {
            order.payment = when {
                order.creditPaise > 0 && order.paidPaise > 0 -> Payment.SPLIT
                order.creditPaise > 0 -> Payment.CREDIT
                order.paidPaise > 0 -> Payment.PARTIALLY_PAID
                else -> Payment.UNPAID
            }
        }
    }

    private fun commitMutation(before: CounterSnapshot, commandId: String, result: String, event: String, order: CounterOrder? = null, affectedOrders: List<CounterOrder> = emptyList()): String {
        order?.let { it.revision++ }
        affectedOrders.distinctBy { it.number }.forEach { it.revision++ }
        val audit = "$commandId $event"
        appliedCommandIds += commandId
        events += audit
        val newMovements = movements.drop(before.stockMovements.size)
        val newRepayments = repayments.drop(before.creditRepayments.size)
        val newPaymentEntries = paymentEntries.drop(before.paymentEntries.size)
        return try {
            ensureCustomerSyncIdentities(before)
            val after = snapshot()
            val syncPayload = SyncOutboxPayloadEncoder.between(before, after)
            if (persistence != null && !persistence.commit(after, commandId, result, audit, LocalSessionState.actorId, newMovements, newRepayments, newPaymentEntries, syncPayload)) {
                restore(before)
                "Duplicate command ignored."
            } else result
        } catch (_: BusinessMaintenanceException) {
            restore(before)
            "Backup or recovery is in progress. This action was not recorded; retry when it finishes."
        } catch (error: Exception) {
            restore(before)
            "Could not save this action locally. No change made. (${error.javaClass.simpleName})"
        }
    }

    private fun mutationSnapshotOrNull(): CounterSnapshot? = try { snapshot() } catch (_: BusinessMaintenanceException) { null }

    private fun maintenanceRejection() = "Backup or recovery is in progress. This action was not recorded; retry when it finishes."

    private fun barrierRejectionIfActive(): String? = if (BusinessWriteBarrier.currentMaintenance() == null) null else maintenanceRejection()

    private fun configuredProductsOrNull(): List<TapriProduct>? = try { persistence?.loadProducts().orEmpty() } catch (_: BusinessMaintenanceException) { null }
    private fun snapshot() = CounterSnapshot(
        nextNumber,
        orders.values.map { CounterOrderRecord(it.number, it.lines.toList(), it.fulfillment, it.payment, it.paidPaise, it.salePosted, it.cancelReason, it.revision, it.creditPaise, it.refundedPaise, it.voided, it.cashRefundedPaise, it.creditReversedPaise, it.createdAtEpochMs) },
        stock.toMap(), appliedCommandIds.toSet(), events.toList(), movements.toList(), credits.toList(), repayments.toList(), paymentEntries.toList(), stockConfirmedProductIds.toSet(),
        supplierRecords.toList(), purchaseReceipts.toList(), supplierPayments.toList(), purchaseReturns.toList(), expenseRecords.toList(), cashDrawerSessions.toList(), cashDrawerMovements.toList(), cashClosingReviews.toList(), purchaseOrderRecords.toList(), customerSyncIdentities.toMap(),
        productUnitLabels = products().associate { it.productId to it.unitLabel }
    )

    private fun ensureCustomerSyncIdentities(before: CounterSnapshot) {
        val previousCredits = before.customerCredits.associateBy(CustomerCredit::creditId)
        val changedCredits = credits.filter { previousCredits[it.creditId] != it }
        val changedRepayments = repayments.drop(before.creditRepayments.size)
        val changedPayments = paymentEntries.drop(before.paymentEntries.size)
        val ids = (changedCredits.map { it.customerId } + changedRepayments.map { it.customerId } + changedPayments.mapNotNull { it.customerId })
            .filter(String::isNotBlank).toSet()
        ids.forEach { customerId -> customerSyncIdentities.getOrPut(customerId) { java.util.UUID.randomUUID().toString() } }
    }

    private fun restore(state: CounterSnapshot) {
        orders.clear()
        state.orders.forEach { row -> orders[row.number] = CounterOrder(row.number, row.lines.toMutableList(), row.fulfillment, row.payment, row.paidPaise, row.creditPaise, row.refundedPaise, row.salePosted, row.voided, row.cancelReason, row.revision, row.cashRefundedPaise, row.creditReversedPaise, row.createdAtEpochMs.takeIf { it > 0L } ?: 0L) }
        stock.clear(); stock.putAll(state.stock)
        stockConfirmedProductIds.clear(); stockConfirmedProductIds.addAll(state.stockConfirmedProductIds)
        appliedCommandIds.clear(); appliedCommandIds.addAll(state.commandIds)
        events.clear(); events.addAll(state.auditEvents)
        movements.clear(); movements.addAll(state.stockMovements)
        credits.clear(); credits.addAll(state.customerCredits)
        repayments.clear(); repayments.addAll(state.creditRepayments)
        customerSyncIdentities.clear(); customerSyncIdentities.putAll(state.customerSyncIdentities)
        paymentEntries.clear(); paymentEntries.addAll(state.paymentEntries)
        supplierRecords.clear(); supplierRecords.addAll(state.suppliers)
        purchaseReceipts.clear(); purchaseReceipts.addAll(state.purchaseReceipts)
        purchaseOrderRecords.clear(); purchaseOrderRecords.addAll(state.purchaseOrderDrafts)
        supplierPayments.clear(); supplierPayments.addAll(state.supplierPayments)
        purchaseReturns.clear(); purchaseReturns.addAll(state.purchaseReturns)
        expenseRecords.clear(); expenseRecords.addAll(state.expenses)
        cashDrawerSessions.clear(); cashDrawerSessions.addAll(state.cashDrawerSessions)
        cashDrawerMovements.clear(); cashDrawerMovements.addAll(state.cashDrawerMovements)
        cashClosingReviews.clear(); cashClosingReviews.addAll(state.cashClosingReviews)
        nextNumber = state.nextNumber
    }

    override fun close() { persistence?.close() }

    private fun line(id:String,quantity:Int): CounterLine? {
        if(quantity !in 1..99) return null
        if (requireConfiguredProducts) {
            val product = configuredProductsOrNull()?.firstOrNull { it.productId == id && it.active && it.priceConfirmed } ?: return null
            return CounterLine(product.productId, product.name, quantity, product.unitPricePaise, estimatedUnitCostPaise = product.estimatedUnitCostPaise)
        }
        val (name,price)=when(id){ "cutting_chai"->"Cutting Chai" to 1000; "chai"->"Regular Chai" to 1500; "samosa"->"Samosa" to 2000; "coffee"->"Coffee" to 2500; else->return null }
        return CounterLine(id,name,quantity,price)
    }
    private fun productFor(text:String): Pair<String,String>? {
        if (requireConfiguredProducts) {
            return configuredProductsOrNull()?.asSequence()?.filter { it.active && it.priceConfirmed }?.flatMap { product ->
                (product.aliases + product.name).asSequence().map { alias -> normalize(alias) to product }
            }?.filter { (alias, _) -> alias.isNotBlank() && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{N}])").containsMatchIn(text) }
                ?.maxByOrNull { it.first.length }?.second?.let { it.productId to it.name } ?: return null
        }
        return when {
        Regex("\\b(cutting chai|cutting)\\b").containsMatchIn(text)->"cutting_chai" to "Cutting Chai"
        Regex("\\b(samosa)\\b").containsMatchIn(text)->"samosa" to "Samosa"
        Regex("\\b(coffee|kaapi)\\b").containsMatchIn(text)->"coffee" to "Coffee"
        Regex("\\b(chai)\\b").containsMatchIn(text)->"chai" to "Regular Chai"
        else->null
        }
    }
    private fun quantityBefore(text:String,productId:String):Int {
        if (requireConfiguredProducts) {
            val product = persistence?.loadProducts()?.firstOrNull { it.productId == productId && it.active && it.priceConfirmed }
            val matchedAlias = product?.let { p -> (p.aliases + p.name).map(::normalize).filter(String::isNotBlank)
                .filter { alias -> Regex("(?<![\\p{L}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{N}])").containsMatchIn(text) }
                .maxByOrNull(String::length) }
            val prefix = matchedAlias?.let { text.substringBefore(it).trim().split(Regex("\\s+" )).lastOrNull() }
            return when(prefix) { "do","don","dono","two" -> 2; "teen","three" -> 3; "chaar","four" -> 4; "paanch","five" -> 5; else -> prefix?.toIntOrNull()?.takeIf { it in 1..99 } ?: 1 }
        }
        val productName=when(productId){"cutting_chai"->"cutting chai";"chai"->"chai";"samosa"->"samosa";else->"coffee"}
        val prefix=text.substringBefore(productName).trim().split(Regex("\\s+")).lastOrNull()
        return when(prefix){"do","don","dono","two"->2;"teen","three"->3;"chaar","four"->4;"paanch","five"->5;else->1}
    }
    private fun normalize(input:String):String {
        val digits=mapOf('०' to '0','१' to '1','२' to '2','३' to '3','४' to '4','५' to '5','६' to '6','७' to '7','८' to '8','९' to '9')
        val words=mapOf("ऑर्डर" to "order","आर्डर" to "order","नया" to "naya","नवीन" to "nava","दो" to "do","दोन" to "don","एक" to "ek","एका" to "eka","तीन" to "teen","कटिंग" to "cutting","चाय" to "chai","चहा" to "chai","समोसा" to "samosa","तैयार" to "ready","तयार" to "ready","है" to "hai","आहे" to "hai","दिया" to "de diya","दिला" to "de diya","कॅश" to "cash","कैश" to "cash","मिला" to "mila","मिळाले" to "mila","पन्नास" to "50","पचास" to "50","pachaas" to "50","pachas" to "50","बारा" to "12","बारह" to "12","यूपीआई" to "upi","युपीआई" to "upi","पेमेंट" to "payment","बाकी" to "pending","रुपये" to "rupees","रुपया" to "rupee","काढून" to "remove","टाक" to "remove","हटाओ" to "remove","hatao" to "remove")
        return input.lowercase().map { digits[it] ?: it }.joinToString("").replace(Regex("[^\\p{L}\\p{M}\\p{N}#\\s]")," ").split(Regex("\\s+")).filter(String::isNotBlank).joinToString(" "){words[it]?:it}.trim()
    }
    private fun money(paise:Int) = money(paise.toLong())
    private fun money(paise:Long): String {
        val absolute = kotlin.math.abs(paise)
        return "${if (paise < 0) "-" else ""}₹${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
    }
}
