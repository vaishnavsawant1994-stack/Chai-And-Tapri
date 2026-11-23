package com.chaiandtapri.counter

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstSaleSetupTest {
    @Test fun nextActionAdvancesFromIdentityToProductsToCountToSelling() {
        assertEquals(FirstSaleSetupAction.SET_SHOP_IDENTITY, FirstSaleSetupAction.next("", 0, 0))
        assertEquals(FirstSaleSetupAction.ADD_PRODUCTS, FirstSaleSetupAction.next("Tapri", 0, 0))
        assertEquals(FirstSaleSetupAction.CONFIRM_OPENING_STOCK, FirstSaleSetupAction.next("Tapri", 2, 1))
        assertEquals(FirstSaleSetupAction.START_SELLING, FirstSaleSetupAction.next("Tapri", 2, 2))
    }

    @Test fun noProductsNeverLooksReadyToSell() {
        assertEquals(FirstSaleSetupAction.ADD_PRODUCTS, FirstSaleSetupAction.next("Tapri", 0, 0))
        assertEquals(FirstSaleSetupAction.ADD_PRODUCTS, FirstSaleSetupAction.next("Tapri", 0, 2))
    }
}
