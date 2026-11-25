package com.chaiandtapri.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerRepaymentReceiptTest {
    @Test fun receiptShowsFifoAllocationRemainingDueAndUnverifiedTenderEvidence() {
        val before = listOf(
            CustomerCredit("credit-1", "ramesh", 12, 1_000, 0),
            CustomerCredit("credit-2", "ramesh", 13, 500, 0)
        )
        val after = listOf(
            before[0].copy(repaidPaise = 500),
            before[1].copy(repaidPaise = 200)
        )
        val payment = PaymentEntry("repayment-1", null, "ramesh", 700, "upi", "staff_declared", "customer_repayment")

        val receipt = CustomerRepaymentReceipt.create("ramesh", before, after, payment)

        assertEquals(listOf(RepaymentAllocation(12, 500), RepaymentAllocation(13, 200)), receipt?.allocations)
        assertEquals(800, receipt?.remainingPaise)
        assertEquals("Staff-declared UPI · not provider-verified", receipt?.evidenceLabel)
        assertTrue(receipt!!.displayText().contains("Order #12: ₹5.00"))
        assertTrue(receipt.displayText().contains("Remaining customer balance: ₹8.00"))
        assertFalse(receipt.displayText().contains("verified payment"))
    }

    @Test fun receiptRefusesIncompleteOrWrongCustomerLedgerChanges() {
        val before = listOf(CustomerCredit("credit-1", "ramesh", 12, 1_000, 0))
        val payment = PaymentEntry("repayment-1", null, "ramesh", 500, "cash", "staff_declared", "customer_repayment")
        assertNull(CustomerRepaymentReceipt.create("ramesh", before, before, payment))
        assertNull(CustomerRepaymentReceipt.create("someone-else", before, before, payment))
        assertNull(CustomerRepaymentReceipt.create("ramesh", before, before, payment.copy(status = "verified")))
    }
}
