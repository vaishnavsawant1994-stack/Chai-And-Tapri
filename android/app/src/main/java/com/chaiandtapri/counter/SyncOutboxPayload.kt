package com.chaiandtapri.counter

/** Small allow-listed projection delta. Customer-linked credit/repayments are blocked until IDs are opaque and privacy-reviewed. */
data class SyncOutboxPayload(val version: Int?, val json: String?, val blockedReason: String?) {
    val sendable: Boolean get() = version != null && json != null && blockedReason == null
}

object SyncOutboxPayloadEncoder {
    fun between(before: CounterSnapshot, after: CounterSnapshot): SyncOutboxPayload {
        val unsupported = before.suppliers != after.suppliers ||
            before.purchaseReceipts != after.purchaseReceipts ||
            before.supplierPayments != after.supplierPayments ||
            before.purchaseReturns != after.purchaseReturns ||
            before.cashDrawerSessions != after.cashDrawerSessions ||
            before.cashDrawerMovements != after.cashDrawerMovements ||
            before.cashClosingReviews != after.cashClosingReviews
        if (unsupported) return SyncOutboxPayload(null, null, "entity_contract_pending")

        val orders = changedBy(before.orders, after.orders, CounterOrderRecord::number)
        val stockIds = (before.stock.keys + after.stock.keys).toSortedSet()
        val stock = stockIds.filter { before.stock[it] != after.stock[it] }.map { it to after.stock[it] }
        val movements = after.stockMovements.drop(before.stockMovements.size)
        val credits = changedBy(before.customerCredits, after.customerCredits, CustomerCredit::creditId)
        val repayments = after.creditRepayments.drop(before.creditRepayments.size)
        val payments = after.paymentEntries.drop(before.paymentEntries.size)
        val previousExpenses = before.expenses.associateBy(BusinessExpense::expenseId)
        val expenses = after.expenses.filter { expense ->
            val previous = previousExpenses[expense.expenseId]
            previous == null || previous.category != expense.category || previous.amountPaise != expense.amountPaise ||
                previous.method != expense.method || previous.status != expense.status
        }
        // Customer IDs are currently operator-defined strings and may be names or phone numbers.
        // Keep customer-linked financial mutations local until the sync identity/consent contract is defined.
        if (credits.isNotEmpty() || repayments.isNotEmpty() || payments.any { it.customerId != null }) {
            return SyncOutboxPayload(null, null, "customer_identity_contract_pending")
        }
        if (orders.isEmpty() && stock.isEmpty() && movements.isEmpty() && credits.isEmpty() && repayments.isEmpty() && payments.isEmpty() && expenses.isEmpty()) {
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
                    number("unitPricePaise", line.unitPricePaise); append(','); nullableNumber("estimatedUnitCostPaise", line.estimatedUnitCostPaise); append('}')
                }
                append('}')
            }
            append(','); append("\"stock\":"); array(stock) { (id, quantity) ->
                append('{'); field("productId", id); append(',')
                if (quantity == null) append("\"quantity\":null") else number("quantity", quantity)
                append('}')
            }
            append(','); append("\"stockMovements\":"); array(movements) { movement ->
                append('{'); field("movementId", movement.movementId); append(','); field("productId", movement.productId); append(',')
                number("quantityDelta", movement.quantityDelta); append(','); nullableNumber("orderNumber", movement.orderNumber); append('}')
            }
            append(','); append("\"customerCredits\":"); array(credits) { credit ->
                append('{'); field("creditId", credit.creditId); append(','); field("customerId", credit.customerId); append(',')
                number("orderNumber", credit.orderNumber); append(','); number("amountPaise", credit.amountPaise); append(',')
                number("repaidPaise", credit.repaidPaise); append(','); number("reversedPaise", credit.reversedPaise); append('}')
            }
            append(','); append("\"repayments\":"); array(repayments) { repayment ->
                append('{'); field("repaymentId", repayment.repaymentId); append(','); field("customerId", repayment.customerId); append(',')
                number("amountPaise", repayment.amountPaise); append('}')
            }
            append(','); append("\"paymentEntries\":"); array(payments) { payment ->
                append('{'); field("entryId", payment.entryId); append(','); nullableNumber("orderNumber", payment.orderNumber); append(',')
                nullableField("customerId", payment.customerId); append(','); number("amountPaise", payment.amountPaise); append(',')
                field("method", payment.method); append(','); field("status", payment.status); append(','); field("kind", payment.kind); append(',')
                nullableField("relatedEntryId", payment.relatedEntryId); append('}')
            }
            append(','); append("\"expenses\":"); array(expenses) { expense ->
                append('{'); field("expenseId", expense.expenseId); append(','); field("category", expense.category); append(',')
                number("amountPaise", expense.amountPaise); append(','); field("method", expense.method); append(',')
                field("status", expense.status); append('}')
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
    private fun StringBuilder.nullableNumber(name: String, value: Int?) { append(quoted(name)).append(':').append(value?.toString() ?: "null") }
    private fun StringBuilder.bool(name: String, value: Boolean) { append(quoted(name)).append(':').append(value) }

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
