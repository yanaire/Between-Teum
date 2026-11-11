package dev.pleos.starter.teum.audio

import dev.pleos.starter.teum.journey.JourneyToken
import dev.pleos.starter.teum.journey.MusicChoice
import dev.pleos.starter.teum.journey.PlaybackState
import dev.pleos.starter.teum.records.JourneyKind
import org.junit.Assert.*
import org.junit.Test

class MusicPlaybackTest {
    private class Output : MusicPlayback.Output {
        lateinit var events: MusicPlayback.Output.Listener
        var released = false
        override fun prepare(piece: OriginalMusic.Piece, listener: MusicPlayback.Output.Listener) {
            events = listener
        }
        override fun close() { released = true }
    }

    private val piece = OriginalMusic.select(JourneyKind.PREPARATION, MusicChoice.SUPPORT_SONG)
    private val token = JourneyToken("first", 3)

    @Test fun requestAndZeroFramesCannotClaimPlayback() {
        val events = mutableListOf<MusicPlayback.Observation>()
        val output = Output()
        MusicPlayback({ output }, events::add).use { owner ->
            owner.play(token, piece)
            output.events.progress(0)
            assertEquals(listOf(PlaybackState.REQUESTED), events.map { it.state })
            output.events.progress(OriginalMusic.SAMPLE_RATE)
            assertEquals(PlaybackState.PLAYING, events.last().state)
            assertEquals(1000L, events.last().positionMs)
        }
        assertTrue(output.released)
    }

    @Test fun stoppedOrReplacedOutputsCannotPublishLateCallbacks() {
        for (replace in listOf(false, true)) {
            val events = mutableListOf<MusicPlayback.Observation>()
            val outputs = mutableListOf<Output>()
            MusicPlayback({ Output().also(outputs::add) }, events::add).use { owner ->
                owner.play(token, piece)
                val old = outputs.single()
                if (replace) owner.play(JourneyToken("second", 0), piece) else owner.stop()
                val before = events.toList()
                old.events.progress(22050)
                old.events.ended()
                old.events.failed("late focus loss")
                assertEquals(before, events)
                assertTrue(old.released)
                if (replace) {
                    outputs.last().events.progress(22050)
                    assertEquals(JourneyToken("second", 0), events.last().token)
                    assertEquals(PlaybackState.PLAYING, events.last().state)
                }
            }
            assertTrue(outputs.all { it.released })
        }
    }

    @Test fun preparationFailureOrFocusLossIsTerminalWithoutAutomaticReplay() {
        for (started in listOf(false, true)) {
            val events = mutableListOf<MusicPlayback.Observation>()
            val output = Output()
            MusicPlayback({ output }, events::add).use { owner ->
                owner.play(token, piece)
                if (started) output.events.progress(22050)
                output.events.failed(if (started) "Audio focus lost" else "Audio focus denied")
                val terminal = events.last()
                assertEquals(PlaybackState.UNAVAILABLE, terminal.state)
                assertTrue(output.released)
                output.events.progress(44100)
                output.events.ended()
                assertEquals(terminal, events.last())
                assertEquals(if (started) 3 else 2, events.size)
            }
        }
    }

    @Test fun endRequiresObservedProgressAndReleasesTheOutput() {
        for (started in listOf(false, true)) {
            val events = mutableListOf<MusicPlayback.Observation>()
            val output = Output()
            MusicPlayback({ output }, events::add).use { owner ->
                owner.play(token, piece)
                if (started) output.events.progress(piece.frames)
                output.events.ended()
                assertEquals(if (started) PlaybackState.ENDED else PlaybackState.UNAVAILABLE, events.last().state)
                assertEquals(if (started) piece.durationMs else 0L, events.last().positionMs)
                assertTrue(output.released)
                val count = events.size
                output.events.ended()
                assertEquals(count, events.size)
            }
        }
    }

    @Test fun destructionInvalidatesPendingCallbacksAndDisallowsRestart() {
        val events = mutableListOf<MusicPlayback.Observation>()
        val output = Output()
        val owner = MusicPlayback({ output }, events::add)
        owner.play(token, piece)
        owner.close()
        output.events.progress(22050)
        output.events.failed("late")
        assertEquals(listOf(PlaybackState.REQUESTED), events.map { it.state })
        assertTrue(output.released)
        assertThrows(IllegalStateException::class.java) { owner.play(token, piece) }
    }
}
