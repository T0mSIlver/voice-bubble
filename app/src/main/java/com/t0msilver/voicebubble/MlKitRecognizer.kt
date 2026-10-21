package com.t0msilver.voicebubble

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ML Kit GenAI speech recognition. Advanced mode is the on-device Gemini Nano model (Pixel 10 and
 * 11, through AICore), the closest thing to Rambler a third-party app can call. Basic mode is
 * Android's own recognizer behind the same API.
 */
class MlKitRecognizer(
    private val scope: CoroutineScope,
    private val locale: Locale,
    private val advanced: Boolean,
    private val listener: Recognizer.Listener,
) : Recognizer {
    override val label = if (advanced) "Gemini Nano (ML Kit advanced)" else "ML Kit basic"
    private var client: SpeechRecognizer? = null
    @Volatile private var wanted = false

    override fun start() {
        wanted = true
        scope.launch {
            val r = try {
                SpeechRecognition.getClient(options(locale, advanced)).also { client = it }
            } catch (e: Throwable) {
                listener.onUnavailable("$label: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            try {
                if (!ready(r)) return@launch
                listener.onStatus("Listening · $label")
                // A session can end on its own (silence, a time limit); keep going until Tom stops.
                while (wanted) {
                    val began = System.currentTimeMillis()
                    r.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromMic() })
                        .transformWhile { emit(it); it !is SpeechRecognizerResponse.CompletedResponse }
                        .collect { resp ->
                            when (resp) {
                                is SpeechRecognizerResponse.PartialTextResponse -> listener.onPartial(resp.text)
                                is SpeechRecognizerResponse.FinalTextResponse -> listener.onFinal(resp.text)
                                is SpeechRecognizerResponse.ErrorResponse -> {
                                    wanted = false
                                    listener.onError("$label: ${resp.e.message}")
                                }
                                else -> Unit
                            }
                        }
                    if (wanted && System.currentTimeMillis() - began < 1000) delay(500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                listener.onError("$label: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { r.close() }
                client = null
                listener.onEnded()
            }
        }
    }

    /** Checks the model, downloading it if needed. False when it can't run (onUnavailable sent). */
    private suspend fun ready(r: SpeechRecognizer): Boolean {
        when (val status = r.checkStatus()) {
            FeatureStatus.AVAILABLE -> return true
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                listener.onStatus("Downloading the $label model…")
                var ok = false
                r.download()
                    .transformWhile {
                        emit(it)
                        it !is DownloadStatus.DownloadCompleted && it !is DownloadStatus.DownloadFailed
                    }
                    .collect { if (it is DownloadStatus.DownloadCompleted) ok = true }
                if (!ok) listener.onUnavailable("$label: model download failed")
                return ok
            }
            else -> {
                listener.onUnavailable("$label: not available here (status $status)")
                return false
            }
        }
    }

    override fun stop() {
        wanted = false
        val r = client ?: return
        scope.launch { runCatching { r.stopRecognition() } }
    }

    companion object {
        fun options(locale: Locale, advanced: Boolean): SpeechRecognizerOptions = speechRecognizerOptions {
            this.locale = locale
            preferredMode = if (advanced) SpeechRecognizerOptions.Mode.MODE_ADVANCED
            else SpeechRecognizerOptions.Mode.MODE_BASIC
        }

        /** For the setup screen: FeatureStatus of one mode, or the exception's message. */
        suspend fun describeStatus(locale: Locale, advanced: Boolean): String = try {
            SpeechRecognition.getClient(options(locale, advanced)).use { r ->
                when (val s = r.checkStatus()) {
                    FeatureStatus.AVAILABLE -> "available"
                    FeatureStatus.DOWNLOADABLE -> "downloadable (downloads on first use)"
                    FeatureStatus.DOWNLOADING -> "downloading"
                    FeatureStatus.UNAVAILABLE -> "unavailable on this phone"
                    else -> "status $s"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
