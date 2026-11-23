package com.chaiandtapri.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorScopedSyncJournalTest {
    @Test fun mismatchedHeadIsNotSentOrAcknowledgedByAnotherOperatorSession() {
        val staffEvent = event("staff-event", "cashier-7")
        val delegate = MemoryJournal(mutableListOf(staffEvent))
        var requests = 0
        val result = SyncRunner(ActorScopedSyncJournal(delegate, "local-owner"), SyncHttpTransport { _, _, _ ->
            requests++
            error("A different operator's outbox event must never reach this transport.")
        }).push("https://sync.example.test", SyncEnvelopeIdentity(uuid(1), uuid(2), uuid(3)), "owner-token")

        assertEquals(0, result.acknowledged)
        assertTrue(result.blockedByActor)
        assertEquals(0, requests)
        assertEquals(listOf(staffEvent), delegate.pending(10))
        assertTrue(delegate.recorded.isEmpty())
    }

    @Test fun onlyContiguousHeadEventsForBoundOperatorAreExposed() {
        val ownerEvent = event("owner-event", "local-owner")
        val staffEvent = event("staff-event", "cashier-7")
        val laterOwnerEvent = event("later-owner-event", "local-owner")
        val scoped = ActorScopedSyncJournal(MemoryJournal(mutableListOf(ownerEvent, staffEvent, laterOwnerEvent)), "local-owner")

        assertEquals(listOf(ownerEvent), scoped.pending(10))
        assertFalse(scoped.blockedByActor)
        assertTrue(scoped.record(ownerEvent.eventId, "accepted", 1))
        assertTrue(scoped.pending(1).isEmpty())
        assertTrue(scoped.blockedByActor)
    }

    @Test fun unattributedLegacyHeadBlocksUntilItCanBeSafelyLinked() {
        val legacy = event("legacy-event", null)
        val scoped = ActorScopedSyncJournal(MemoryJournal(mutableListOf(legacy)), "local-owner")
        assertTrue(scoped.pending(10).isEmpty())
        assertTrue(scoped.blockedByActor)
    }

    @Test fun ownerCloudBindingRequiresServerOwnerRoleAndLocalOwner() {
        val owner = ownerSession()
        assertTrue(SyncOwnerPolicy.mayBindCloudSession(owner, "local-owner"))
        assertFalse(SyncOwnerPolicy.mayBindCloudSession(owner.copy(role = SyncAccountRole.CASHIER), "local-owner"))
        assertFalse(SyncOwnerPolicy.mayBindCloudSession(owner, "cashier-1"))
        assertFalse(SyncOwnerPolicy.mayBindCloudSession(owner.copy(userId = null), "local-owner"))
    }

    @Test fun ownerUploadRequiresServerOwnerAndMatchingLocalOwnerBinding() {
        val owner = ownerSession().copy(localActorId = "local-owner")
        assertTrue(SyncOwnerPolicy.mayUpload(owner))
        assertFalse(SyncOwnerPolicy.mayUpload(owner.copy(role = SyncAccountRole.MANAGER)))
        assertFalse(SyncOwnerPolicy.mayUpload(owner.copy(localActorId = "cashier-1")))
        assertFalse(SyncOwnerPolicy.mayUpload(owner.copy(userId = null)))
    }

    private fun ownerSession() = SyncSession(
        "https://sync.example.test",
        SyncEnvelopeIdentity(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333"
        ),
        "synthetic-token",
        userId = "44444444-4444-4444-8444-444444444444",
        role = SyncAccountRole.OWNER
    )

    private fun event(id: String, actorId: String?) = SyncOutboxEvent(uuid(id.hashCode().toLong()), id, 1,
        """{"kind":"projection_delta.v1","orders":[],"stock":[],"stockMovements":[],"customerCredits":[],"repayments":[],"paymentEntries":[],"expenses":[],"purchaseReceipts":[],"supplierPayments":[],"purchaseReturns":[],"staffShifts":[]}""", actorId)

    private fun uuid(value: Long) = java.util.UUID.nameUUIDFromBytes(value.toString().toByteArray()).toString()

    private class MemoryJournal(private val events: MutableList<SyncOutboxEvent>) : SyncJournal {
        val recorded = mutableListOf<String>()
        override fun pending(limit: Int) = events.take(limit)
        override fun cursor() = recorded.size.toLong()
        override fun record(eventId: String, outcome: String, serverRevision: Long): Boolean {
            recorded += "$eventId:$outcome:$serverRevision"
            events.removeAll { it.eventId == eventId }
            return true
        }
    }
}
