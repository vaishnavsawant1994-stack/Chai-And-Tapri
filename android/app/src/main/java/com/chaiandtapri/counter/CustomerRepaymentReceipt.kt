package com.chaiandtapri.counter

data class RepaymentAllocation(val orderNumber: Int, val amountPaise: Int)

data class CustomerRepaymentReceipt(
    val customerId: String,
    val amountPaise: Int,
    val method: String,
    val status: String,
    val remainingPaise: Int,
    val allocations: List<RepaymentAllocation>
) {
    val evidenceLabel: String
        get() = if (method == "upi") "Staff-declared UPI · not provider-verified" else "Staff-declared cash"

    fun displayText(): String = buildString {
        appendLine("Customer repayment receipt")
        appendLine("Account: $customerId")
        appendLine("Amount received: ${formatMoney(amountPaise)}")
        appendLine("Method: $evidenceLabel")
        appendLine("Allocation:")
        allocations.forEach { appendLine("Order #${it.orderNumber}: ${formatMoney(it.amountPaise)}") }
        append("Remaining customer balance: ${formatMoney(remainingPaise)}")
    }

    companion object {
        fun create(
            customerId: String,
            before: List<CustomerCredit>,
            after: List<CustomerCredit>,
            payment: PaymentEntry
        ): CustomerRepaymentReceipt? {
            if (customerId.isBlank() || payment.customerId != customerId || payment.kind != "customer_repayment" ||
                payment.status != "staff_declared" || payment.method !in setOf("cash", "upi") || payment.amountPaise <= 0) return null
            val afterById = after.associateBy { it.creditId }
            val allocations = before.asSequence().filter { it.customerId == customerId }
                .mapNotNull { previous ->
                    val current = afterById[previous.creditId] ?: return@mapNotNull null
                    val applied = current.repaidPaise - previous.repaidPaise
                    if (applied <= 0) null else RepaymentAllocation(previous.orderNumber, applied)
                }.toList()
            if (allocations.sumOf { it.amountPaise } != payment.amountPaise) return null
            val remaining = after.filter { it.customerId == customerId }.sumOf { it.outstandingPaise }
            return CustomerRepaymentReceipt(customerId, payment.amountPaise, payment.method, payment.status, remaining, allocations)
        }

        private fun formatMoney(paise: Int): String = "₹${"%.2f".format(java.util.Locale.ROOT, paise / 100.0)}"
    }
}
