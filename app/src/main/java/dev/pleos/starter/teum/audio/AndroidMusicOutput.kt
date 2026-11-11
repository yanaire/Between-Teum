package dev.pleos.starter.teum.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** The submitted task delivers render results; cancellation remains silent. */
internal fun musicRenderTask(
    piece: OriginalMusic.Piece,
    deliver: (Result<ShortArray>) -> Unit
): Runnable = Runnable {
    val result = try {
        Result.success(OriginalMusic.render(piece))
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        return@Runnable
    } catch (error: RuntimeException) {
        Result.failure(error)
    }
    deliver(result)
}

/** One disposable output per request. All public methods and callbacks run on main. */
class AndroidMusicOutput(
    private val context: Context,
    private val renderer: ExecutorService,
    private val authorized: () -> Boolean
) : MusicPlayback.Output {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var listener: MusicPlayback.Output.Listener? = null
    private var track: AudioTrack? = null
    private var job: Future<*>? = null
    private var closed = false
    private var receiverRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                fail("Audio route interrupted")
        }
    }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) fail("Audio focus lost")
        }, main).build()

    override fun prepare(piece: OriginalMusic.Piece, listener: MusicPlayback.Output.Listener) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && this.listener == null)
        this.listener = listener
        try {
            if (!authorized()) {
                fail("Playback no longer permitted")
                return
            }
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                fail("Audio focus denied")
                return
            }
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            receiverRegistered = true
            job = renderer.submit(musicRenderTask(piece) { result ->
                main.post {
                    result.fold(
                        onSuccess = { pcm -> if (!closed) start(pcm) },
                        onFailure = { error ->
                            fail("Music synthesis failed: ${error.javaClass.simpleName}: ${error.message}")
                        }
                    )
                }
            })
        } catch (error: SecurityException) {
            fail("Audio access denied: ${error.message}")
        } catch (error: IllegalStateException) {
            fail("Audio unavailable: ${error.message}")
        }
    }

    private fun start(pcm: ShortArray) {
        if (!authorized()) {
            fail("Playback no longer permitted")
            return
        }
        try {
            val created = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(OriginalMusic.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track = created
            check(created.state != AudioTrack.STATE_UNINITIALIZED) { "AudioTrack initialization failed" }
            check(created.write(pcm, 0, pcm.size) == pcm.size) { "Incomplete music PCM write" }
            check(created.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack static data initialization failed" }
            created.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onPeriodicNotification(output: AudioTrack) {
                    if (closed || output !== track) return
                    if (!authorized()) fail("Playback no longer permitted")
                    else listener?.progress(output.playbackHeadPosition)
                }
                override fun onMarkerReached(output: AudioTrack) {
                    if (closed || output !== track) return
                    if (!authorized()) {
                        fail("Playback no longer permitted")
                        return
                    }
                    listener?.progress(output.playbackHeadPosition)
                    listener?.ended()
                }
            }, main)
            check(created.setPositionNotificationPeriod(OriginalMusic.SAMPLE_RATE / 4) == AudioTrack.SUCCESS)
            check(created.setNotificationMarkerPosition(pcm.size) == AudioTrack.SUCCESS)
            check(created.setVolume(0.6f) == AudioTrack.SUCCESS)
            created.play()
            // PLAYING is published only by a playback-head callback, not this request.
        } catch (error: IllegalArgumentException) {
            fail("Audio format unavailable: ${error.message}")
        } catch (error: IllegalStateException) {
            fail("Audio preparation failed: ${error.message}")
        } catch (error: UnsupportedOperationException) {
            fail("Audio output unsupported: ${error.message}")
        } catch (error: SecurityException) {
            fail("Audio output denied: ${error.message}")
        }
    }

    private fun fail(reason: String) {
        if (closed) return
        val callback = listener
        close()
        callback?.failed(reason)
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        job?.cancel(true)
        job = null
        listener = null
        main.removeCallbacksAndMessages(null)
        val owned = track
        track = null
        try {
            // release is valid even when initialization or preparation failed.
            owned?.release()
        } finally {
            try {
                if (receiverRegistered) {
                    receiverRegistered = false
                    context.unregisterReceiver(noisy)
                }
            } finally {
                audio.abandonAudioFocusRequest(focus)
            }
        }
    }
}
