package com.chaiandtapri.counter

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSessionVaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val identity = SyncEnvelopeIdentity(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333"
    )
    private val session = SyncSession("https://sync.example.test/api/", identity, "synthetic-test-token")

    @After fun cleanUp() = SyncSessionVault.resetForTests(context)

    @Test fun encryptedSessionSurvivesVaultRecreationAndExplicitClear() {
        SyncSessionVault.resetForTests(context)
        SyncSessionVault(context).save(session)

        val envelope = SyncSessionVault.encryptedEnvelopeForTests(context)
        assertNotNull(envelope["session_ciphertext"])
        assertNotNull(envelope["session_iv"])
        assertTrue(envelope.values.all { it != "synthetic-test-token" })

        val restored = SyncSessionVault(context).load()
        assertEquals("https://sync.example.test/api", restored?.endpointBase)
        assertEquals(identity, restored?.identity)
        assertEquals("synthetic-test-token", restored?.bearerToken)

        SyncSessionVault(context).clear()
        assertNull(SyncSessionVault(context).load())
    }

    @Test fun invalidEndpointOrIdentityIsRejectedBeforePersistence() {
        SyncSessionVault.resetForTests(context)
        val vault = SyncSessionVault(context)
        val badEndpoint = runCatching { vault.save(session.copy(endpointBase = "http://sync.example.test")) }.isFailure
        val badIdentity = runCatching { vault.save(session.copy(identity = identity.copy(shopId = "not-a-uuid"))) }.isFailure
        assertTrue(badEndpoint)
        assertTrue(badIdentity)
        assertTrue(SyncSessionVault.encryptedEnvelopeForTests(context).values.all { it == null })
    }

    @Test fun missingKeystoreKeyFailsClosedAndPreservesEncryptedEnvelope() {
        SyncSessionVault.resetForTests(context)
        SyncSessionVault(context).save(session)
        val before = SyncSessionVault.encryptedEnvelopeForTests(context)
        SyncSessionVault.deleteKeyForTests()

        val error = runCatching { SyncSessionVault(context).load() }.exceptionOrNull()
        assertTrue(error is SyncSessionUnavailableException)
        assertEquals(before, SyncSessionVault.encryptedEnvelopeForTests(context))
    }

    @Test fun localOperatorBindingSurvivesEncryptedVaultReopen() {
        SyncSessionVault.resetForTests(context)
        val bound = session.copy(localActorId = "cashier-sync-01")
        SyncSessionVault(context).save(bound)
        assertEquals(bound.copy(endpointBase = bound.endpointBase.trimEnd('/')), SyncSessionVault(context).load())
    }

    @Test fun serverAccountIdentityAndMembershipRoleSurviveEncryptedVaultReopen() {
        SyncSessionVault.resetForTests(context)
        val authenticated = session.copy(
            userId = "44444444-4444-4444-8444-444444444444",
            role = SyncAccountRole.OWNER,
            localActorId = "local-owner"
        )
        SyncSessionVault(context).save(authenticated)
        assertEquals(authenticated.copy(endpointBase = authenticated.endpointBase.trimEnd('/')), SyncSessionVault(context).load())
    }

    @Test fun incompleteServerAccountIdentityIsRejectedBeforePersistence() {
        SyncSessionVault.resetForTests(context)
        val vault = SyncSessionVault(context)
        assertTrue(runCatching { vault.save(session.copy(userId = "44444444-4444-4444-8444-444444444444")) }.isFailure)
        assertTrue(runCatching { vault.save(session.copy(role = SyncAccountRole.CASHIER)) }.isFailure)
        assertTrue(SyncSessionVault.encryptedEnvelopeForTests(context).values.all { it == null })
    }

    @Test fun malformedLocalOperatorBindingIsRejected() {
        SyncSessionVault.resetForTests(context)
        assertTrue(runCatching { SyncSessionVault(context).save(session.copy(localActorId = "  ")) }.isFailure)
        assertTrue(SyncSessionVault.encryptedEnvelopeForTests(context).values.all { it == null })
    }

    @Test fun namespacedVaultIsIsolatedFromTheDefaultOwnerSession() {
        SyncSessionVault.resetForTests(context)
        val namespace = "test_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        val isolated = SyncSessionVault(context, namespace)
        val isolatedSession = session.copy(bearerToken = "isolated-synthetic-token")
        SyncSessionVault(context).save(session)
        isolated.save(isolatedSession)

        val normalizedSession = session.copy(endpointBase = session.endpointBase.trimEnd('/'))
        assertEquals(normalizedSession, SyncSessionVault(context).load())
        assertEquals(normalizedSession.copy(bearerToken = isolatedSession.bearerToken), SyncSessionVault(context, namespace).load())
        isolated.clear()
        assertEquals(normalizedSession, SyncSessionVault(context).load())

        SyncSessionVault(context).clear()
    }
}
