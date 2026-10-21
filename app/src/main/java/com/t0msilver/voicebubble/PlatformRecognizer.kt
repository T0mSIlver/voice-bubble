package com.t0msilver.voicebubble

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Android's SpeechRecognizer (on Pixel, Google's on-device model when it is installed). It ends
 * each utterance after a pause, so it restarts until Tom taps stop.
 */
class PlatformRecognizer(
    private val context: Context,
    private val locale: Locale,
    private val listener: Recognizer.Listener,
) : Recognizer {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var wanted = false
    private var onDevice = false
    override val label get() = if (onDevice) "Android on-device recognizer" else "Android recognizer"

    override fun start() {
        onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("No Android speech recognizer installed")
            listener.onEnded()
            return
        }
        sr = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        sr!!.setRecognitionListener(callbacks)
        wanted = true
        listener.onStatus("Listening · $label")
        listen()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
        sr?.startListening(intent)
    }

    private fun relisten() {
        if (wanted) main.postDelayed({ if (wanted) listen() }, 150) else finish()
    }

    private fun finish() {
        main.removeCallbacksAndMessages(null)
        sr?.destroy()
        sr = null
        listener.onEnded()
    }

    override fun stop() {
        wanted = false
        // stopListening delivers the utterance's results, then onResults/onError calls finish.
        sr?.stopListening() ?: return
        main.postDelayed({ if (sr != null) finish() }, 2500)
    }

    private val callbacks = object : RecognitionListener {
        override fun onPartialResults(b: Bundle) {
            b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
        }

        override fun onResults(b: Bundle) {
            listener.onFinal(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            relisten()
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> relisten()
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    wanted = false
                    listener.onError("$label: ${locale.toLanguageTag()} isn't installed (Settings → Speech)")
                    finish()
                }
                else -> {
                    wanted = false
                    listener.onError("$label: error $error")
                    finish()
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
