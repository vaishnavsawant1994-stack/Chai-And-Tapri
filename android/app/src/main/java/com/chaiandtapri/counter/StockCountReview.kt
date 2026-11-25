package com.chaiandtapri.counter

data class StockCountReviewRow(
    val productId: String,
    val productName: String,
    val previousQuantity: Int?,
    val countedQuantity: Int,
    val unitLabel: String = "units"
) {
    val variance: Int? get() = previousQuantity?.let { countedQuantity - it }
}

/** Creates a truthful preview: an unconfirmed ledger balance is never presented as a known variance. */
object StockCountReview {
    fun build(
        products: Collection<TapriProduct>,
        counts: Map<String, Int>,
        stock: Map<String, Int>,
        confirmedProductIds: Set<String>
    ): List<StockCountReviewRow> {
        require(counts.isNotEmpty()) { "Select at least one product to count." }
        val productsById = products.filter { it.active }.associateBy { it.productId }
        return counts.toSortedMap().map { (productId, quantity) ->
            require(quantity in 0..100_000) { "Count for $productId is outside the supported range." }
            val product = productsById[productId] ?: throw IllegalArgumentException("Selected product is missing or inactive.")
            val previous = if (productId in confirmedProductIds) (stock[productId] ?: 0) else null
            StockCountReviewRow(productId, product.name, previous, quantity, product.unitLabel)
        }
    }
}
