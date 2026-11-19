package com.chaiandtapri.counter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

sealed interface SpeechInputEvent {
    data object Ready : SpeechInputEvent
    data object SpeechStarted : SpeechInputEvent
    data class Transcript(val text: String) : SpeechInputEvent
    data class Failure(val code: Int, val label: String) : SpeechInputEvent
}

/** One utterance per start call; callers must explicitly start each next utterance. */
class AndroidSpeechInput(context: Context, private val emit: (SpeechInputEvent) -> Unit) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(locale: String): Boolean {
        stop()
        if (!available) { emit(SpeechInputEvent.Failure(-1,"Recognition service unavailable")); return false }
        return try {
            val speech = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = emit(SpeechInputEvent.Ready)
                override fun onBeginningOfSpeech() = emit(SpeechInputEvent.SpeechStarted)
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { if(recognizer!==speech)return;emit(SpeechInputEvent.Failure(error, errorLabel(error)));release() }
                override fun onResults(results: Bundle?) {
                    if(recognizer!==speech)return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) emit(SpeechInputEvent.Failure(-2,"No speech recognized")) else emit(SpeechInputEvent.Transcript(text))
                    release()
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,locale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,locale)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,appContext.packageName)
            }
            speech.startListening(intent)
            true
        } catch (error: RuntimeException) {
            release()
            emit(SpeechInputEvent.Failure(-3,error.message ?: "Unable to start recognition"))
            false
        }
    }

    fun stop() { val active=recognizer;recognizer=null;active?.cancel();active?.destroy() }
    fun destroy() { stop() }
    private fun release() { recognizer?.destroy(); recognizer = null }
    private fun errorLabel(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio capture error"
        SpeechRecognizer.ERROR_CLIENT -> "Recognition cancelled or client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Recognition network unavailable"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        else -> "Recognition error $code"
    }
}
