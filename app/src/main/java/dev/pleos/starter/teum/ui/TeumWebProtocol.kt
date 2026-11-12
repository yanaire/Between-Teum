package dev.pleos.starter.teum.ui

import com.google.gson.Strictness
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import dev.pleos.starter.teum.preferences.Atmosphere
import dev.pleos.starter.teum.preferences.RestSound
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import dev.pleos.starter.teum.journey.*
import dev.pleos.starter.teum.preferences.TeumPreferences
import dev.pleos.starter.teum.location.ArrivalSuggestion
import dev.pleos.starter.teum.location.MeetingFact
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.vehicle.VehicleSafety

enum class WebJourney { MORNING, LUNCH, PREPARATION, EVENING, QUICK }
enum class WebVehicleFeature { HVAC_TEMPERATURE, AMBIENT_BRIGHTNESS, SEAT_POSITION, WINDOW_POSITION }
enum class WebPermission { MICROPHONE, LOCATION }
enum class WebPreferenceFlag {
    VOICE_GUIDE, MORNING, NAP, PREPARATION, EVENING, ASK_BEFORE_HOME_ARRIVAL,
    DELETE_EVENING_STORY_AFTER_NEXT_MORNING
}
enum class WebJourneyCommand {
    START, NEXT, PREVIOUS, SKIP, RECORD_SPEECH, STOP_SPEECH, RETRY_SPEECH,
    PLAY_MUSIC, CHANGE_MUSIC, WAKE_NOW, CLOSE_EYES, OPEN_EYES
}
enum class WebJourneyField { FEELING, INTENTION, STORY, ATMOSPHERE, NAP_MINUTES, MEETING_MINUTE, MUSIC_CHOICE }

/** Intents only. Native state remains authoritative for safety, timing and effects. */
sealed interface TeumWebAction {
    object ReadState : TeumWebAction
    data class AdmitArrival(val nonce: String) : TeumWebAction
    data class Navigate(val route: TeumWebRoute) : TeumWebAction
    data class SelectDay(val date: LocalDate) : TeumWebAction
    data class MeetingFeedback(val value: String) : TeumWebAction
    data class OpenJourney(val journey: WebJourney, val short: Boolean = false) : TeumWebAction
    data class ReconfigureEntry(val choice: EntryChoice) : TeumWebAction
    data class SkipSuggestion(val journey: WebJourney) : TeumWebAction
    object SaveJourney : TeumWebAction
    object FinishEarly : TeumWebAction
    object AcknowledgeWake : TeumWebAction
    data class ApproveRestore(val consent: Boolean) : TeumWebAction
    data class SaveRecord(val note: String, val nextAction: String, val recall: Boolean) : TeumWebAction
    data class DeleteRecord(val recordId: String) : TeumWebAction
    object ClearRecords : TeumWebAction
    data class SelectRecall(val recordId: String) : TeumWebAction
    data class HistoryConsent(val enabled: Boolean) : TeumWebAction
    data class SoundEnabled(val enabled: Boolean) : TeumWebAction
    data class InspectVehicle(val feature: WebVehicleFeature) : TeumWebAction
    data class PreviewVehicle(val feature: WebVehicleFeature, val value: Double) : TeumWebAction
    data class ConfirmVehicle(val previewId: Long) : TeumWebAction
    data class SavePlaceLabel(val home: Boolean, val label: String) : TeumWebAction
    data class CaptureLocation(val home: Boolean) : TeumWebAction
    data class SaveSchedule(val workStartMinute: Int?, val lunchReturnMinute: Int?, val walkingReserveMinutes: Int?) : TeumWebAction
    data class WorkFromHomeDay(val day: DayOfWeek, val enabled: Boolean) : TeumWebAction
    data class SaveBackrest(val restDegrees: Int, val napDegrees: Int) : TeumWebAction
    data class SelectSound(val sound: RestSound) : TeumWebAction
    data class SetPreferenceFlag(val flag: WebPreferenceFlag, val enabled: Boolean) : TeumWebAction
    data class SelectAtmosphere(val atmosphere: Atmosphere) : TeumWebAction
    data class RequestPermission(val permission: WebPermission) : TeumWebAction
    object CompleteOnboarding : TeumWebAction
    data class JourneyCommand(val command: WebJourneyCommand) : TeumWebAction
    data class JourneyInput(val field: WebJourneyField, val value: String) : TeumWebAction
}

