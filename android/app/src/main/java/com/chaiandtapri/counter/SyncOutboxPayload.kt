package com.chaiandtapri.counter

/** Small allow-listed projection delta. Customer financial records use opaque IDs only. */
data class SyncOutboxPayload(val version: Int?, val json: String?, val blockedReason: String?) {
    val sendable: Boolean get() = version != null && json != null && blockedReason == null
}

object SyncOutboxPayloadEncoder {
    /** Owner-only catalogue upsert; the server separately enforces role and unit-history constraints. */
    fun catalogueProduct(product: TapriProduct): SyncOutboxPayload {
        val json = buildString {
            append('{'); field("kind", "projection_delta.v1"); append(',')
            append("\"orders\":[],\"stock\":[],\"stockMovements\":[],\"customerCredits\":[],\"repayments\":[],\"paymentEntries\":[],")
            append("\"catalogue\":[{")
            field("productId", product.productId); append(','); field("name", product.name); append(',')
            field("category", product.category); append(','); number("unitPricePaise", product.unitPricePaise); append(',')
            bool("active", product.active); append(','); bool("favorite", product.favorite); append(',')
            bool("priceConfirmed", product.priceConfirmed); append(','); append("\"aliases\":")
            array(product.aliases) { append(quoted(it)) }; append(',')
            nullableNumber("reorderThreshold", product.reorderThreshold); append(',')
            nullableNumber("estimatedUnitCostPaise", product.estimatedUnitCostPaise); append(',')
            nullableField("scanCode", product.scanCode?.trim()?.takeIf(String::isNotEmpty)); append(',')
            field("unitLabel", product.unitLabel); append(','); field("purchaseUnitLabel", product.purchaseUnitLabel); append(',')
            number("baseUnitsPerPurchaseUnit", product.baseUnitsPerPurchaseUnit); append("}]}")
        }
        return SyncOutboxPayload(version = 1, json = json, blockedReason = null)
    }

