package com.chaiandtapri.counter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCounterSessionPolicyTest {
    @Test fun repeatedCaptureRequiresExplicitActiveSessionAndOptIn() {
        for (reason in listOf(VoiceRearmReason.REVIEW_APPLIED, VoiceRearmReason.REVIEW_REJECTED, VoiceRearmReason.NO_MATCH, VoiceRearmReason.SPEECH_TIMEOUT)) {
            assertTrue(VoiceCounterSessionPolicy.shouldRearm(sessionActive = true, autoListenEnabled = true, reason = reason))
            assertFalse(VoiceCounterSessionPolicy.shouldRearm(sessionActive = false, autoListenEnabled = true, reason = reason))
            assertFalse(VoiceCounterSessionPolicy.shouldRearm(sessionActive = true, autoListenEnabled = false, reason = reason))
        }
    }

    @Test fun ambiguousTranscriptsAndTerminalRecognitionErrorsNeverAutoRearm() {
        assertFalse(VoiceCounterSessionPolicy.shouldRearm(true, true, VoiceRearmReason.UNRESOLVED_TRANSCRIPT))
        assertFalse(VoiceCounterSessionPolicy.shouldRearm(true, true, VoiceRearmReason.OTHER_FAILURE))
    }
}
