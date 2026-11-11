package dev.pleos.starter.teum.journey

import dev.pleos.starter.teum.records.SpeechMeasurement
import org.junit.Assert.*
import org.junit.Test

class MusicInterruptionTest {
    private fun listening(): JourneyFixture = JourneyFixture(JourneyRoute.PREPARATION).apply {
        send(JourneyRequest.RecordSpeech)
        send(JourneyRequest.SpeechDone)
        runtime.speechMeasured(runtime.token, SpeechMeasurement(0))
        send(JourneyRequest.Continue)
        send(JourneyRequest.SelectMusic(MusicChoice.SUPPORT_SONG))
        send(JourneyRequest.ListenMusic)
    }

    @Test fun interruptionClearsPendingOrPlayingStatusAndRejectsLatePlayback() {
        for (reason in JourneyInterruption.entries) for (playing in listOf(false, true)) {
            val f = listening()
            val token = f.runtime.token
            if (playing) f.runtime.playbackObserved(token, PlaybackState.PLAYING)
            val update = f.runtime.interrupt(reason)
            assertEquals(PlaybackState.UNAVAILABLE, update.snapshot.playback)
            assertEquals(listOf(JourneyEffect.StopOwnedEffects), update.effects)
            assertThrows(JourneyRejected::class.java) { f.runtime.playbackObserved(token, PlaybackState.PLAYING) }
        }
    }

    @Test fun userFinishAndStopCannotLeaveAPlayingStatus() {
        for (request in listOf(JourneyRequest.Finish, JourneyRequest.Stop)) {
            val f = listening()
            f.runtime.playbackObserved(f.runtime.token, PlaybackState.PLAYING)
            val result = f.send(request)
            assertFalse(result.snapshot.active)
            assertEquals(PlaybackState.UNAVAILABLE, result.snapshot.playback)
            assertEquals(listOf(JourneyEffect.StopOwnedEffects), result.effects)
        }
    }

    @Test fun restartCannotReplayMusicOrRetainAPlayingClaim() {
        val f = listening()
        f.runtime.playbackObserved(f.runtime.token, PlaybackState.PLAYING)
        val recovered = JourneyRuntime(f.clock, f.store).recover()!!
        assertEquals(JourneyStatus.RECOVERED_INTERRUPTED, recovered.snapshot.status)
        assertEquals(PlaybackState.UNAVAILABLE, recovered.snapshot.playback)
        assertEquals(listOf(JourneyEffect.StopOwnedEffects), recovered.effects)
    }

    @Test fun backwardsNativeClockStopsMusicInsteadOfLeavingPlayingStatus() {
        val f = listening()
        f.runtime.playbackObserved(f.runtime.token, PlaybackState.PLAYING)
        f.clock.monotonic--
        val result = f.runtime.tick()
        assertEquals(JourneyInterruption.CLOCK, result.snapshot.interruption)
        assertEquals(PlaybackState.UNAVAILABLE, result.snapshot.playback)
        assertEquals(listOf(JourneyEffect.StopOwnedEffects), result.effects)
    }
}
