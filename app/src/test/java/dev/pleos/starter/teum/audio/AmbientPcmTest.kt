package dev.pleos.starter.teum.audio

import dev.pleos.starter.teum.preferences.RestSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientPcmTest {
    @Test fun noneProducesExplicitSilenceWithoutAllocatingAnAudioLoop() {
        assertEquals(0, AmbientPcm.render(RestSound.NONE).size)
    }

    @Test fun eachAudibleChoiceHasDistinctBoundedPcmAndClickFreeBoundaries() {
        val rendered = RestSound.entries.filter { it != RestSound.NONE }.map(AmbientPcm::render)
        rendered.forEach { pcm ->
            assertEquals(128000, pcm.size)
            assertTrue(pcm.any { it.toInt() != 0 })
            assertTrue(pcm.all { it.toInt() in -26214..26214 })
            assertEquals(0, pcm.first().toInt())
            assertEquals(0, pcm.last().toInt())
        }
        rendered.forEachIndexed { index, pcm ->
            rendered.drop(index + 1).forEach { other -> assertFalse(pcm.contentEquals(other)) }
        }
    }
}
