package dev.pleos.starter.teum.records

import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

enum class JourneyKind { MORNING, NAP, PREPARATION, EVENING, QUICK_BREATHING }
enum class RecordTimeBasis { ACTIVITY_FINISH, ACTIVITY_START, SAVED_TIME, UNDATED }

/**
 * Only observed measurements. Relative loudness is a dimensionless RMS
 * amplitude ratio (0..1) from normalized PCM, never calibrated dB SPL.
 * Pace, when available, is recognized whitespace-delimited tokens per minute
 * of actual recording, not a claim about syllables or fluency.
 */
data class SpeechMeasurement(
    val recordingDurationMs: Long,
    val voicedDurationMs: Long? = null,
    val relativeLoudness: Double? = null,
    val recognizedText: String? = null,
    val recognizedFillerCount: Int? = null,
    val recognizedTokenCount: Int? = null
) {
    fun recognizedTokensPerMinute(): Double? = recognizedTokenCount?.let {
        if (recordingDurationMs == 0L) null else it * 60000.0 / recordingDurationMs
    }

    fun validate() {
        require(recordingDurationMs >= 0)
        require(voicedDurationMs == null || voicedDurationMs in 0..recordingDurationMs)
        require(relativeLoudness == null || (relativeLoudness.isFinite() && relativeLoudness in 0.0..1.0))
        require(recognizedFillerCount == null || (recognizedText != null && recognizedFillerCount >= 0))
        require(recognizedTokenCount == null || (recognizedText != null && recognizedTokenCount >= 0))
    }
}

data class EveningReflection(
    val story: String? = null,
    val greeting: String? = null,
    val consumedAtEpochMs: Long? = null
)

data class JourneyFacts(
    val kind: JourneyKind? = null,
    val nativeActivityId: String? = null,
    val startedAtEpochMs: Long? = null,
    val finishedAtEpochMs: Long? = null,
    val mood: String? = null,
    val intention: String? = null,
    val reflection: EveningReflection? = null,
    val firstSpeech: SpeechMeasurement? = null,
    val secondSpeech: SpeechMeasurement? = null,
    val atmosphere: String? = null
) {
    fun validate() {
        require(nativeActivityId == null || nativeActivityId.isNotBlank())
        require(atmosphere == null || atmosphere in setOf("CALM", "FIRM", "WARM", "FOCUS", "FLEX"))
        require(startedAtEpochMs == null || startedAtEpochMs > 0)
        require(finishedAtEpochMs == null || finishedAtEpochMs > 0)
        require(startedAtEpochMs == null || finishedAtEpochMs == null || finishedAtEpochMs >= startedAtEpochMs)
        require(reflection == null || kind == JourneyKind.EVENING)
        require(reflection?.consumedAtEpochMs == null || reflection.consumedAtEpochMs > 0)
        firstSpeech?.validate()
        secondSpeech?.validate()
    }
}

fun ActivityRecord.timeBasis(): RecordTimeBasis = when {
    journeyFacts?.finishedAtEpochMs != null -> RecordTimeBasis.ACTIVITY_FINISH
    journeyFacts?.startedAtEpochMs != null -> RecordTimeBasis.ACTIVITY_START
    createdAtEpochMs > 0 -> RecordTimeBasis.SAVED_TIME
    else -> RecordTimeBasis.UNDATED
}

fun ActivityRecord.knownTimeEpochMs(): Long? =
    journeyFacts?.finishedAtEpochMs ?: journeyFacts?.startedAtEpochMs ?: createdAtEpochMs.takeIf { it > 0 }

internal object JourneyFactsJson {
    fun write(w: JsonWriter, facts: JourneyFacts) {
        facts.validate()
        w.beginObject()
        fun text(key: String, value: String?) { value?.let { w.name(key).value(it) } }
        fun number(key: String, value: Number?) { value?.let { w.name(key).value(it) } }
        text("kind", facts.kind?.name)
        text("nativeActivityId", facts.nativeActivityId)
        number("startedAtEpochMs", facts.startedAtEpochMs)
        number("finishedAtEpochMs", facts.finishedAtEpochMs)
        text("mood", facts.mood)
        text("atmosphere", facts.atmosphere)
        text("intention", facts.intention)
        facts.reflection?.let {
            w.name("hasReflection").value(true)
            text("story", it.story)
            text("greeting", it.greeting)
            number("consumedAtEpochMs", it.consumedAtEpochMs)
        }
        for ((key, speech) in listOf("first" to facts.firstSpeech, "second" to facts.secondSpeech)) {
            speech?.let {
                number("${key}RecordingDurationMs", it.recordingDurationMs)
                number("${key}VoicedDurationMs", it.voicedDurationMs)
                number("${key}RelativeLoudness", it.relativeLoudness)
                text("${key}RecognizedText", it.recognizedText)
                number("${key}RecognizedFillerCount", it.recognizedFillerCount)
                number("${key}RecognizedTokenCount", it.recognizedTokenCount)
            }
        }
        w.endObject()
    }

    fun read(r: JsonReader): JourneyFacts {
        val fields = mutableMapOf<String, JsonPrimitive>()
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            require(key !in fields)
            fields[key] = when (r.peek()) {
                JsonToken.STRING -> JsonPrimitive(r.nextString())
                JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(r.nextString()))
                JsonToken.BOOLEAN -> JsonPrimitive(r.nextBoolean())
                else -> throw IllegalArgumentException("Expected scalar journey fact")
            }
        }
        r.endObject()
        fun text(key: String): String? = fields[key]?.let { require(it.isString); it.asString }
        fun long(key: String): Long? = fields[key]?.let {
            require(it.isNumber && it.asString.matches(Regex("-?(0|[1-9][0-9]*)")))
            it.asBigDecimal.longValueExact()
        }
        fun int(key: String): Int? = long(key)?.let { require(it in 0..Int.MAX_VALUE.toLong()); it.toInt() }
        fun speech(key: String): SpeechMeasurement? {
            val duration = long("${key}RecordingDurationMs")
            require(duration != null || fields.keys.none { it.startsWith(key) })
            return duration?.let {
                SpeechMeasurement(it, long("${key}VoicedDurationMs"),
                    fields["${key}RelativeLoudness"]?.let { n -> require(n.isNumber); n.asDouble },
                    text("${key}RecognizedText"), int("${key}RecognizedFillerCount"), int("${key}RecognizedTokenCount"))
            }
        }
        val hasReflection = fields["hasReflection"]?.let { require(it.isBoolean); it.asBoolean } ?: false
        require(hasReflection || listOf("story", "greeting", "consumedAtEpochMs").none { it in fields })
        return JourneyFacts(
            kind = text("kind")?.let(JourneyKind::valueOf),
            nativeActivityId = text("nativeActivityId"),
            startedAtEpochMs = long("startedAtEpochMs"), finishedAtEpochMs = long("finishedAtEpochMs"),
            mood = text("mood"), intention = text("intention"),
            reflection = if (hasReflection) EveningReflection(text("story"), text("greeting"), long("consumedAtEpochMs")) else null,
            firstSpeech = speech("first"), secondSpeech = speech("second"),
            atmosphere = text("atmosphere")
        ).also { it.validate() }
    }
}
