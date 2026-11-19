package com.chaiandtapri.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerAccountPickerTest {
    @Test fun profileAndLegacyCreditAccountsAreMergedWithoutImplicitAccounts() {
        val options = CustomerAccountPicker.options(
            profiles = listOf(CustomerProfile("cust-1", "Asha")),
            credits = listOf(
                CustomerCredit("credit-1", "cust-1", 1, 1200, 200),
                CustomerCredit("credit-2", "old-ledger-id", 2, 500, 0)
            )
        )

        assertEquals(listOf("cust-1", "old-ledger-id"), options.map { it.customerId })
        assertEquals("Asha", options.first().displayName)
        assertEquals(1000, options.first().outstandingPaise)
        assertEquals("old-ledger-id", options.last().displayName)
    }

    @Test fun duplicateProfilesDoNotDuplicateAccountRowsAndNamesSortCaseInsensitively() {
        val options = CustomerAccountPicker.options(
            profiles = listOf(
                CustomerProfile("z-id", "zoya"),
                CustomerProfile("a-id", "Amit"),
                CustomerProfile("z-id", "duplicate profile")
            ),
            credits = listOf(CustomerCredit("credit-z", "z-id", 1, 200, 0))
        )

        assertEquals(listOf("a-id", "z-id"), options.map { it.customerId })
        assertEquals("zoya", options.last().displayName)
    }

    @Test fun accountLabelShowsNameIdentifierAndOutstandingAmount() {
        val option = CustomerAccountOption("cust-7", "Ravi", 12550)

        assertEquals("Ravi · cust-7 · due ₹125.50", option.label())
        assertTrue(CustomerAccountOption("cust-8", "cust-8", 0).label().contains("due ₹0.00"))
    }
}
