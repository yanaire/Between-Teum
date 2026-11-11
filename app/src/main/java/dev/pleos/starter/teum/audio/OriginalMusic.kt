package dev.pleos.starter.teum.audio

import dev.pleos.starter.teum.journey.MusicChoice
import dev.pleos.starter.teum.records.JourneyKind
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Original scores authored for Teum. No recordings, samples, history or network inputs. */
object OriginalMusic {
    const val SAMPLE_RATE = 22050

    data class Piece(val kind: JourneyKind, val arrangement: Int, val title: String, val bpm: Int) {
        val durationMs: Long get() = (64 * 60_000L / bpm) + 2000
        val frames: Int get() = (durationMs * SAMPLE_RATE / 1000).toInt()
    }

    fun select(kind: JourneyKind, choice: MusicChoice): Piece {
        // Keep persisted enum names compatible. RECENT is now an alternate arrangement,
        // never a claim that listening history was read.
        val arrangement = when (choice) {
            MusicChoice.SUPPORT_SONG -> 0
            MusicChoice.RECENT -> 1
            MusicChoice.RECOMMENDATION -> 2
        }
        val (title, bpm) = when (kind) {
            JourneyKind.MORNING, JourneyKind.QUICK_BREATHING -> "아침의 첫걸음" to 104
            JourneyKind.NAP -> "한낮의 쉼표" to 72
            JourneyKind.PREPARATION -> "다시 한 걸음" to 96
            JourneyKind.EVENING -> "하루를 접으며" to 80
        }
        return Piece(kind, arrangement, "$title · ${listOf("피아노", "벨", "플럭")[arrangement]}", bpm)
    }

    /** Sixteen bars in 4/4: a written melody, chord arpeggios and bass; finite, no loop. */
    fun render(piece: Piece): ShortArray {
        val mix = DoubleArray(piece.frames)
        val beat = 60.0 / piece.bpm
        val roots = when (piece.kind) {
            JourneyKind.MORNING, JourneyKind.QUICK_BREATHING -> intArrayOf(48, 55, 57, 53)
            JourneyKind.NAP -> intArrayOf(50, 46, 53, 48)
            JourneyKind.PREPARATION -> intArrayOf(48, 53, 57, 55)
            JourneyKind.EVENING -> intArrayOf(45, 53, 48, 55)
        }
        val melody = when (piece.kind) {
            JourneyKind.MORNING, JourneyKind.QUICK_BREATHING ->
                intArrayOf(72, 76, 79, 76, 74, 71, 67, 71, 69, 72, 76, 79, 77, 76, 72, 74)
            JourneyKind.NAP ->
                intArrayOf(74, 77, 81, 77, 74, 70, 65, 70, 69, 72, 77, 76, 72, 67, 64, 67)
            JourneyKind.PREPARATION ->
                intArrayOf(72, 74, 76, 79, 77, 76, 72, 69, 69, 72, 76, 81, 79, 74, 71, 72)
            JourneyKind.EVENING ->
                intArrayOf(76, 72, 69, 64, 65, 69, 72, 77, 76, 72, 67, 64, 67, 71, 74, 72)
        }
        fun note(midi: Int, start: Double, length: Double, gain: Double, voice: Int) {
            val first = (start * SAMPLE_RATE).toInt()
            val count = (length * SAMPLE_RATE).toInt()
            val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
            for (i in 0 until minOf(count, mix.size - first)) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = minOf(1.0, t / 0.012)
                val release = minOf(1.0, (count - 1 - i).toDouble() / (SAMPLE_RATE * 0.10))
                val phase = 2 * PI * frequency * t
                val wave = when (voice) {
                    0 -> sin(phase) + 0.25 * sin(phase * 2) * exp(-t * 4)
                    1 -> sin(phase) + 0.30 * sin(phase * 3) * exp(-t * 6)
                    else -> sin(phase) + 0.20 * sin(phase * 2) + 0.10 * sin(phase * 4)
                }
                mix[first + i] += wave * attack * release * exp(-t * 1.8) * gain
            }
        }
        for (bar in 0 until 16) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Score cancelled")
            val root = roots[bar % 4]
            val minor = when (piece.kind) {
                JourneyKind.NAP -> bar % 4 == 0
                JourneyKind.EVENING -> bar % 4 == 0
                else -> root == 57
            }
            val chord = intArrayOf(root, root + if (minor) 3 else 4, root + 7, root + 12)
            note(root - 12, bar * 4 * beat, beat * 3.8, 0.16, 0)
            for (step in 0 until 8) {
                val pitch = chord[(step + piece.arrangement) % 4] + 12
                note(pitch, (bar * 4 + step * 0.5) * beat, beat * 0.9, 0.09, piece.arrangement)
            }
            for (step in 0 until 4) {
                val pitch = melody[(bar % 4) * 4 + step] + if (bar in 8..11) 12 else 0
                note(pitch, (bar * 4 + step) * beat, beat * 0.85, 0.20, piece.arrangement)
            }
        }
        // Written final tonic, then a fade to exact silence. No repeated playback.
        note(roots[0] + 12, 64 * beat, 1.8, 0.18, piece.arrangement)
        return ShortArray(mix.size) { i ->
            val fade = minOf(1.0, i / (SAMPLE_RATE * 0.02),
                (mix.size - 1 - i) / (SAMPLE_RATE * 0.15))
            (mix[i].coerceIn(-0.85, 0.85) * fade * 32767).toInt().toShort()
        }
    }
}
