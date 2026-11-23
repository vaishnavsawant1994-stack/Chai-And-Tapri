package com.chaiandtapri.counter

/** The next owner action required before the first catalogue-priced, stock-backed sale. */
enum class FirstSaleSetupAction(val buttonLabel: String) {
    SET_SHOP_IDENTITY("Continue setup · shop identity"),
    ADD_PRODUCTS("Continue setup · add products and confirm prices"),
    CONFIRM_OPENING_STOCK("Continue setup · confirm physical opening stock"),
    START_SELLING("Start selling · Quick Sale");

    companion object {
        fun next(shopName: String, sellableProductCount: Int, stockConfirmedProductCount: Int): FirstSaleSetupAction = when {
            shopName.isBlank() -> SET_SHOP_IDENTITY
            sellableProductCount <= 0 -> ADD_PRODUCTS
            stockConfirmedProductCount < sellableProductCount -> CONFIRM_OPENING_STOCK
            else -> START_SELLING
        }
    }
}
