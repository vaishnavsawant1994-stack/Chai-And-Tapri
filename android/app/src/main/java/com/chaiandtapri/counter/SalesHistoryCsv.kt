package com.chaiandtapri.counter

/** One row per order; its payment events are summarized without exporting customer identity. */
data class SalesHistoryCsvRow(val order: CounterOrder, val payments: List<PaymentEntry>)

object SalesHistoryCsv {
    fun render(rows: List<SalesHistoryCsvRow>): String = buildString {
        appendLine("order_number,created_at_epoch_ms,items,fulfillment,payment,sale_paise,paid_paise,credit_paise,refunded_paise,due_paise,voided,cancel_reason,payment_event_count,payment_events,evidence")
        rows.forEach { row ->
            val order = row.order
            val events = row.payments.joinToString("; ") { event ->
                "${event.method} ${event.amountPaise} paise ${event.status} ${event.kind}"
            }
            append(listOf(
                order.number.toString(), order.createdAtEpochMs.takeIf { it > 0L }?.toString().orEmpty(),
                order.items, order.fulfillment.name, order.payment.name, order.salePaise.toString(),
                order.paidPaise.toString(), order.creditPaise.toString(), order.refundedPaise.toString(),
                order.duePaise.toString(), order.voided.toString(), order.cancelReason.orEmpty(),
                row.payments.size.toString(), events,
                "Local recorded order and payment states; staff-declared tenders are not provider-verified. Timestamp uses device clock."
            ).joinToString(",") { cell(it) }).append('\n')
        }
    }

    private fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'" + value else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
