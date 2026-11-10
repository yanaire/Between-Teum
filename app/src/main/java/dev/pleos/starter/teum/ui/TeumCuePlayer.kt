package dev.pleos.starter.teum.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Local audible cues only. No microphone, no recording. TTS is used only when
 * an engine initializes and reports Korean support; otherwise the caller keeps
 * the visible prompt and this player emits a device tone.
 */
class TeumCuePlayer(context: Context) : AutoCloseable {
    enum class Status { CHECKING, READY, FALLBACK }

    private val main = Handler(Looper.getMainLooper())
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    } catch (e: RuntimeException) {
        null
    }
    private var engine: TextToSpeech? = null
    private var status = Status.CHECKING
    private var closed = false
    private val fallback = Runnable {
        if (!closed && status == Status.CHECKING) {
            status = Status.FALLBACK
            listener?.invoke(status)
        }
    }

    init {
        main.postDelayed(fallback, 3_000)
        try {
            engine = TextToSpeech(context.applicationContext) { code ->
                main.post {
                    main.removeCallbacks(fallback)
                    if (closed) return@post
                    if (code != TextToSpeech.SUCCESS) {
                        status = Status.FALLBACK
                    } else {
                        val result = try {
                            engine?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
                        } catch (e: RuntimeException) {
                            TextToSpeech.LANG_NOT_SUPPORTED
                        }
                        status = if (result >= TextToSpeech.LANG_AVAILABLE) Status.READY else Status.FALLBACK
                    }
                    listener?.invoke(status)
                }
            }
        } catch (e: RuntimeException) {
            status = Status.FALLBACK
        }
    }

    var listener: ((Status) -> Unit)? = null

    fun status(): Status = status

    fun isReady(): Boolean = status == Status.READY

    fun speak(text: String): Boolean {
        if (closed || status != Status.READY) return false
        val result = try {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "teum-cue")
        } catch (e: RuntimeException) {
            null
        } ?: TextToSpeech.ERROR
        return result == TextToSpeech.SUCCESS
    }

    fun beep(urgent: Boolean = false) {
        if (closed) return
        try {
            tone?.startTone(
                if (urgent) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP,
                if (urgent) 400 else 220
            )
        } catch (e: RuntimeException) {
            // A missing audio device is not a UI failure; the visible prompt remains.
        }
    }

    /** Immediately stops any speaking or tone; used when an activity is cancelled. */
    fun stop() {
        if (closed) return
        try {
            engine?.stop()
        } catch (e: RuntimeException) {
            // Best-effort stop only.
        }
        try {
            tone?.stopTone()
        } catch (e: RuntimeException) {
            // Best-effort stop only.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(fallback)
        main.removeCallbacksAndMessages(null)
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (e: RuntimeException) {
            // Best-effort release only.
        }
        engine = null
        try {
            tone?.release()
        } catch (e: RuntimeException) {
            // Best-effort release only.
        }
    }
}
