package dev.pleos.starter.teum.records

import dev.pleos.starter.teum.content.SituationIds
import org.junit.Assert.*
import org.junit.Test

class JourneyFactsTest {
    private fun record(facts: JourneyFacts? = null): ActivityRecord = newActivityRecord(
        SituationIds.COMMUTE_EVENING, "Journey", 1, listOf(0), 19,
        ActivityOutcome.COMPLETED, nowEpochMs = 100_000, journeyFacts = facts
    )

    @Test
    fun optionalFactsPreserveRealTimestampsTextAndTwoSpeechRounds() {
        val expected = record(JourneyFacts(
            JourneyKind.EVENING, "native-session-1", 10_000, 29_000, "tired", "One thing",
            EveningReflection("Actual story\n\"quoted\"", "Derived greeting"),
            SpeechMeasurement(12_345, 9_876, 0.25, "well hello", 1, 2),
            SpeechMeasurement(10_234, recognizedText = "hello", recognizedTokenCount = 1),
            atmosphere = "FOCUS"
        ))
        assertEquals(expected, RecordJson.decode(RecordJson.encode(expected)))
    }

    @Test
    fun oldRecordsRemainSavedTimeOrUndatedWithUnknownMetricsAbsent() {
        val old = record()
        val decoded = requireNotNull(RecordJson.decode(RecordJson.encode(old)))
        assertEquals(RecordTimeBasis.SAVED_TIME, decoded.timeBasis())
        assertNull(decoded.journeyFacts)
        assertEquals(100_000L, decoded.knownTimeEpochMs())
        assertEquals(RecordTimeBasis.UNDATED, old.copy(createdAtEpochMs = 0).timeBasis())
        assertNull(old.copy(createdAtEpochMs = 0).knownTimeEpochMs())
    }

    @Test
    fun timestampBasisUsesNativeFinishBeforeStartBeforeSave() {
        val facts = JourneyFacts(startedAtEpochMs = 10_000, finishedAtEpochMs = 29_000)
        assertEquals(29_000L, record(facts).knownTimeEpochMs())
        assertEquals(RecordTimeBasis.ACTIVITY_FINISH, record(facts).timeBasis())
        assertEquals(RecordTimeBasis.ACTIVITY_START, record(facts.copy(finishedAtEpochMs = null)).timeBasis())
        assertEquals(10_000L, record(facts.copy(finishedAtEpochMs = null)).knownTimeEpochMs())
    }

    @Test
    fun unobservedSpeechMetricsStayAbsentAndPaceUsesActualRecording() {
        val unknown = SpeechMeasurement(0)
        assertNull(unknown.recognizedTokensPerMinute())
        assertNull(unknown.voicedDurationMs)
        assertNull(unknown.relativeLoudness)
        val measured = SpeechMeasurement(30_000, recognizedText = "hello there", recognizedTokenCount = 2)
        assertEquals(4.0, requireNotNull(measured.recognizedTokensPerMinute()), 0.0)
    }

    @Test
    fun invalidNativeFactsAreRejectedWithoutLosingStrictLegacyDecoding() {
        val raw = RecordJson.encode(record(JourneyFacts(firstSpeech = SpeechMeasurement(100))))
        val invalid = listOf(
            raw.replace("\"firstRecordingDurationMs\":100", "\"firstRecordingDurationMs\":-1"),
            raw.replace("\"firstRecordingDurationMs\":100", "\"firstRecordingDurationMs\":1.0"),
            raw.replace("\"firstRecordingDurationMs\":100", "\"firstRecordingDurationMs\":100,\"firstVoicedDurationMs\":101"),
            raw.replace("\"firstRecordingDurationMs\":100", "\"firstRecordingDurationMs\":100,\"firstRelativeLoudness\":1.1"),
            raw.replace("\"firstRecordingDurationMs\":100", "\"firstRecordingDurationMs\":100,\"firstRecognizedFillerCount\":1"),
            raw.replace("\"firstRecordingDurationMs\":100", "\"firstRecordingDurationMs\":100,\"firstRecordingDurationMs\":100"),
            raw.replace("\"firstRecordingDurationMs\":100", "\"firstVoicedDurationMs\":10"),
            raw.replace("\"firstRecordingDurationMs\":100", "\"startedAtEpochMs\":20,\"finishedAtEpochMs\":10"),
            raw.replace("\"firstRecordingDurationMs\":100", "\"kind\":\"MORNING\",\"hasReflection\":true,\"story\":\"x\""),
            raw.replace("\"firstRecordingDurationMs\":100", "\"firstRecordingDurationMs\":9223372036854775808")
        )
        invalid.forEach { assertNull(it, RecordJson.decode(it)) }
    }
}
