package com.chaiandtapri.counter

data class StockCountHistoryRow(
    val productId: String,
    val productName: String,
    val countedQuantity: Int?
)

data class StockCountHistorySession(
    val commandId: String,
    val recordedAtEpochMs: Long,
    val reason: String,
    val rows: List<StockCountHistoryRow>,
    val actorId: String?
)

/** Read-only projection of immutable audit events; legacy entries disclose when exact counts were not retained. */
object StockCountHistory {
    private val fieldsPattern = Regex("(?:^|\\s)([a-z_]+)=([^\\s]*)")
    private val countedPairPattern = Regex("([a-z][a-z0-9_]{1,39}):(\\d{1,6})")

    fun from(records: List<AuditRecord>, productNames: Map<String, String>): List<StockCountHistorySession> =
        records.asSequence().mapNotNull { record ->
            val eventBody = record.event.substringAfter(' ', missingDelimiterValue = "")
            val prefix = "stock.physical_count "
            if (!eventBody.startsWith(prefix)) return@mapNotNull null
            val body = eventBody.removePrefix(prefix)
            val values = fieldsPattern.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            val productIds = values["products"]?.split(',')?.filter { it.matches(Regex("[a-z][a-z0-9_]{1,39}")) }.orEmpty()
            if (productIds.isEmpty()) return@mapNotNull null
            val counted = values["counted"]?.split(',')?.mapNotNull { token ->
                countedPairPattern.matchEntire(token)?.let { match -> match.groupValues[1] to match.groupValues[2].toInt() }
            }?.toMap().orEmpty()
            if ("counted" in values && (counted.keys != productIds.toSet() || counted.values.any { it !in 0..100_000 })) return@mapNotNull null
            val reason = Regex("(?:^|\\s)reason=(.*)$").find(body)?.groupValues?.get(1).orEmpty()
            StockCountHistorySession(
                commandId = record.commandId,
                recordedAtEpochMs = record.createdAtEpochMs,
                reason = reason,
                rows = productIds.distinct().sorted().map { id -> StockCountHistoryRow(id, productNames[id] ?: id, counted[id]) },
                actorId = record.actorId
            )
        }.sortedWith(compareByDescending<StockCountHistorySession> { it.recordedAtEpochMs }.thenByDescending { it.commandId }).toList()
}