    fun between(before: CounterSnapshot, after: CounterSnapshot): SyncOutboxPayload {
        val unsupported = before.suppliers != after.suppliers ||
            before.cashClosingReviews != after.cashClosingReviews
        if (unsupported) return SyncOutboxPayload(null, null, "entity_contract_pending")

        val orders = changedBy(before.orders, after.orders, CounterOrderRecord::number)
        val stockIds = (before.stock.keys + after.stock.keys).toSortedSet()
        val stock = stockIds.filter { before.stock[it] != after.stock[it] }.map { it to after.stock[it] }
        val movements = after.stockMovements.drop(before.stockMovements.size)
        val credits = changedBy(before.customerCredits, after.customerCredits, CustomerCredit::creditId)
        val repayments = after.creditRepayments.drop(before.creditRepayments.size)
        val payments = after.paymentEntries.drop(before.paymentEntries.size)
        val supplierSyncIds = after.suppliers.associate { it.supplierId to it.syncId }
        val receipts = after.purchaseReceipts.drop(before.purchaseReceipts.size)
        val supplierPayments = after.supplierPayments.drop(before.supplierPayments.size)
        val purchaseReturns = after.purchaseReturns.drop(before.purchaseReturns.size)
        val previousShifts = before.cashDrawerSessions.associateBy(CashDrawerSession::sessionId)
        val shifts = after.cashDrawerSessions.mapNotNull { session ->
            val old = previousShifts[session.sessionId]
            val oldExpected = old?.let { expectedCash(before, it.sessionId) }
            val expected = expectedCash(after, session.sessionId) ?: return@mapNotNull null
            val syncProjectionUnchanged = old != null && old.openedAt == session.openedAt &&
                old.openingCashPaise == session.openingCashPaise && old.closedAt == session.closedAt &&
                old.closingCashPaise == session.closingCashPaise && (old.staffLabel == null) == (session.staffLabel == null) &&
                oldExpected == expected
            if (syncProjectionUnchanged) null else session to expected
        }
        val customerSyncIds = after.customerSyncIdentities
        val productUnitLabels = after.productUnitLabels
        if ((receipts.map { it.supplierId } + supplierPayments.map { it.supplierId } + purchaseReturns.map { it.supplierId })
                .any { supplierId -> supplierSyncIds[supplierId]?.isOpaqueUuid() != true } ||
            receipts.any { !it.syncId.isOpaqueUuid() } || supplierPayments.any { !it.syncId.isOpaqueUuid() } || purchaseReturns.any { !it.syncId.isOpaqueUuid() }) {
            return SyncOutboxPayload(null, null, "opaque_supplier_sync_identity_required")
        }
        val previousExpenses = before.expenses.associateBy(BusinessExpense::expenseId)
        val expenses = after.expenses.filter { expense ->
            val previous = previousExpenses[expense.expenseId]
            previous == null || previous.category != expense.category || previous.amountPaise != expense.amountPaise ||
                previous.method != expense.method || previous.status != expense.status
        }
        val changedCustomerIds = (credits.map { it.customerId } + repayments.map { it.customerId } + payments.mapNotNull { it.customerId }).toSet()
        if (changedCustomerIds.any { localId -> customerSyncIds[localId]?.isOpaqueUuid() != true } ||
            credits.any { !it.syncId.isOpaqueUuid() } || repayments.any { !it.syncId.isOpaqueUuid() } || payments.any { !it.syncId.isOpaqueUuid() }) {
            return SyncOutboxPayload(null, null, "opaque_customer_sync_identity_required")
        }
        if (orders.isEmpty() && stock.isEmpty() && movements.isEmpty() && credits.isEmpty() && repayments.isEmpty() && payments.isEmpty() && expenses.isEmpty() && receipts.isEmpty() && supplierPayments.isEmpty() && purchaseReturns.isEmpty() && shifts.isEmpty()) {
            return SyncOutboxPayload(null, null, "empty_delta")
        }

        val json = buildString {
            append('{'); field("kind", "projection_delta.v1"); append(',')
            append("\"orders\":"); array(orders) { order ->
                append('{'); number("orderNumber", order.number); append(','); field("fulfillment", order.fulfillment.name); append(',')
                field("payment", order.payment.name); append(','); number("paidPaise", order.paidPaise); append(',')
                number("creditPaise", order.creditPaise); append(','); number("refundedPaise", order.refundedPaise); append(',')
                number("cashRefundedPaise", order.cashRefundedPaise); append(','); number("creditReversedPaise", order.creditReversedPaise); append(',')
                bool("salePosted", order.salePosted); append(','); bool("voided", order.voided); append(',')
                number("revision", order.revision); append(','); long("createdAtEpochMs", order.createdAtEpochMs); append(',')
                append("\"lines\":"); array(order.lines) { line ->
                    append('{'); field("productId", line.productId); append(','); number("quantity", line.quantity); append(',')
                    number("deliveredQuantity", line.deliveredQuantity); append(','); number("refundedQuantity", line.refundedQuantity); append(',')
                    number("unitPricePaise", line.unitPricePaise); append(','); nullableNumber("estimatedUnitCostPaise", line.estimatedUnitCostPaise)
                    productUnitLabels[line.productId]?.let { label -> append(','); field("unitLabel", label) }
                    append('}')
                }
                append('}')
            }
            append(','); append("\"stock\":"); array(stock) { (id, quantity) ->
                append('{'); field("productId", id); append(',')
                if (quantity == null) append("\"quantity\":null") else number("quantity", quantity)
                productUnitLabels[id]?.let { label -> append(','); field("unitLabel", label) }
                append('}')
            }
            append(','); append("\"stockMovements\":"); array(movements) { movement ->
                append('{'); field("movementId", movement.movementId); append(','); field("productId", movement.productId); append(',')
                number("quantityDelta", movement.quantityDelta); append(','); nullableNumber("orderNumber", movement.orderNumber)
                productUnitLabels[movement.productId]?.let { label -> append(','); field("unitLabel", label) }
                append('}')
            }
            append(','); append("\"customerCredits\":"); array(credits) { credit ->
                append('{'); field("creditId", credit.syncId); append(','); field("customerId", customerSyncIds.getValue(credit.customerId)); append(',')
                number("amountPaise", credit.amountPaise); append(',')
                number("repaidPaise", credit.repaidPaise); append(','); number("reversedPaise", credit.reversedPaise); append('}')
            }
            append(','); append("\"repayments\":"); array(repayments) { repayment ->
                append('{'); field("repaymentId", repayment.syncId); append(','); field("customerId", customerSyncIds.getValue(repayment.customerId)); append(',')
                number("amountPaise", repayment.amountPaise); append('}')
            }
            append(','); append("\"paymentEntries\":"); array(payments) { payment ->
                append('{'); field("entryId", payment.syncId); append(','); nullableNumber("orderNumber", payment.orderNumber); append(',')
                nullableField("customerId", payment.customerId?.let(customerSyncIds::get)); append(','); number("amountPaise", payment.amountPaise); append(',')
                field("method", payment.method); append(','); field("status", payment.status); append(','); field("kind", payment.kind); append(',')
                nullableField("relatedEntryId", payment.relatedEntryId?.let { localId -> after.paymentEntries.firstOrNull { it.entryId == localId }?.syncId }); append('}')
            }
            append(','); append("\"expenses\":"); array(expenses) { expense ->
                append('{'); field("expenseId", expense.expenseId); append(','); field("category", expense.category); append(',')
                number("amountPaise", expense.amountPaise); append(','); field("method", expense.method); append(',')
                field("status", expense.status); append('}')
            }
            append(','); append("\"purchaseReceipts\":"); array(receipts) { receipt ->
                append('{'); field("receiptId", receipt.syncId); append(',')
                field("supplierId", supplierSyncIds.getValue(receipt.supplierId)); append(',')
                long("totalPaise", receipt.totalPaise); append('}')
            }
            append(','); append("\"supplierPayments\":"); array(supplierPayments) { payment ->
                append('{'); field("paymentId", payment.syncId); append(',')
                field("supplierId", supplierSyncIds.getValue(payment.supplierId)); append(',')
                number("amountPaise", payment.amountPaise); append(','); field("method", payment.method); append(',')
                field("status", payment.status); append('}')
            }
            append(','); append("\"purchaseReturns\":"); array(purchaseReturns) { purchaseReturn ->
                val receiptSyncId = after.purchaseReceipts.firstOrNull { it.receiptId == purchaseReturn.receiptId }?.syncId
                    ?: return SyncOutboxPayload(null, null, "purchase_return_receipt_sync_identity_missing")
                if (!receiptSyncId.isOpaqueUuid()) return SyncOutboxPayload(null, null, "purchase_return_receipt_sync_identity_missing")
                append('{'); field("returnId", purchaseReturn.syncId); append(',')
                field("supplierId", supplierSyncIds.getValue(purchaseReturn.supplierId)); append(',')
                field("receiptId", receiptSyncId); append(','); long("amountPaise", purchaseReturn.totalPaise); append('}')
            }
            append(','); append("\"staffShifts\":"); array(shifts) { (session, expected) ->
                val stableId = java.util.UUID.nameUUIDFromBytes(session.sessionId.toByteArray(Charsets.UTF_8)).toString()
                append('{'); field("shiftId", stableId); append(',')
                field("sessionType", if (session.staffLabel == null) "cash_drawer" else "staff_shift"); append(',')
                long("openedAtEpochMs", session.openedAt); append(','); nullableLong("closedAtEpochMs", session.closedAt); append(',')
                number("openingCashPaise", session.openingCashPaise); append(','); long("expectedCashPaise", expected); append(',')
                nullableNumber("closingCashPaise", session.closingCashPaise); append('}')
            }
            append('}')
        }
        return SyncOutboxPayload(version = 1, json = json, blockedReason = null)
    }

