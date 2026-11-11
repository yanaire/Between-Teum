package dev.pleos.starter.teum.journey

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dev.pleos.starter.teum.records.JourneyFacts
import dev.pleos.starter.teum.records.JourneyFactsJson
import java.io.StringReader
import java.io.StringWriter
import java.io.IOException

/** Atomic durable replace/clear. Throw on failure; reads never erase malformed input. */
interface JourneySnapshotStore {
    fun load(): String?
    fun save(json: String)
    fun clear()
}

class InvalidJourneySnapshot(cause: Exception) : IllegalArgumentException("Invalid journey snapshot", cause)

/** Strict versioned JSON; invalid snapshots throw and remain untouched for diagnosis/discard. */
object JourneySnapshotCodec {
    fun encode(s: JourneySnapshot): String {
        s.validate()
        val output = StringWriter()
        JsonWriter(output).use { w ->
            w.beginObject()
            w.name("version").value(1)
            w.name("route").value(s.plan.route.name)
            w.name("napMinutes").value(s.plan.napMinutes)
            w.name("sessionId").value(s.sessionId)
            w.name("recordId").value(s.recordId)
            w.name("phase").value(s.phase.name)
            w.name("status").value(s.status.name)
            w.name("facts")
            JourneyFactsJson.write(w, s.facts)
            w.name("elapsedMs").value(s.elapsedMs)
            w.name("phaseStartedMs").value(s.phaseStartedMs)
            w.name("observedWallMs").value(s.observedWallMs)
            w.name("completedMask").value(s.completedMask)
            s.historicalSteps?.let { credits ->
                w.name("historicalSteps").value(credits.map { it.name }.sorted().joinToString(","))
            }
            s.feeling?.let { w.name("feeling").value(it.name) }
            s.atmosphere?.let { w.name("atmosphere").value(it.name) }
            s.musicChoice?.let { w.name("musicChoice").value(it.name) }
            w.name("playback").value(s.playback.name)
            w.name("speechCapture").value(s.speechCapture.name)
            w.name("awakeAcknowledged").value(s.awakeAcknowledged)
            w.name("eyesClosed").value(s.eyesClosed)
            w.name("revision").value(s.revision)
            s.interruption?.let { w.name("interruption").value(it.name) }
            s.napStartedMs?.let { w.name("napStartedMs").value(it) }
            s.napSleptMs?.let { w.name("napSleptMs").value(it) }
            w.endObject()
        }
        return output.toString()
    }

    fun decode(raw: String): JourneySnapshot {
        return try {
            read(raw)
        } catch (e: IOException) {
            throw InvalidJourneySnapshot(e)
        } catch (e: IllegalArgumentException) {
            throw InvalidJourneySnapshot(e)
        } catch (e: IllegalStateException) {
            throw InvalidJourneySnapshot(e)
        } catch (e: ArithmeticException) {
            throw InvalidJourneySnapshot(e)
        }
    }

    private fun read(raw: String): JourneySnapshot {
        require(raw.length <= 65_536)
        val strings = mutableMapOf<String, String>()
        val numbers = mutableMapOf<String, Long>()
        val flags = mutableMapOf<String, Boolean>()
        val seen = mutableSetOf<String>()
        var facts: JourneyFacts? = null
        JsonReader(StringReader(raw)).use { r ->
            r.strictness = Strictness.STRICT
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                require(seen.add(key)) { "Duplicate snapshot key" }
                when (key) {
                    "facts" -> facts = JourneyFactsJson.read(r)
                    "route", "sessionId", "recordId", "phase", "status", "feeling", "atmosphere",
                    "musicChoice", "playback", "speechCapture", "interruption", "historicalSteps" -> {
                        require(r.peek() == JsonToken.STRING)
                        strings[key] = r.nextString()
                    }
                    "version", "napMinutes", "elapsedMs", "phaseStartedMs", "observedWallMs",
                    "completedMask", "revision", "napStartedMs", "napSleptMs" -> {
                        require(r.peek() == JsonToken.NUMBER)
                        val number = r.nextString()
                        require(number.matches(Regex("0|[1-9][0-9]*")))
                        numbers[key] = requireNotNull(number.toLongOrNull())
                    }
                    "awakeAcknowledged", "eyesClosed" -> {
                        require(r.peek() == JsonToken.BOOLEAN)
                        flags[key] = r.nextBoolean()
                    }
                    else -> throw IllegalArgumentException("Unknown snapshot field: $key")
                }
            }
            r.endObject()
            require(r.peek() == JsonToken.END_DOCUMENT)
        }
        fun string(key: String) = requireNotNull(strings[key])
        fun number(key: String) = requireNotNull(numbers[key])
        fun integer(key: String): Int = Math.toIntExact(number(key))
        require(number("version") == 1L)
        return JourneySnapshot(
            plan = JourneyPlan(JourneyRoute.valueOf(string("route")), integer("napMinutes")),
            sessionId = string("sessionId"), recordId = string("recordId"),
            phase = JourneyPhase.valueOf(string("phase")), status = JourneyStatus.valueOf(string("status")),
            facts = requireNotNull(facts), elapsedMs = number("elapsedMs"), phaseStartedMs = number("phaseStartedMs"),
            observedWallMs = number("observedWallMs"), completedMask = integer("completedMask"),
            feeling = strings["feeling"]?.let(Feeling::valueOf),
            atmosphere = strings["atmosphere"]?.let(JourneyAtmosphere::valueOf),
            musicChoice = strings["musicChoice"]?.let(MusicChoice::valueOf),
            playback = PlaybackState.valueOf(string("playback")), speechCapture = SpeechCapture.valueOf(string("speechCapture")),
            awakeAcknowledged = requireNotNull(flags["awakeAcknowledged"]), eyesClosed = requireNotNull(flags["eyesClosed"]),
            revision = number("revision"), interruption = strings["interruption"]?.let(JourneyInterruption::valueOf),
            napStartedMs = numbers["napStartedMs"], napSleptMs = numbers["napSleptMs"],
            historicalSteps = strings["historicalSteps"]?.let { value ->
                val names = if (value.isEmpty()) emptyList() else value.split(",")
                require(names.distinct().size == names.size)
                names.map(JourneyPhase::valueOf).toSet()
            }
        ).also { it.validate() }
    }
}