data class WebJourneyToken(val sessionId: String, val revision: Long)
data class TeumWebRequest(
    val requestId: String,
    val epoch: Long,
    val action: TeumWebAction,
    val journeyToken: WebJourneyToken? = null,
    val arrivalToken: String? = null
)

data class PreviousTransition(
    val requestId: String, val requestEpoch: Long, val journeyId: String,
    val fromRevision: Long, val toRevision: Long, val fromScreen: String, val toScreen: String
)

/** Strict, bounded flat JSON. Rejects duplicate keys and browser-supplied native facts. */
object TeumWebProtocol {
    fun previousTransition(request: TeumWebRequest, before: JourneySnapshot,
        after: JourneySnapshot): PreviousTransition? {
        val command = request.action as? TeumWebAction.JourneyCommand ?: return null
        if (command.command != WebJourneyCommand.PREVIOUS ||
            request.journeyToken != WebJourneyToken(before.sessionId, before.revision) ||
            !before.active || !after.active || before.sessionId != after.sessionId ||
            before.plan != after.plan || before.phase == after.phase ||
            after.revision - before.revision != 1L) return null
        return PreviousTransition(request.requestId, request.epoch, before.sessionId,
            before.revision, after.revision, before.screenId, after.screenId)
    }

    fun stateEnvelope(state: JsonObject, epoch: Long, previous: PreviousTransition? = null): JsonObject {
        val token = state["journeyToken"]?.takeIf { it.isJsonObject }?.asJsonObject
        return JsonObject().apply {
            addProperty("type", "state")
            addProperty("epoch", epoch)
            add("state", state.deepCopy())
            previous?.takeIf {
                it.requestEpoch == epoch && state["journeyActive"]?.asBoolean == true && token != null &&
                    token["sessionId"]?.asString == it.journeyId &&
                    token["revision"]?.asLong == it.toRevision &&
                    state["screen"]?.asString == it.toScreen
            }?.let {
                add("previousTransition", JsonObject().apply {
                    addProperty("requestId", it.requestId)
                    addProperty("requestEpoch", it.requestEpoch)
                    addProperty("journeyId", it.journeyId)
                    addProperty("fromRevision", it.fromRevision)
                    addProperty("toRevision", it.toRevision)
                    addProperty("fromScreen", it.fromScreen)
                    addProperty("toScreen", state["screen"].asString)
                })
            }
        }
    }

