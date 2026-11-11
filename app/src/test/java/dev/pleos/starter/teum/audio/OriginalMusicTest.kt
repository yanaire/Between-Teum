package dev.pleos.starter.teum.audio

import dev.pleos.starter.teum.journey.MusicChoice
import dev.pleos.starter.teum.records.JourneyKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class OriginalMusicTest {
    @Test fun nativeContextSelectsDifferentScoresWithoutHistoryOrClockInputs() {
        val contexts = listOf(JourneyKind.MORNING, JourneyKind.NAP, JourneyKind.PREPARATION, JourneyKind.EVENING)
        val pieces = contexts.map { OriginalMusic.select(it, MusicChoice.SUPPORT_SONG) }
        assertEquals(listOf(104, 72, 96, 80), pieces.map { it.bpm })
        assertEquals(contexts, pieces.map { it.kind })
        assertEquals(4, pieces.map { it.title }.distinct().size)
        assertEquals(OriginalMusic.select(JourneyKind.MORNING, MusicChoice.SUPPORT_SONG).bpm,
            OriginalMusic.select(JourneyKind.QUICK_BREATHING, MusicChoice.SUPPORT_SONG).bpm)
    }

    @Test fun eachArrangementRendersDeterministicFiniteTonalMusicWithSilentEdges() {
        val firstNotes = mapOf(JourneyKind.MORNING to 523.251, JourneyKind.NAP to 587.330,
            JourneyKind.PREPARATION to 523.251, JourneyKind.EVENING to 659.255)
        for ((kind, firstNote) in firstNotes) {
            val hashes = MusicChoice.entries.map { choice ->
                val piece = OriginalMusic.select(kind, choice)
                val pcm = OriginalMusic.render(piece)
                assertEquals(piece.frames, pcm.size)
                assertTrue(piece.durationMs in 38_000..56_000)
                assertEquals(0, pcm.first().toInt())
                assertEquals(0, pcm.last().toInt())
                assertTrue(pcm.all { it.toInt() in -27852..27852 })
                assertTrue("Expected written melody fundamental for $piece",
                    energy(pcm, firstNote) > energy(pcm, firstNote * 1.41421356) * 20)
                val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                pcm.forEach(bytes::putShort)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array())
                    .joinToString("") { "%02x".format(it) }
                println("ORIGINAL_MUSIC_PCM kind=$kind choice=$choice frames=${pcm.size} sha256=$hash")
                if (choice == MusicChoice.SUPPORT_SONG)
                    assertArrayEquals(pcm, OriginalMusic.render(piece))
                hash
            }
            assertEquals(3, hashes.distinct().size)
        }
    }

    private fun energy(pcm: ShortArray, hz: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        for (i in 1000 until 6000) {
            val phase = 2 * PI * hz * i / OriginalMusic.SAMPLE_RATE
            real += pcm[i] * cos(phase)
            imaginary += pcm[i] * sin(phase)
        }
        return real * real + imaginary * imaginary
    }
}
