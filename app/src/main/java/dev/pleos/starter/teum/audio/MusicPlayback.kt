package dev.pleos.starter.teum.audio

import dev.pleos.starter.teum.journey.JourneyToken
import dev.pleos.starter.teum.journey.PlaybackState

/** Serialized by the main thread. Platform callbacks, never play requests, prove progress. */
class MusicPlayback(
    private val outputFactory: (JourneyToken) -> Output,
    private val onObservation: (Observation) -> Unit
) : AutoCloseable {
    interface Output : AutoCloseable {
        interface Listener {
            fun progress(frames: Int)
            fun ended()
            fun failed(reason: String)
        }
        fun prepare(piece: OriginalMusic.Piece, listener: Listener)
    }

    data class Observation(
        val token: JourneyToken, val state: PlaybackState, val piece: OriginalMusic.Piece,
        val positionMs: Long = 0, val reason: String? = null
    )

    private var generation = 0L
    private var output: Output? = null
    private var closed = false

    fun play(token: JourneyToken, piece: OriginalMusic.Piece) {
        check(!closed)
        stop()
        val owner = generation
        val created = outputFactory(token)
        output = created
        var started = false
        onObservation(Observation(token, PlaybackState.REQUESTED, piece))
        created.prepare(piece, object : Output.Listener {
            override fun progress(frames: Int) {
                if (owner != generation || closed || frames <= 0) return
                started = true
                onObservation(Observation(token, PlaybackState.PLAYING, piece,
                    (frames.toLong() * 1000 / OriginalMusic.SAMPLE_RATE).coerceAtMost(piece.durationMs)))
            }
            override fun ended() {
                if (owner != generation || closed) return
                stop()
                onObservation(Observation(token,
                    if (started) PlaybackState.ENDED else PlaybackState.UNAVAILABLE,
                    piece, if (started) piece.durationMs else 0,
                    if (started) null else "No playback progress observed"))
            }
            override fun failed(reason: String) {
                if (owner != generation || closed) return
                stop()
                onObservation(Observation(token, PlaybackState.UNAVAILABLE, piece, reason = reason))
            }
        })
    }

    fun stop() {
        generation++
        val owned = output
        output = null
        owned?.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
    }
}
