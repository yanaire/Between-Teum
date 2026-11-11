package dev.pleos.starter.teum.audio

import dev.pleos.starter.teum.records.SpeechMeasurement
import java.util.Locale
import kotlin.math.sqrt

/** Accumulates actual mono PCM16 samples. Recognition and voiced duration remain unknown. */
class SpeechMetrics(private val sampleRate: Int = 16000) {
    private var samples = 0L
    private var squares = 0.0

    init { require(sampleRate > 0) }

    fun append(pcm: ShortArray, count: Int = pcm.size) {
        require(count in 0..pcm.size)
        for (index in 0 until count) {
            val amplitude = pcm[index].toDouble() / 32768.0
            squares += amplitude * amplitude
        }
        samples += count
    }

    fun measurement(text: String? = null): SpeechMeasurement {
        // Unicode whitespace-delimited tokens, including punctuation-only tokens,
        // match the records contract. Fillers are exact standalone tokens after
        // stripping boundary punctuation and lowercasing with Locale.ROOT.
        val tokens = text?.split(Regex("[\\s\\p{Z}]+"))?.filter { it.isNotEmpty() }
        val fillers = tokens?.count {
            it.trim { char -> !char.isLetterOrDigit() }.lowercase(Locale.ROOT) in FILLERS
        }
        return SpeechMeasurement(
            recordingDurationMs = samples * 1000 / sampleRate,
            voicedDurationMs = null,
            relativeLoudness = if (samples == 0L) null else sqrt(squares / samples).coerceIn(0.0, 1.0),
            recognizedText = text,
            recognizedFillerCount = fillers,
            recognizedTokenCount = tokens?.size
        )
    }

    companion object {
        /** Lexical occurrences only; not hesitation detection or a fluency score. */
        val FILLERS: Set<String> = setOf("음", "어", "아", "그", "저", "um", "uh", "erm", "hmm")
    }
}
