package dev.pleos.starter.teum.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.pleos.starter.teum.records.SpeechMeasurement
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed class RecognitionOutcome {
    data object Recognized : RecognitionOutcome()
    data class Unavailable(val reason: String) : RecognitionOutcome()
    data class Failed(val reason: String, val platformCode: Int? = null) : RecognitionOutcome()
}

sealed class SpeechCaptureResult {
    data class Completed(val measurement: SpeechMeasurement, val recognition: RecognitionOutcome) : SpeechCaptureResult()
    data class Failed(val reason: String) : SpeechCaptureResult()
    data object Cancelled : SpeechCaptureResult()
}

/**
 * Main-thread API and callbacks. PCM never leaves memory or goes to a network service.
 * Parent owns RECORD_AUDIO permission, safety gate, foreground lifecycle, and persistence.
 *
 * Recognition uses the Pleos ON_DEVICE external-PCM API after the microphone stops.
 * Missing service, permission or Korean model produces an explicit unavailable result.
 */
class NativeSpeechCapture(
    context: Context,
    private val onResult: (SpeechCaptureResult) -> Unit,
    private val onPhase: (Phase) -> Unit = {},
    private val onLevel: (Double) -> Unit = {}
) : AutoCloseable {
    enum class Phase { RECORDING, RECOGNIZING }

    private class Capture(val locale: Locale) {
        val running = AtomicBoolean(true)
        val metrics = SpeechMetrics(SAMPLE_RATE)
        val pcm = ByteArrayOutputStream()
        val lock = Any()
        var recorder: AudioRecord? = null
        var stopFailure: String? = null
        var recognizer: PleosPcmRecognition? = null
    }

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var current: Capture? = null
    private var closed = false

    /** False means no new round started. Never requests permission or assumes a cached grant. */
    fun start(locale: Locale = Locale.KOREA): Boolean {
        mainThread()
        check(!closed)
        if (current != null) return false
        if (!hasPermission()) {
            onResult(SpeechCaptureResult.Failed("RECORD_AUDIO permission is not granted"))
            return false
        }
        val capture = Capture(locale)
        try {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "PCM16 mono 16 kHz input unsupported" }
            val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE / 5 * 2)).build()
            capture.recorder = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
        } catch (error: SecurityException) {
            capture.recorder?.release()
            onResult(SpeechCaptureResult.Failed("Microphone permission/access denied"))
            return false
        } catch (error: IllegalStateException) {
            capture.recorder?.release()
            onResult(SpeechCaptureResult.Failed(error.message ?: "Microphone unavailable"))
            return false
        } catch (error: IllegalArgumentException) {
            capture.recorder?.release()
            onResult(SpeechCaptureResult.Failed(error.message ?: "Microphone format unavailable"))
            return false
        } catch (error: UnsupportedOperationException) {
            capture.recorder?.release()
            onResult(SpeechCaptureResult.Failed(error.message ?: "Microphone unsupported"))
            return false
        }
        current = capture
        executor.execute { record(capture) }
        onPhase(Phase.RECORDING)
        return true
    }

    /** Ends actual microphone capture. Recognition, if verified and available, follows. */
    fun finish() {
        mainThread()
        current?.let { stopMicrophone(it) }
    }

    /** Use immediately on parent safety/lifecycle loss. No result is saved automatically. */
    fun cancel() {
        mainThread()
        current?.let { capture ->
            stopMicrophone(capture)
            complete(capture, SpeechCaptureResult.Cancelled)
        }
    }

    private fun record(capture: Capture) {
        val buffer = ShortArray(SAMPLE_RATE / 10)
        var failure: String? = null
        var countTotal = 0
        try {
            val recorder = checkNotNull(capture.recorder)
            while (capture.running.get() && countTotal < SAMPLE_RATE * MAX_SECONDS) {
                if (!hasPermission()) {
                    failure = "Microphone permission revoked"
                    break
                }
                if (Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true) {
                    failure = "Microphone capture is silenced by the platform"
                    break
                }
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (capture.running.get()) failure = "PCM read failed: $count"
                    break
                }
                capture.metrics.append(buffer, count)
                for (index in 0 until count) {
                    val sample = buffer[index].toInt()
                    capture.pcm.write(sample and 255)
                    capture.pcm.write((sample shr 8) and 255)
                }
                countTotal += count
                val level = SpeechMetrics(SAMPLE_RATE).also { it.append(buffer, count) }
                    .measurement().relativeLoudness ?: 0.0
                handler.post { if (current === capture && capture.running.get()) onLevel(level) }
            }
        } catch (error: SecurityException) {
            failure = "Microphone permission/access revoked"
        } catch (error: IllegalStateException) {
            if (capture.running.get()) failure = error.message ?: "Microphone stopped unexpectedly"
        } finally {
            synchronized(capture.lock) {
                capture.running.set(false)
                capture.recorder?.release()
                capture.recorder = null
                if (failure == null) failure = capture.stopFailure
            }
        }
        val error = failure
        handler.post {
            if (current !== capture) return@post
            if (error != null) complete(capture, SpeechCaptureResult.Failed(error))
            else if (countTotal == 0) complete(capture, SpeechCaptureResult.Failed("No PCM captured"))
            else recognize(capture)
        }
    }

    private fun stopMicrophone(capture: Capture) {
        synchronized(capture.lock) {
            capture.running.set(false)
            capture.recorder?.let {
                try {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                } catch (error: IllegalStateException) {
                    capture.stopFailure = "Microphone disconnected while stopping"
                    it.release()
                    capture.recorder = null
                }
            }
        }
    }

    private fun recognize(capture: Capture) {
        if (!hasPermission()) {
            complete(capture, SpeechCaptureResult.Failed("Microphone permission revoked"))
            return
        }
        val recognizer = PleosPcmRecognition(context) { text, outcome ->
            if (current === capture) complete(capture,
                SpeechCaptureResult.Completed(capture.metrics.measurement(text), outcome))
        }
        capture.recognizer = recognizer
        recognizer.start(capture.pcm.toByteArray(), capture.locale)
        if (current === capture) onPhase(Phase.RECOGNIZING)
    }

    private fun complete(capture: Capture, result: SpeechCaptureResult) {
        if (current !== capture) return
        current = null
        capture.recognizer?.close()
        capture.recognizer = null
        onResult(result)
    }

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun close() {
        mainThread()
        if (closed) return
        closed = true
        cancel()
        executor.shutdownNow()
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 120
    }
}
