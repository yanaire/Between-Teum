package dev.pleos.starter.teum.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import dev.pleos.starter.teum.journey.JourneyPhase
import dev.pleos.starter.teum.journey.JourneySnapshot
import dev.pleos.starter.teum.journey.JourneyRoute
import dev.pleos.starter.teum.journey.EntryChoice
import dev.pleos.starter.teum.journey.breathingCadence
import dev.pleos.starter.teum.preferences.TeumPreferences
import dev.pleos.starter.teum.preferences.JourneyNotification
import dev.pleos.starter.teum.records.JourneyKind
import dev.pleos.starter.teum.location.ArrivalSuggestion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Converts observed native facts to the original screen vocabulary. No effects or demo data. */
class TeumWebProjection {
    private val gson = Gson()

    fun project(
        data: JsonObject, route: TeumWebRoute, revision: Long, now: Long, zone: ZoneId,
        preferences: TeumPreferences, journey: JourneySnapshot?, remainingMs: Long?,
        selectedDate: LocalDate, skipped: Set<String>, meetingAt: Long?, sleepMinutes: Int,
        theme: String, hardware: JsonObject, permissions: JsonObject, appVersion: String,
        completion: JsonObject, morningGreeting: String?, notice: String?,
        arrival: ArrivalSuggestion? = null
    ): JsonObject {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val today = local.toLocalDate()
        val slot = when (journey?.plan?.kind) {
            JourneyKind.MORNING, JourneyKind.QUICK_BREATHING -> "am"
            JourneyKind.NAP -> "noon"
            JourneyKind.PREPARATION -> "meet"
            JourneyKind.EVENING -> "pm"
            null -> when (local.hour) { in 0..10 -> "am"; in 11..14 -> "noon"; in 15..16 -> "meet"; else -> "pm" }
        }
        val day = data.getAsJsonObject("week").getAsJsonArray("days")
            .map { it.asJsonObject }.firstOrNull { it["date"].asString == today.toString() }
        val completed = day?.getAsJsonArray("completedJourneys")?.map { it.asString }.orEmpty()
        val records = data.getAsJsonArray("records").map { it.asJsonObject }
        val todayIds = day?.getAsJsonArray("recordIds")?.map { it.asString }.orEmpty()
        val morning = records.firstOrNull {
            it["id"].asString in todayIds && it.getAsJsonObject("journeyFacts")?.get("kind")?.asString == "MORNING"
        }?.getAsJsonObject("journeyFacts")
        val mood = journey?.atmosphere?.name?.lowercase(Locale.ROOT)
            ?: preferences.atmosphere?.name?.lowercase(Locale.ROOT) ?: "calm"
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)
        fun clock(minute: Int?): String? = minute?.let { "%02d:%02d".format(Locale.ROOT, it / 60, it % 60) }
        val meeting = meetingAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val elapsedToMeeting = meetingAt?.let { (it - now) / 60_000 }
        return data.deepCopy().apply {
            addProperty("screen", route.id)
            addProperty("renderRevision", revision)
            addProperty("theme", theme)
            addProperty("slot", arrival?.slot?.let {
                when (it) {
                    JourneyNotification.MORNING -> "am"
                    JourneyNotification.NAP -> "noon"
                    JourneyNotification.PREPARATION -> "meet"
                    JourneyNotification.EVENING -> "pm"
                }
            } ?: slot)
            addProperty("mood", mood)
            addProperty("pick", mood)
            addProperty("dayColor", journey?.atmosphere?.name?.lowercase(Locale.ROOT))
            addProperty("tune", journey?.musicChoice?.ordinal ?: 0)
            addProperty("round", when (journey?.phase) {
                JourneyPhase.SPEECH_SECOND, JourneyPhase.SPEECH_COMPARISON -> 2
                JourneyPhase.MUSIC_SELECTION -> if (journey.facts.secondSpeech != null) 2 else 1
                else -> 1
            })
            addProperty("short", journey?.plan?.route in setOf(JourneyRoute.SHORT_MORNING, JourneyRoute.SHORT_EVENING))
            addProperty("sleep", journey?.plan?.napMinutes ?: sleepMinutes)
            addProperty("meetAt", meeting?.format(time))
            addProperty("intention", if (journey != null) journey.facts.intention else morning?.get("intention")?.asString)
            addProperty("story", journey?.facts?.reflection?.story)
            addProperty("transcript", journey?.facts?.intention ?: journey?.facts?.reflection?.story)
            addProperty("selectedDate", selectedDate.toString())
            addProperty("appVersion", appVersion)
            addProperty("notice", notice)
            addProperty("journeyPhase", journey?.phase?.name)
            addProperty("journeyActive", journey?.active == true)
            add("entry", JsonObject().apply {
                addProperty("reviewing", journey?.active == true && journey.phase == JourneyPhase.ENTRY_REVIEW)
                addProperty("plan", journey?.plan?.route?.name)
                addProperty("canChangePlan", journey == null)
                addProperty("canReconfigure", journey?.active == true && journey.phase == JourneyPhase.ENTRY_REVIEW)
                addProperty("selectedChoice", journey?.let { owned ->
                    EntryChoice.entries.first { it.plan == owned.plan }.name
                })
                addProperty("minutes", journey?.plan?.nominalMinutes)
                addProperty("napMinutes", journey?.plan?.takeIf { it.route == JourneyRoute.NAP }?.napMinutes)
                addProperty("elapsedSeconds", journey?.elapsedMs?.div(1000))
            })
            add("day", JsonObject().apply { addProperty("date", selectedDate.toString()) })
            add("done", JsonObject().apply {
                addProperty("am", "MORNING" in completed)
                addProperty("noon", "NAP" in completed)
                addProperty("meet", "PREPARATION" in completed)
                addProperty("pm", "EVENING" in completed)
            })
            add("skipped", JsonObject().apply {
                listOf("am", "noon", "meet", "pm").forEach { addProperty(it, it in skipped) }
            })
            add("journeyToken", JsonNull.INSTANCE)
            add("breathing", JsonNull.INSTANCE)
            journey?.let {
                add("journeyToken", JsonObject().apply {
                    addProperty("sessionId", it.sessionId); addProperty("revision", it.revision)
                })
            }
            add("context", JsonObject().apply {
                addProperty("localDate", today.toString())
                addProperty("greeting", when (local.hour) {
                    in 0..10 -> "좋은 아침이에요"; in 11..14 -> "점심 잘 보냈어요?"; in 15..16 -> "안녕하세요"; else -> "오늘도 고생 많았어요"
                })
                addProperty("summary", "주차한 차 안에서 잠깐 쉬어 가요")
                addProperty("scheduleSummary", arrival?.let { "저장한 일정까지 쉴 수 있는 시간 ${it.availableMinutes}분" }
                    ?: "저장한 일정과 차량 상태를 확인하고 시작해 주세요")
                addProperty("availableMinutes", arrival?.availableMinutes ?:
                    journey?.takeUnless { it.phase == JourneyPhase.ENTRY_REVIEW }?.plan?.nominalMinutes?.toLong())
                addProperty("napSummary", remainingMs?.let { "남은 휴식 시간 ${it / 1000}초" }
                    ?: arrival?.let { "쉴 수 있는 시간 ${it.availableMinutes}분 · 선택한 낮잠 ${sleepMinutes}분" }
                    ?: "선택한 낮잠 시간 ${sleepMinutes}분")
                addProperty("napRemainingSeconds", remainingMs?.div(1000))
                addProperty("napElapsedSeconds", journey?.napSleptMs?.div(1000))
                addProperty("countdownSeconds", if (journey?.phase == JourneyPhase.QUICK_COUNTDOWN)
                    remainingMs?.let { (it + 999) / 1000 } else null)
                addProperty("meetingSummary", if (elapsedToMeeting != null && elapsedToMeeting > 0)
                    "등록한 일정까지 ${elapsedToMeeting}분 남았어요" else "다가오는 일정을 확인하지 못했어요")
                addProperty("meetingMinute", meeting?.let { it.hour * 60 + it.minute })
                addProperty("meetingOccurred", false)
                addProperty("dateLabel", local.format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)))
                addProperty("intentionDateLabel", journey?.facts?.startedAtEpochMs?.let {
                    Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("M월 d일 EEEE HH:mm", Locale.KOREAN))
                })
                addProperty("morningGreeting", morningGreeting)
                add("slotTimes", JsonObject().apply {
                    addProperty("am", clock(preferences.schedule.workStartMinute))
                    addProperty("noon", clock(preferences.schedule.lunchReturnMinute))
                    addProperty("pm", "집에서 쉬어 가기")
                })
            })
            add("speech", JsonObject().apply {
                add("first", gson.toJsonTree(journey?.facts?.firstSpeech))
                add("second", gson.toJsonTree(journey?.facts?.secondSpeech))
                addProperty("recording", journey?.speechCapture == dev.pleos.starter.teum.journey.SpeechCapture.RECORDING)
                addProperty("processing", journey?.speechCapture == dev.pleos.starter.teum.journey.SpeechCapture.AWAITING_RESULT)
            })
            journey?.takeIf { it.phase in setOf(JourneyPhase.MORNING_BREATHING, JourneyPhase.QUICK_BREATHING) }?.let {
                val cadence = it.breathingCadence()
                val duration = cadence.inhaleMs + cadence.extraInhaleMs + cadence.exhaleMs
                val progress = it.phaseElapsedMs % duration
                add("breathing", JsonObject().apply {
                    addProperty("phase", when {
                        progress < cadence.inhaleMs -> "INHALE"
                        progress < cadence.inhaleMs + cadence.extraInhaleMs -> "TOP_UP"
                        else -> "EXHALE"
                    })
                    addProperty("cycleElapsedMs", progress)
                    addProperty("cycleDurationMs", duration)
                })
            }
            add("recordLabels", JsonObject().apply {
                records.forEach { record ->
                    record["knownTimeEpochMs"]?.takeUnless { it.isJsonNull }?.asLong?.let {
                        add(record["id"].asString, JsonObject().apply {
                            addProperty("time", Instant.ofEpochMilli(it).atZone(zone).format(time))
                        })
                    }
                }
            })
            add("hardware", hardware)
            add("permissions", permissions)
            add("completion", completion)
        }
    }
}
