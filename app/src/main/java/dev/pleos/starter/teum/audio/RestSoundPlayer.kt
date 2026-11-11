package dev.pleos.starter.teum.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import dev.pleos.starter.teum.preferences.RestSound
import java.util.concurrent.Executors

/** Main-thread API. Parent stops on lifecycle/safety changes and closes on destruction. */
class RestSoundPlayer(context: Context, private val onState: (State) -> Unit) : AutoCloseable {
    sealed class State {
        data object Stopped : State()
        data object Silent : State()
        data class Preparing(val sound: RestSound) : State()
        data class Playing(val sound: RestSound) : State()
        data class Unavailable(val reason: String) : State()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) stop()
        }, handler).build()
    private var track: AudioTrack? = null
    private var generation = 0L
    private var closed = false

    /** Selects exactly one sound. Selecting NONE stops output and abandons focus. */
    fun play(sound: RestSound) {
        mainThread()
        check(!closed)
        stopOutput()
        if (sound == RestSound.NONE) {
            onState(State.Silent)
            return
        }
        val granted = try {
            audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (error: SecurityException) {
            false
        }
        if (!granted) {
            onState(State.Unavailable("Audio focus denied"))
            return
        }
        val token = generation
        executor.execute {
            val pcm = AmbientPcm.render(sound)
            handler.post {
                if (closed || token != generation) return@post
                var created: AudioTrack? = null
                try {
                    val output = AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(AmbientPcm.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                    created = output
                    check(output.write(pcm, 0, pcm.size) == pcm.size) { "Incomplete PCM write" }
                    check(output.setLoopPoints(0, pcm.size, -1) == AudioTrack.SUCCESS) { "Loop rejected" }
                    check(output.setVolume(0.6f) == AudioTrack.SUCCESS) { "Volume rejected" }
                    output.play()
                    track = output
                } catch (error: IllegalArgumentException) {
                    created?.release()
                    fail(error.message)
                } catch (error: IllegalStateException) {
                    created?.release()
                    fail(error.message)
                } catch (error: UnsupportedOperationException) {
                    created?.release()
                    fail(error.message)
                } catch (error: SecurityException) {
                    created?.release()
                    fail(error.message)
                }
                if (track != null && token == generation) onState(State.Playing(sound))
            }
        }
        onState(State.Preparing(sound))
    }

    fun stop() {
        mainThread()
        stopOutput()
        onState(State.Stopped)
    }

    private fun fail(reason: String?) {
        track = null
        audio.abandonAudioFocusRequest(focus)
        onState(State.Unavailable(reason ?: "Audio output unavailable"))
    }

    private fun stopOutput() {
        generation++
        track?.let { it.pause(); it.release() }
        track = null
        audio.abandonAudioFocusRequest(focus)
    }

    override fun close() {
        mainThread()
        if (closed) return
        closed = true
        stop()
        executor.shutdownNow()
    }

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
