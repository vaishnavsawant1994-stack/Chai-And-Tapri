package com.chaiandtapri.counter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the default offline emulator suite from accidentally running endpoint-dependent sync E2E tests. */
@RunWith(AndroidJUnit4::class)
class InstrumentationConfigurationTest {
    @Test fun runnerArgumentsMatchLiveSyncOptInMode() {
        val args = InstrumentationRegistry.getArguments()
        val liveSyncOptIn = args.getString("cat052LiveSyncE2e") == "true"
        val exclusions = args.getString("notClass").orEmpty()
        val expected = listOf(
            "com.chaiandtapri.counter.LiveBackendSyncE2ETest",
            "com.chaiandtapri.counter.LiveBackgroundSyncRetryE2ETest",
            "com.chaiandtapri.counter.LiveBackgroundSyncProcessRestartE2ETest",
            "com.chaiandtapri.counter.LiveSyncMaintenanceBarrierE2ETest"
        ).joinToString(",")
        if (liveSyncOptIn) {
            assertFalse("live sync opt-in must leave backend classes runnable", exclusions.contains("LiveBackgroundSync"))
            assertFalse("live sync opt-in must leave backend classes runnable", exclusions.contains("LiveBackendSync"))
        } else {
            assertEquals("default emulator suite runner exclusions", expected, exclusions)
        }
    }
}