    fun decode(raw: String): TeumWebRequest? {
        if (raw.length > 32_768 || raw.toByteArray(Charsets.UTF_8).size > 32_768) return null
        return try {
            JsonReader(StringReader(raw)).use { reader ->
                reader.strictness = Strictness.STRICT
                val fields = readFields(reader)
                require(reader.peek() == JsonToken.END_DOCUMENT)
                val id = fields.text("requestId", 96)
                require(id.matches(Regex("[A-Za-z0-9_-]+")))
                val epoch = fields.integer("epoch")
                require(epoch >= 0)
                val token = if (fields.has("journeyId") || fields.has("journeyRevision")) {
                    val sessionId = fields.text("journeyId", 128)
                    require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
                    val revision = fields.integer("journeyRevision")
                    require(revision >= 0)
                    WebJourneyToken(sessionId, revision)
                } else null
                val arrival = if (fields.has("arrivalToken"))
                    fields.text("arrivalToken", 256).also {
                        require(it.matches(Regex("[A-Za-z0-9_+:/.-]{1,256}")))
                    } else null
                val action = readAction(fields)
                if (action is TeumWebAction.JourneyCommand && action.command == WebJourneyCommand.PREVIOUS)
                    require(token != null)
                if (action is TeumWebAction.ReconfigureEntry) require(token != null)
                TeumWebRequest(id, epoch, action, token, arrival)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun readAction(fields: Fields): TeumWebAction {
        val action = fields.text("action", 40)
        return when (action) {
            "reconfigureEntry" -> {
                fields.keys("choice")
                TeumWebAction.ReconfigureEntry(EntryChoice.valueOf(fields.text("choice", 24)))
            }
            "admitArrival" -> {
                fields.keys("nonce")
                TeumWebAction.AdmitArrival(fields.text("nonce", 64).also {
                    require(it.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                })
            }
            "navigate" -> {
                fields.keys("route")
                TeumWebAction.Navigate(requireNotNull(TeumWebRoute.fromId(fields.text("route", 16))))
            }
            "selectDay" -> {
                fields.keys("date")
                val date = fields.text("date", 10)
                require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                TeumWebAction.SelectDay(LocalDate.parse(date))
            }
            "meetingFeedback" -> {
                fields.keys("value")
                val value = fields.text("value", 8)
                require(value in setOf("well", "okay", "hard"))
                TeumWebAction.MeetingFeedback(value)
            }
            "state" -> {
                fields.keys()
                TeumWebAction.ReadState
            }
            "openJourney", "openShortJourney" -> {
                fields.keys("journey")
                val journey = when (fields.text("journey", 16)) {
                    "am" -> WebJourney.MORNING
                    "noon" -> WebJourney.LUNCH
                    "meet" -> WebJourney.PREPARATION
                    "pm" -> WebJourney.EVENING
                    "quick" -> WebJourney.QUICK
                    else -> throw IllegalArgumentException("Unknown journey")
                }
                val short = action == "openShortJourney"
                require(!short || journey in setOf(WebJourney.MORNING, WebJourney.EVENING))
                TeumWebAction.OpenJourney(journey, short)
            }
            "skipSuggestion" -> {
                fields.keys("journey")
                val journey = when (fields.text("journey", 16)) {
                    "am" -> WebJourney.MORNING
                    "noon" -> WebJourney.LUNCH
                    "meet" -> WebJourney.PREPARATION
                    "pm" -> WebJourney.EVENING
                    else -> throw IllegalArgumentException("Unknown suggestion")
                }
                TeumWebAction.SkipSuggestion(journey)
            }
            "saveJourney" -> {
                fields.keys()
                TeumWebAction.SaveJourney
            }
            "finishEarly" -> {
                fields.keys()
                TeumWebAction.FinishEarly
            }
            "acknowledgeWake" -> {
                fields.keys()
                TeumWebAction.AcknowledgeWake
            }
            "approveRestore" -> {
                fields.keys("consent")
                TeumWebAction.ApproveRestore(fields.boolean("consent"))
            }
            "saveRecord" -> {
                fields.keys("note", "nextAction", "recall")
                TeumWebAction.SaveRecord(
                    fields.text("note", 4000), fields.text("nextAction", 1000), fields.boolean("recall")
                )
            }
            "deleteRecord" -> {
                fields.keys("recordId")
                TeumWebAction.DeleteRecord(fields.recordId())
            }
            "clearRecords" -> {
                fields.keys()
                TeumWebAction.ClearRecords
            }
            "selectRecall" -> {
                fields.keys("recordId")
                TeumWebAction.SelectRecall(fields.recordId())
            }
            "historyConsent" -> {
                fields.keys("enabled")
                TeumWebAction.HistoryConsent(fields.boolean("enabled"))
            }
            "soundEnabled" -> {
                fields.keys("enabled")
                TeumWebAction.SoundEnabled(fields.boolean("enabled"))
            }
            "inspectVehicle" -> {
                fields.keys("feature")
                TeumWebAction.InspectVehicle(WebVehicleFeature.valueOf(fields.text("feature", 40)))
            }
            "previewVehicle" -> {
                fields.keys("feature", "value")
                val feature = WebVehicleFeature.valueOf(fields.text("feature", 40))
                TeumWebAction.PreviewVehicle(feature, fields.decimal("value"))
            }
            "confirmVehicle" -> {
                fields.keys("previewId")
                val previewId = fields.integer("previewId")
                require(previewId > 0)
                TeumWebAction.ConfirmVehicle(previewId)
            }
            "savePlaceLabel" -> {
                fields.keys("home", "label")
                TeumWebAction.SavePlaceLabel(fields.boolean("home"), fields.text("label", 200))
            }
            "captureLocation" -> {
                fields.keys("home")
                TeumWebAction.CaptureLocation(fields.boolean("home"))
            }
            "saveSchedule" -> {
                fields.keys("workStartMinute", "lunchReturnMinute", "walkingReserveMinutes")
                fun optional(name: String, range: IntRange): Int? {
                    val value = fields.integer(name)
                    require(value == -1L || value in range.first.toLong()..range.last.toLong())
                    return value.takeUnless { it == -1L }?.toInt()
                }
                TeumWebAction.SaveSchedule(optional("workStartMinute", 0..1439),
                    optional("lunchReturnMinute", 0..1439), optional("walkingReserveMinutes", 1..10))
            }
            "saveBackrest" -> {
                fields.keys("restDegrees", "napDegrees")
                val rest = fields.integer("restDegrees")
                val nap = fields.integer("napDegrees")
                require(rest in 106L..146L && nap in 106L..160L)
                TeumWebAction.SaveBackrest(rest.toInt(), nap.toInt())
            }
            "workFromHomeDay" -> {
                fields.keys("day", "enabled")
                TeumWebAction.WorkFromHomeDay(DayOfWeek.valueOf(fields.text("day", 16)), fields.boolean("enabled"))
            }
            "selectSound" -> {
                fields.keys("sound")
                TeumWebAction.SelectSound(RestSound.valueOf(fields.text("sound", 24)))
            }
            "setPreferenceFlag" -> {
                fields.keys("flag", "enabled")
                TeumWebAction.SetPreferenceFlag(WebPreferenceFlag.valueOf(fields.text("flag", 48)), fields.boolean("enabled"))
            }
            "selectAtmosphere" -> {
                fields.keys("atmosphere")
                TeumWebAction.SelectAtmosphere(Atmosphere.valueOf(fields.text("atmosphere", 16)))
            }
            "requestPermission" -> {
                fields.keys("permission")
                TeumWebAction.RequestPermission(WebPermission.valueOf(fields.text("permission", 16)))
            }
            "completeOnboarding" -> {
                fields.keys()
                TeumWebAction.CompleteOnboarding
            }
            "journeyCommand" -> {
                fields.keys("command")
                TeumWebAction.JourneyCommand(WebJourneyCommand.valueOf(fields.text("command", 24)))
            }
            "journeyInput" -> {
                fields.keys("field", "value")
                TeumWebAction.JourneyInput(WebJourneyField.valueOf(fields.text("field", 24)), fields.text("value", 4000))
            }
            else -> throw IllegalArgumentException("Unknown action")
        }
    }

    private class Fields(
        val strings: Map<String, String>,
        val numbers: Map<String, String>,
        val booleans: Map<String, Boolean>
    ) {
        fun has(name: String): Boolean = name in strings || name in numbers || name in booleans

        fun keys(vararg payload: String) {
            val tokenKeys = if (has("journeyId") || has("journeyRevision"))
                setOf("journeyId", "journeyRevision") else emptySet()
            val arrivalKeys = if (has("arrivalToken")) setOf("arrivalToken") else emptySet()
            require(strings.keys + numbers.keys + booleans.keys ==
                setOf("requestId", "epoch", "action") + payload + tokenKeys + arrivalKeys)
        }

        fun text(name: String, maximum: Int): String =
            requireNotNull(strings[name]).also { require(it.length <= maximum) }

        fun integer(name: String): Long {
            val raw = requireNotNull(numbers[name])
            require(raw.matches(Regex("-?(0|[1-9][0-9]*)")))
            return requireNotNull(raw.toLongOrNull())
        }

        fun decimal(name: String): Double =
            requireNotNull(numbers[name]?.toDoubleOrNull()).also { require(it.isFinite()) }

        fun boolean(name: String): Boolean = requireNotNull(booleans[name])

        fun recordId(): String = text("recordId", 128).also {
            require(it.matches(Regex("[A-Za-z0-9_-]+")))
        }
    }

    private fun readFields(reader: JsonReader): Fields {
        val strings = mutableMapOf<String, String>()
        val numbers = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name))
            when (reader.peek()) {
                JsonToken.STRING -> strings[name] = reader.nextString()
                JsonToken.NUMBER -> numbers[name] = reader.nextString()
                JsonToken.BOOLEAN -> booleans[name] = reader.nextBoolean()
                else -> throw IllegalArgumentException("Expected scalar field")
            }
        }
        reader.endObject()
        return Fields(strings, numbers, booleans)
    }
}

/** Native entry boundary, shared by selection/resume and publication of actual availability. */
internal fun validateEntryChoice(
    owner: JourneySnapshot, choice: EntryChoice, preferences: TeumPreferences,
    meeting: MeetingFact?, now: ClockReading, safety: VehicleSafety, zone: ZoneId
): JourneyPlan {
    check(owner.active && owner.phase == JourneyPhase.ENTRY_REVIEW) { "No active entry review" }
    require(choice in owner.plan.entryChoices) { "No such original entry control" }
    require(now.wallMillis >= requireNotNull(owner.facts.startedAtEpochMs) && now.elapsedMillis >= 0)
    check(safety.blockedReason(now.elapsedMillis) == null) { "Fresh parking and UX observations required" }
    val selected = choice.plan
    val local = Instant.ofEpochMilli(now.wallMillis).atZone(zone)
    val schedule = preferences.schedule
    fun scheduledDeadline(minute: Int?): Long {
        require(minute != null) { "Saved deadline unavailable" }
        require(local.dayOfWeek !in schedule.workFromHomeDays) { "Work from home day" }
        val reserve = requireNotNull(schedule.walkingReserveMinutes) { "Walking reserve unavailable" }
        val wall = local.toLocalDate().atTime(minute / 60, minute % 60)
        require(zone.rules.getValidOffsets(wall).size == 1) { "Ambiguous or missing deadline" }
        return wall.atZone(zone).toInstant().toEpochMilli() - reserve * 60_000L
    }
    val deadline = when (owner.plan.route) {
        JourneyRoute.SHORT_MORNING -> scheduledDeadline(schedule.workStartMinute)
        JourneyRoute.NAP -> scheduledDeadline(schedule.lunchReturnMinute)
        JourneyRoute.PREPARATION -> requireNotNull(meeting) { "Saved meeting unavailable" }.let {
            require(it.zone == zone) { "Meeting timezone mismatch" }
            it.startsAt.toEpochMilli()
        }
        // These are explicit duration controls. There is no original evening deadline input.
        JourneyRoute.EVENING, JourneyRoute.SHORT_EVENING -> null
        else -> throw JourneyRejected("No original entry review")
    }
    val requiredMs = selected.nominalMinutes * 60_000L +
        if (selected.route == JourneyRoute.QUICK_BREATHING) 3000L else 0L
    if (deadline != null) require(deadline - now.wallMillis >= requiredMs) { "Selected plan exceeds available time" }
    return selected
}

internal fun entryDeclinesArrival(
    offered: ArrivalSuggestion, previous: JourneyPlan, selected: JourneyPlan
): Boolean = offered.slot.name != previous.kind.name || offered.slot.name != selected.kind.name
