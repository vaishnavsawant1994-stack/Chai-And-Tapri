package com.chaiandtapri.counter

/** Flat, one-product-per-row export of the local immutable physical-count audit projection. */
object StockCountHistoryCsv {
    fun render(sessions: List<StockCountHistorySession>, timeZoneId: String): String = buildString {
        appendLine("command_id,recorded_at_epoch_ms,device_time_zone,product_id,product_name,counted_quantity,quantity_status,reason,local_actor_recorded,evidence")
        sessions.forEach { session ->
            session.rows.forEach { row ->
                val quantity = row.countedQuantity
                append(listOf(
                    session.commandId,
                    session.recordedAtEpochMs.takeIf { it > 0L }?.toString().orEmpty(),
                    timeZoneId,
                    row.productId,
                    row.productName,
                    quantity?.toString().orEmpty(),
                    if (quantity == null) "historical quantity unavailable" else "recorded physical count",
                    session.reason,
                    (session.actorId != null).toString(),
                    "Device-local audit record; timestamp uses device clock; actor identity is not independently verified"
                ).joinToString(",") { cell(it) }).append('\n')
            }
        }
    }

    private fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'" + value else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
