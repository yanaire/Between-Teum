package dev.pleos.starter.teum.preferences

import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.time.DayOfWeek

internal object PreferenceJson {
    fun encode(value: TeumPreferences): String {
        val output = StringWriter()
        JsonWriter(output).use { w ->
            w.beginObject()
            w.name("version").value(1)
            for ((key, place) in listOf("company" to value.company, "home" to value.home)) {
                w.name("${key}Label").value(place.label)
                place.coordinates?.let {
                    w.name("${key}Latitude").value(it.latitude)
                    w.name("${key}Longitude").value(it.longitude)
                }
            }
            value.schedule.workStartMinute?.let { w.name("workStartMinute").value(it) }
            value.schedule.lunchReturnMinute?.let { w.name("lunchReturnMinute").value(it) }
            value.schedule.walkingReserveMinutes?.let { w.name("walkingReserveMinutes").value(it) }
            w.name("workFromHomeDays").value(value.schedule.workFromHomeDays.sortedBy { it.value }.joinToString(",") { it.name })
            w.name("restDegrees").value(value.backrest.restDegrees)
            w.name("napDegrees").value(value.backrest.napDegrees)
            w.name("restSound").value(value.restSound.name)
            w.name("voiceGuide").value(value.voiceGuide)
            w.name("morning").value(value.notifications.morning)
            w.name("nap").value(value.notifications.nap)
            w.name("preparation").value(value.notifications.preparation)
            w.name("evening").value(value.notifications.evening)
            w.name("askBeforeHomeArrival").value(value.notifications.askBeforeHomeArrival)
            value.atmosphere?.let { w.name("atmosphere").value(it.name) }
            w.name("deleteEveningStoryAfterNextMorning").value(value.privacy.deleteEveningStoryAfterNextMorning)
            w.endObject()
        }
        return output.toString()
    }

    fun decode(raw: String): TeumPreferences? = try {
        JsonReader(StringReader(raw)).use { r ->
            r.strictness = Strictness.STRICT
            val fields = mutableMapOf<String, JsonPrimitive>()
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                require(key !in fields)
                fields[key] = when (r.peek()) {
                    JsonToken.STRING -> JsonPrimitive(r.nextString())
                    JsonToken.BOOLEAN -> JsonPrimitive(r.nextBoolean())
                    JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(r.nextString()))
                    else -> throw IllegalArgumentException("Expected scalar preference")
                }
            }
            r.endObject()
            require(r.peek() == JsonToken.END_DOCUMENT)
            fun text(key: String): String? = fields[key]?.let { require(it.isString); it.asString }
            fun integer(key: String): Int? = fields[key]?.let {
                require(it.isNumber)
                require(it.asString.matches(Regex("-?(0|[1-9][0-9]*)")))
                it.asBigDecimal.intValueExact()
            }
            fun flag(key: String, default: Boolean): Boolean = fields[key]?.let {
                require(it.isBoolean); it.asBoolean
            } ?: default
            fun place(key: String): SavedPlace {
                val lat = fields["${key}Latitude"]
                val lon = fields["${key}Longitude"]
                require((lat == null) == (lon == null))
                val coordinates = if (lat == null || lon == null) null else {
                    require(lat.isNumber && lon.isNumber)
                    PlaceCoordinates(lat.asDouble, lon.asDouble)
                }
                return SavedPlace(text("${key}Label") ?: "", coordinates)
            }
            require(integer("version") == 1)
            val days = text("workFromHomeDays").orEmpty().let {
                if (it.isEmpty()) emptySet() else it.split(",").map(DayOfWeek::valueOf).toSet()
            }
            TeumPreferences(
                company = place("company"), home = place("home"),
                schedule = DailySchedule(integer("workStartMinute"), integer("lunchReturnMinute"), integer("walkingReserveMinutes"), days),
                backrest = BackrestPreferences(integer("restDegrees") ?: 118, integer("napDegrees") ?: 132),
                restSound = text("restSound")?.let(RestSound::valueOf) ?: RestSound.NONE,
                voiceGuide = flag("voiceGuide", true),
                notifications = NotificationPreferences(flag("morning", true), flag("nap", true),
                    flag("preparation", true), flag("evening", true), flag("askBeforeHomeArrival", true)),
                atmosphere = text("atmosphere")?.let(Atmosphere::valueOf),
                privacy = PrivacyPreferences(flag("deleteEveningStoryAfterNextMorning", true))
            )
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
