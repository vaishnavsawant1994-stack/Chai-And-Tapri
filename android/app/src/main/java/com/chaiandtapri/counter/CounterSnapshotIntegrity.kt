package com.chaiandtapri.counter

import java.util.Locale

/** Read-only business reconciliation. It reports contradictions and never repairs stored records. */
internal object CounterSnapshotIntegrity {
    fun requireConsistent(snapshot: CounterSnapshot) {
        val problems = mutableListOf<String>()
        val byNumber = snapshot.orders.associateBy { it.number }
        if (byNumber.size != snapshot.orders.size) problems += "duplicate order number"
        val creditsByOrder = snapshot.customerCredits.groupBy { it.orderNumber }
        val cashRefundsByOrder = snapshot.paymentEntries.filter { it.kind == "cash_refund" }
            .groupBy { it.orderNumber }.mapValues { (_, entries) -> entries.sumOf { -it.amountPaise } }
        val reversalsByOrder = snapshot.customerCredits.groupBy { it.orderNumber }
            .mapValues { (_, entries) -> entries.sumOf { it.reversedPaise } }
        snapshot.customerCredits.forEach { credit ->
            if (credit.orderNumber !in byNumber) problems += "credit references missing order ${credit.orderNumber}"
            if (credit.amountPaise < 0 || credit.repaidPaise < 0 || credit.reversedPaise < 0 || credit.outstandingPaise < 0) problems += "invalid receivable for order ${credit.orderNumber}"
        }
        snapshot.orders.forEach { order ->
            val deliveredValue = order.lines.sumOf { line -> line.deliveredQuantity.toLong() * line.unitPricePaise }
            if (order.lines.any { it.quantity <= 0 || it.deliveredQuantity !in 0..it.quantity || it.refundedQuantity !in 0..it.deliveredQuantity || it.unitPricePaise < 0 }) {
                problems += "invalid line quantity or price for order ${order.number}"
            }
            if (order.paidPaise < 0 || order.creditPaise < 0 || order.refundedPaise < 0 || order.cashRefundedPaise < 0 || order.creditReversedPaise < 0) {
                problems += "negative financial projection for order ${order.number}"
            }
            if (order.paidPaise.toLong() + order.creditPaise + order.refundedPaise > deliveredValue) problems += "order ${order.number} is over-allocated"
            val ledgerReceivablePaise = creditsByOrder[order.number].orEmpty().sumOf { it.outstandingPaise }
            if (ledgerReceivablePaise != order.creditPaise) problems += "order ${order.number} receivable projection ${order.creditPaise}p differs from credit ledger ${ledgerReceivablePaise}p"
            if ((reversalsByOrder[order.number] ?: 0) != order.creditReversedPaise) problems += "order ${order.number} credit reversal differs from ledger"
            if ((cashRefundsByOrder[order.number] ?: 0) != order.cashRefundedPaise) problems += "order ${order.number} cash refund differs from payment ledger"
            if (order.payment == Payment.UPI_PENDING && snapshot.paymentEntries.none { it.orderNumber == order.number && it.method == "upi" && it.status == "pending" }) {
                problems += "order ${order.number} is UPI-pending without a pending payment event"
            }
        }
        if (snapshot.paymentEntries.map { it.entryId }.toSet().size != snapshot.paymentEntries.size) problems += "duplicate payment event ID"
        if (snapshot.stock.values.any { it < 0 }) problems += "negative stock balance"
        if (snapshot.stockConfirmedProductIds.any { it !in snapshot.stock }) problems += "confirmed stock references a missing product balance"
        val supplierIds = snapshot.suppliers.map { it.supplierId }
        val receiptIds = snapshot.purchaseReceipts.map { it.receiptId }
        val purchaseOrderIds = snapshot.purchaseOrderDrafts.map { it.purchaseOrderId }
        if (supplierIds.toSet().size != supplierIds.size) problems += "duplicate supplier ID"
        if (receiptIds.toSet().size != receiptIds.size) problems += "duplicate purchase receipt ID"
        if (purchaseOrderIds.toSet().size != purchaseOrderIds.size) problems += "duplicate purchase order ID"
        if (snapshot.supplierPayments.map { it.paymentId }.toSet().size != snapshot.supplierPayments.size) problems += "duplicate supplier payment ID"
        if (snapshot.purchaseReturns.map { it.returnId }.toSet().size != snapshot.purchaseReturns.size) problems += "duplicate purchase return ID"
        if (snapshot.expenses.map { it.expenseId }.toSet().size != snapshot.expenses.size) problems += "duplicate expense ID"
        if (snapshot.cashDrawerSessions.map { it.sessionId }.toSet().size != snapshot.cashDrawerSessions.size) problems += "duplicate cash drawer session ID"
        if (snapshot.cashDrawerSessions.count { it.closedAt == null } > 1) problems += "multiple active cash drawer sessions"
        val drawerIds = snapshot.cashDrawerSessions.map { it.sessionId }.toSet()
        snapshot.cashDrawerSessions.forEach { session ->
            if (session.openingCashPaise < 0 || session.openedAt <= 0 || (session.closedAt == null) != (session.closingCashPaise == null) || session.closingCashPaise?.let { it < 0 } == true || session.closedAt?.let { it < session.openedAt } == true) problems += "cash drawer session ${session.sessionId} has invalid fields"
            if (session.staffLabel?.let { it.trim().length !in 2..60 } == true || session.handoverToLabel?.let { it.trim().length !in 2..60 } == true || session.handoverNote.length > 160) problems += "cash drawer session ${session.sessionId} has invalid shift handover fields"
            if (session.staffLabel == null && (session.handoverToLabel != null || session.handoverNote.isNotEmpty())) problems += "cash drawer session ${session.sessionId} has handover details without a shift label"
        }
        if (snapshot.cashDrawerMovements.map { it.movementId }.toSet().size != snapshot.cashDrawerMovements.size) problems += "duplicate cash drawer movement ID"
        snapshot.cashDrawerMovements.forEach { movement ->
            if (movement.sessionId !in drawerIds || movement.kind !in setOf("cash_in", "cash_out") || movement.amountPaise <= 0 || movement.note.length !in 3..120) problems += "cash drawer movement ${movement.movementId} has invalid fields"
        }
        if (snapshot.cashClosingReviews.map { it.reviewId }.toSet().size != snapshot.cashClosingReviews.size || snapshot.cashClosingReviews.map { it.sessionId }.toSet().size != snapshot.cashClosingReviews.size) problems += "duplicate cash closing review"
        snapshot.cashClosingReviews.forEach { review ->
            if (review.sessionId !in drawerIds || snapshot.cashDrawerSessions.none { it.sessionId == review.sessionId && it.closedAt != null } || review.reviewedAt <= 0 || review.note.length > 240) problems += "cash closing review ${review.reviewId} has invalid fields or references an open/missing session"
        }
        if (snapshot.paymentEntries.any { it.drawerSessionId != null && it.drawerSessionId !in drawerIds } || snapshot.expenses.any { it.drawerSessionId != null && it.drawerSessionId !in drawerIds } || snapshot.supplierPayments.any { it.drawerSessionId != null && it.drawerSessionId !in drawerIds }) problems += "cash event references a missing drawer session"
        snapshot.expenses.forEach { expense ->
            if (expense.expenseId.isBlank() || expense.category.length !in 2..40 || !expense.category.matches(Regex("[a-z][a-z0-9 _-]*")) || expense.amountPaise !in 1..100_000_000 || expense.method !in setOf("cash", "upi", "card") || expense.status != "staff_declared" || expense.note.length > 160 || expense.recordedAtEpochMs?.let { it <= 0 } == true) {
                problems += "expense ${expense.expenseId} has invalid fields or unsupported evidence status"
            }
        }
        val suppliers = supplierIds.toSet()
        val receiptsBySupplier = snapshot.purchaseReceipts.groupBy { it.supplierId }
        val paymentsBySupplier = snapshot.supplierPayments.groupBy { it.supplierId }
        val returnsBySupplier = snapshot.purchaseReturns.groupBy { it.supplierId }
        val receiptsById = snapshot.purchaseReceipts.associateBy { it.receiptId }
        val purchaseOrdersById = snapshot.purchaseOrderDrafts.associateBy { it.purchaseOrderId }
        snapshot.purchaseOrderDrafts.forEach { order ->
            if (order.purchaseOrderId.isBlank() || order.supplierId !in suppliers || order.lines.isEmpty() ||
                order.lines.map { it.productId }.toSet().size != order.lines.size || order.revision < 0 || order.createdAtEpochMs < 0L) {
                problems += "purchase order ${order.purchaseOrderId} has invalid identity, supplier, lines, or revision"
            }
            if (order.lines.any { it.productId !in snapshot.stock || it.orderedQuantity !in 1..100_000 || it.unitCostPaise !in 1..50_000_000 }) {
                problems += "purchase order ${order.purchaseOrderId} has an invalid line"
            }
            if (order.totalPaise !in 1..Int.MAX_VALUE.toLong()) problems += "purchase order ${order.purchaseOrderId} estimate is outside the supported range"
        }
        snapshot.purchaseReceipts.forEach { receipt ->
            val invoiceReference = receipt.supplierInvoiceReference
            if (invoiceReference != null && (invoiceReference != invoiceReference.trim() || invoiceReference.length !in 1..80 || invoiceReference.any { it == '\n' || it == '\r' || it.isISOControl() })) {
                problems += "purchase receipt ${receipt.receiptId} has an invalid supplier invoice reference"
            }
            if (receipt.supplierId !in suppliers) problems += "purchase receipt references missing supplier ${receipt.supplierId}"
            val linkedOrder = receipt.purchaseOrderId?.let(purchaseOrdersById::get)
            if (receipt.purchaseOrderId != null && (linkedOrder == null || linkedOrder.supplierId != receipt.supplierId || linkedOrder.status != PurchaseOrderStatus.SENT)) {
                problems += "purchase receipt ${receipt.receiptId} references a missing, mismatched, or cancelled purchase order"
            }
            if (receipt.lines.isEmpty() || receipt.lines.map { it.productId }.toSet().size != receipt.lines.size) problems += "purchase receipt ${receipt.receiptId} has no lines or duplicate products"
            if (receipt.lines.any { it.quantity !in 1..100_000 || it.unitCostPaise !in 1..50_000_000 || it.productId !in snapshot.stock }) problems += "purchase receipt ${receipt.receiptId} has an invalid line"
            if (receipt.totalPaise != receipt.lines.sumOf { it.lineCostPaise } || receipt.totalPaise !in 1..Int.MAX_VALUE.toLong()) problems += "purchase receipt ${receipt.receiptId} total does not match its lines"
            receipt.lines.forEach { line ->
                if (line.productId !in snapshot.stockConfirmedProductIds) problems += "purchase receipt ${receipt.receiptId} affects stock without a confirmed baseline"
                if (snapshot.stockMovements.none { it.movementId == "${receipt.receiptId}:${line.productId}:purchase" && it.quantityDelta == line.quantity }) {
                    problems += "purchase receipt ${receipt.receiptId} has no matching stock movement for ${line.productId}"
                }
            }
        }
        snapshot.purchaseReceipts.filter { it.supplierInvoiceReference != null }
            .groupBy { it.supplierId to it.supplierInvoiceReference!!.lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .keys.forEach { (supplierId, reference) -> problems += "supplier $supplierId has duplicate invoice reference $reference" }
        snapshot.supplierPayments.forEach { payment ->
            if (payment.supplierId !in suppliers) problems += "supplier payment references missing supplier ${payment.supplierId}"
            if (payment.amountPaise <= 0 || payment.method !in setOf("cash", "upi") || payment.status != "staff_declared") problems += "supplier payment has an invalid amount, method, or unsupported evidence status"
        }
        snapshot.purchaseReturns.forEach { purchaseReturn ->
            val receipt = receiptsById[purchaseReturn.receiptId]
            if (purchaseReturn.supplierId !in suppliers || receipt?.supplierId != purchaseReturn.supplierId) problems += "purchase return references a missing or mismatched supplier receipt"
            if (purchaseReturn.lines.isEmpty() || purchaseReturn.lines.map { it.productId }.toSet().size != purchaseReturn.lines.size) problems += "purchase return ${purchaseReturn.returnId} has no lines or duplicate products"
            if (purchaseReturn.totalPaise != purchaseReturn.lines.sumOf { it.lineValuePaise } || purchaseReturn.totalPaise !in 1..Int.MAX_VALUE.toLong()) problems += "purchase return ${purchaseReturn.returnId} total does not match its lines"
            purchaseReturn.lines.forEach { line ->
                val original = receipt?.lines?.firstOrNull { it.productId == line.productId }
                if (line.quantity !in 1..100_000 || line.unitCostPaise <= 0 || line.productId !in snapshot.stock || original?.unitCostPaise != line.unitCostPaise) problems += "purchase return ${purchaseReturn.returnId} has an invalid line"
                if (line.productId !in snapshot.stockConfirmedProductIds) problems += "purchase return ${purchaseReturn.returnId} affects stock without a confirmed baseline"
                if (snapshot.stockMovements.none { it.movementId == "${purchaseReturn.returnId}:${line.productId}:purchase_return" && it.quantityDelta == -line.quantity }) problems += "purchase return ${purchaseReturn.returnId} has no matching stock movement"
            }
        }
        snapshot.purchaseReturns.groupBy { it.receiptId }.forEach { (receiptId, returns) ->
            val receipt = receiptsById[receiptId]
            val returned = returns.flatMap { it.lines }.groupBy { it.productId }.mapValues { (_, lines) -> lines.sumOf { it.quantity } }
            returned.forEach { (productId, quantity) ->
                if (quantity > (receipt?.lines?.firstOrNull { it.productId == productId }?.quantity ?: 0)) problems += "purchase returns exceed received quantity"
            }
        }
        snapshot.purchaseReceipts.filter { it.purchaseOrderId != null }.groupBy { it.purchaseOrderId!! }.forEach { (orderId, receipts) ->
            val order = purchaseOrdersById[orderId] ?: return@forEach
            val received = receipts.flatMap { it.lines }.groupBy { it.productId }.mapValues { (_, lines) -> lines.sumOf { it.quantity } }
            received.forEach { (productId, quantity) ->
                if (quantity > (order.lines.firstOrNull { it.productId == productId }?.orderedQuantity ?: 0)) problems += "purchase order $orderId receipts exceed the ordered quantity"
            }
        }
        suppliers.forEach { supplierId ->
            val purchases = receiptsBySupplier[supplierId].orEmpty().sumOf { it.totalPaise }
            val payments = paymentsBySupplier[supplierId].orEmpty().sumOf { it.amountPaise.toLong() }
            val returns = returnsBySupplier[supplierId].orEmpty().sumOf { it.totalPaise }
            if (payments + returns > purchases) problems += "supplier $supplierId payments and returns exceed recorded receipts"
        }
        check(problems.isEmpty()) { "Business reconciliation failed: ${problems.joinToString("; ")}" }
    }
}
