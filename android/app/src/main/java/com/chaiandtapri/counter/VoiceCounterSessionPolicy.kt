package com.chaiandtapri.counter

internal enum class VoiceRearmReason {
    REVIEW_APPLIED,
    REVIEW_REJECTED,
    NO_MATCH,
    SPEECH_TIMEOUT,
    OTHER_FAILURE,
    UNRESOLVED_TRANSCRIPT,
}

/** Allows repeated one-utterance captures only inside an explicit, foreground operator session. */
internal object VoiceCounterSessionPolicy {
    fun shouldRearm(sessionActive: Boolean, autoListenEnabled: Boolean, reason: VoiceRearmReason): Boolean =
        sessionActive && autoListenEnabled && reason in setOf(
            VoiceRearmReason.REVIEW_APPLIED,
            VoiceRearmReason.REVIEW_REJECTED,
            VoiceRearmReason.NO_MATCH,
            VoiceRearmReason.SPEECH_TIMEOUT,
        )
}
