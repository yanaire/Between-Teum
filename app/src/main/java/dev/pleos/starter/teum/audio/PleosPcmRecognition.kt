package dev.pleos.starter.teum.audio

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/** ON_DEVICE external PCM only. Never calls request(), registers a server or opens a microphone. */
internal class PleosPcmRecognition(
    private val context: Context,
    private val onResult: (String?, RecognitionOutcome) -> Unit
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var sdk: SpeechToText? = null
    private var closed = false
    private val listener = object : ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            handler.post { if (!closed && completed) complete(stt, RecognitionOutcome.Recognized) }
        }
        override fun onError(errorType: ErrorType) {
            handler.post { if (!closed) complete(null, RecognitionOutcome.Failed("Pleos ON_DEVICE: $errorType")) }
        }
        // These notifications do not establish final text, voiced duration or PCM amplitude.
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() = Unit
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun start(pcm: ByteArray, locale: Locale = Locale.KOREA) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && sdk == null)
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 16000 * 2 * 120)
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            complete(null, RecognitionOutcome.Unavailable("Pleos STT_SERVICE permission is not granted"))
            return
        }
        // Initial binding plus real-time PCM transfer plus a bounded final-result interval.
        handler.postDelayed({
            if (!closed) complete(null, RecognitionOutcome.Failed("Pleos on-device recognition timed out"))
        }, 5000L + pcm.size * 1000L / 32000L + 15000L)
        try {
            val engine = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
            sdk = engine
            engine.addListener(listener)
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    handler.post {
                        if (closed) return@post
                        try {
                            engine.setLocale(locale)
                            send(pcm, 0)
                        } catch (error: SecurityException) {
                            complete(null, RecognitionOutcome.Unavailable("Pleos recognition access denied"))
                        } catch (error: IllegalStateException) {
                            complete(null, RecognitionOutcome.Failed("Pleos recognition disconnected"))
                        }
                    }
                }
                override fun onFailure(errorType: ErrorType) {
                    handler.post {
                        if (!closed) complete(null, RecognitionOutcome.Unavailable("Pleos initialization: $errorType"))
                    }
                }
            })
        } catch (error: SecurityException) {
            complete(null, RecognitionOutcome.Unavailable("Pleos recognition access denied"))
        } catch (error: IllegalStateException) {
            complete(null, RecognitionOutcome.Unavailable("Pleos recognition service unavailable"))
        }
    }

    private fun send(pcm: ByteArray, offset: Int) {
        if (closed) return
        val engine = checkNotNull(sdk)
        try {
            if (offset == pcm.size) {
                engine.completeAudioSend()
                return
            }
            val end = minOf(offset + 3200, pcm.size)
            engine.sendAudio(pcm.copyOfRange(offset, end))
            handler.postDelayed({ send(pcm, end) }, 100)
        } catch (error: SecurityException) {
            complete(null, RecognitionOutcome.Unavailable("Pleos recognition access revoked"))
        } catch (error: IllegalStateException) {
            complete(null, RecognitionOutcome.Failed("Pleos PCM transfer failed"))
        }
    }

    private fun complete(text: String?, outcome: RecognitionOutcome) {
        if (closed) return
        close()
        onResult(text, outcome)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        sdk?.let { engine ->
            releaseStep { engine.stop() }
            releaseStep { engine.removeListener(listener) }
            releaseStep { engine.release() }
        }
        sdk = null
    }

    private fun releaseStep(operation: () -> Unit) {
        try {
            operation()
        } catch (error: SecurityException) {
            Log.w("TeumSpeech", "Recognition access was revoked during release")
        } catch (error: IllegalStateException) {
            Log.w("TeumSpeech", "Recognition service disconnected during release")
        }
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.STT_SERVICE"
    }
}
