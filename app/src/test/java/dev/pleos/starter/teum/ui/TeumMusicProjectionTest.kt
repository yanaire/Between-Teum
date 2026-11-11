package dev.pleos.starter.teum.ui

import dev.pleos.starter.teum.audio.MusicPlayback
import dev.pleos.starter.teum.audio.OriginalMusic
import dev.pleos.starter.teum.journey.*
import dev.pleos.starter.teum.records.SpeechMeasurement
import org.junit.Assert.*
import org.junit.Test

class TeumMusicProjectionTest {
    @Test fun selectedMusicRemainsUnrequestedAndOnlyCurrentCallbackSuppliesPosition() {
        val f = JourneyFixture(JourneyRoute.PREPARATION)
        f.send(JourneyRequest.RecordSpeech)
        f.send(JourneyRequest.SpeechDone)
        f.runtime.speechMeasured(f.runtime.token, SpeechMeasurement(0))
        f.send(JourneyRequest.Continue)
        f.send(JourneyRequest.SelectMusic(MusicChoice.RECENT))
        val selected = musicProjection(f.state, null)
        assertEquals("UNREQUESTED", selected["status"].asString)
        assertTrue(selected.getAsJsonArray("choices").all { it.asJsonObject["available"].asBoolean })
        f.send(JourneyRequest.ListenMusic)
        val token = f.runtime.token
        val piece = OriginalMusic.select(f.state.plan.kind, MusicChoice.RECENT)
        val observation = MusicPlayback.Observation(token, PlaybackState.PLAYING, piece, 1000)
        f.runtime.playbackObserved(token, PlaybackState.PLAYING)
        assertEquals(1000L, musicProjection(f.state, observation)["positionMs"].asLong)
        f.send(JourneyRequest.ChangeMusic)
        val after = musicProjection(f.state, observation)
        assertEquals("UNREQUESTED", after["status"].asString)
        assertEquals(0L, after["positionMs"].asLong)
        println("MUSIC_SELECTION_JSON=$selected")
    }
}
