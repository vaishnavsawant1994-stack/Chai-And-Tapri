package com.chaiandtapri.counter

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Keeps endpoint-dependent live-sync tests opt-in for the ordinary offline emulator suite. */
class OfflineQualificationRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        if (arguments.getString("cat052LiveSyncE2e") != "true") {
            val excluded = linkedSetOf(
                "com.chaiandtapri.counter.LiveBackendSyncE2ETest",
                "com.chaiandtapri.counter.LiveBackgroundSyncRetryE2ETest",
                "com.chaiandtapri.counter.LiveBackgroundSyncProcessRestartE2ETest"
            )
            arguments.getString("notClass")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.let(excluded::addAll)
            arguments.putString("notClass", excluded.joinToString(","))
        }
        super.onCreate(arguments)
    }
}
