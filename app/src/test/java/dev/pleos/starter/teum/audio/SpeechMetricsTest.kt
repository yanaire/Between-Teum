package dev.pleos.starter.teum.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class SpeechMetricsTest {
    @Test fun emptyCaptureHasNoInventedMeasurements() {
        val measured = SpeechMetrics().measurement()
        assertEquals(0L, measured.recordingDurationMs)
        assertNull(measured.relativeLoudness)
        assertNull(measured.voicedDurationMs)
        assertNull(measured.recognizedText)
        assertNull(measured.recognizedTokenCount)
        assertNull(measured.recognizedFillerCount)
        measured.validate()
    }

    @Test fun rmsUsesSampleEnergyAcrossUnequalReadChunks() {
        val metrics = SpeechMetrics(1000)
        metrics.append(shortArrayOf(16384))
        metrics.append(shortArrayOf(0, 0, 0))
        val measured = metrics.measurement()
        assertEquals(4L, measured.recordingDurationMs)
        assertEquals(0.25, measured.relativeLoudness!!, 1e-12)
        assertNull(measured.voicedDurationMs)
    }

    @Test fun pcmExtremesStayNormalizedWithoutShortOverflow() {
        val metrics = SpeechMetrics(1000)
        metrics.append(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0))
        val measured = metrics.measurement()
        assertEquals(sqrt((1.0 + (32767.0 / 32768) * (32767.0 / 32768)) / 3),
            measured.relativeLoudness!!, 1e-12)
        measured.validate()
    }

    @Test fun readCountExcludesUnusedBufferTail() {
        val metrics = SpeechMetrics(1000)
        metrics.append(shortArrayOf(0, Short.MIN_VALUE), 1)
        val measured = metrics.measurement()
        assertEquals(1L, measured.recordingDurationMs)
        assertEquals(0.0, measured.relativeLoudness!!, 0.0)
    }

    @Test fun transcriptionCountsWhitespaceTokensAndOnlyStandaloneLexicalFillers() {
        val metrics = SpeechMetrics(1000)
        metrics.append(ShortArray(60000))
        val text = "  음,\t음악은\u00a0좋아.\nUM! 어… 그날 저 \"저\" uh-huh . "
        val measured = metrics.measurement(text)
        assertEquals(text, measured.recognizedText)
        assertEquals(10, measured.recognizedTokenCount)
        assertEquals(5, measured.recognizedFillerCount)
        assertEquals(10.0, measured.recognizedTokensPerMinute()!!, 0.0)
        measured.validate()
    }

    @Test fun knownEmptyRecognitionDiffersFromUnavailableRecognition() {
        val measured = SpeechMetrics().measurement(" \n\u2003")
        assertEquals(0, measured.recognizedTokenCount)
        assertEquals(0, measured.recognizedFillerCount)
        assertNull(measured.recognizedTokensPerMinute())
    }

    @Test fun separateRoundsNeverReuseSamplesOrTranscription() {
        val first = SpeechMetrics(1000).also { it.append(ShortArray(1000) { 16384 }) }.measurement("음 안녕")
        val second = SpeechMetrics(1000).also { it.append(ShortArray(2000)) }.measurement("안녕")
        assertEquals(1000L, first.recordingDurationMs)
        assertEquals(2000L, second.recordingDurationMs)
        assertEquals(0.5, first.relativeLoudness!!, 0.0)
        assertEquals(0.0, second.relativeLoudness!!, 0.0)
        assertEquals(1, first.recognizedFillerCount)
        assertEquals(0, second.recognizedFillerCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidReadCountIsRejected() {
        SpeechMetrics().append(shortArrayOf(1), 2)
    }
}