    private fun <T, K> changedBy(before: List<T>, after: List<T>, key: (T) -> K): List<T> {
        val previous = before.associateBy(key)
        return after.filter { previous[key(it)] != it }
    }

    private inline fun <T> StringBuilder.array(values: List<T>, write: StringBuilder.(T) -> Unit) {
        append('['); values.forEachIndexed { index, value -> if (index > 0) append(','); write(value) }; append(']')
    }

    private fun StringBuilder.field(name: String, value: String) { append(quoted(name)).append(':').append(quoted(value)) }
    private fun StringBuilder.nullableField(name: String, value: String?) { append(quoted(name)).append(':').append(value?.let(::quoted) ?: "null") }
    private fun StringBuilder.number(name: String, value: Int) { append(quoted(name)).append(':').append(value) }
    private fun StringBuilder.long(name: String, value: Long) { append(quoted(name)).append(':').append(value) }
    private fun StringBuilder.nullableLong(name: String, value: Long?) { append(quoted(name)).append(':').append(value?.toString() ?: "null") }
    private fun StringBuilder.nullableNumber(name: String, value: Int?) { append(quoted(name)).append(':').append(value?.toString() ?: "null") }
    private fun StringBuilder.bool(name: String, value: Boolean) { append(quoted(name)).append(':').append(value) }

    private fun String.isOpaqueUuid(): Boolean = runCatching { java.util.UUID.fromString(this).toString() == this }.getOrDefault(false)

    private fun expectedCash(snapshot: CounterSnapshot, sessionId: String): Long? {
        val session = snapshot.cashDrawerSessions.firstOrNull { it.sessionId == sessionId } ?: return null
        val collections = snapshot.paymentEntries.filter { it.drawerSessionId == sessionId && it.method == "cash" && it.status in setOf("staff_declared", "staff_declared_refund") }.sumOf { it.amountPaise.toLong() }
        val expenses = snapshot.expenses.filter { it.drawerSessionId == sessionId && it.method == "cash" && it.status == "staff_declared" }.sumOf { it.amountPaise.toLong() }
        val supplierOut = snapshot.supplierPayments.filter { it.drawerSessionId == sessionId && it.method == "cash" && it.status == "staff_declared" }.sumOf { it.amountPaise.toLong() }
        val adjustments = snapshot.cashDrawerMovements.filter { it.sessionId == sessionId }.sumOf { if (it.kind == "cash_in") it.amountPaise.toLong() else -it.amountPaise.toLong() }
        return session.openingCashPaise.toLong() + collections - expenses - supplierOut + adjustments
    }

    private fun quoted(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
