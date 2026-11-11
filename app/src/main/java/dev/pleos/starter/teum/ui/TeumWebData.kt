package dev.pleos.starter.teum.ui

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.pleos.starter.teum.preferences.BackrestPreferences
import dev.pleos.starter.teum.preferences.PreferencesRepository
import dev.pleos.starter.teum.preferences.SavedPlace
import dev.pleos.starter.teum.records.JourneyKind
import dev.pleos.starter.teum.records.RecordsRepository
import dev.pleos.starter.teum.records.knownTimeEpochMs
import dev.pleos.starter.teum.records.timeBasis
import java.time.Instant
import java.time.ZoneId

/** The application storage executor serializes this owner and its repositories. */
class TeumWebData(
    private val preferences: PreferencesRepository,
    private val records: RecordsRepository
) {
    private val gson = Gson()

    fun applyPreference(action: TeumWebAction): Boolean {
        when (action) {
            is TeumWebAction.SavePlaceLabel -> preferences.update {
                // Editing a label never quietly changes its previously captured coordinates.
                if (action.home) it.copy(home = it.home.copy(label = action.label))
                else it.copy(company = it.company.copy(label = action.label))
            }
            is TeumWebAction.SaveSchedule -> preferences.update {
                it.copy(schedule = it.schedule.copy(
                    workStartMinute = action.workStartMinute,
                    lunchReturnMinute = action.lunchReturnMinute,
                    walkingReserveMinutes = action.walkingReserveMinutes
                ))
            }
            is TeumWebAction.WorkFromHomeDay -> preferences.update {
                val days = it.schedule.workFromHomeDays
                it.copy(schedule = it.schedule.copy(workFromHomeDays =
                    if (action.enabled) days + action.day else days - action.day))
            }
            is TeumWebAction.SaveBackrest -> preferences.setBackrest(
                BackrestPreferences(action.restDegrees, action.napDegrees)
            )
            is TeumWebAction.SelectSound -> preferences.setRestSound(action.sound)
            is TeumWebAction.SelectAtmosphere -> preferences.setAtmosphere(action.atmosphere)
            is TeumWebAction.SetPreferenceFlag -> preferences.update {
                val n = it.notifications
                when (action.flag) {
                    WebPreferenceFlag.VOICE_GUIDE -> it.copy(voiceGuide = action.enabled)
                    WebPreferenceFlag.MORNING -> it.copy(notifications = n.copy(morning = action.enabled))
                    WebPreferenceFlag.NAP -> it.copy(notifications = n.copy(nap = action.enabled))
                    WebPreferenceFlag.PREPARATION -> it.copy(notifications = n.copy(preparation = action.enabled))
                    WebPreferenceFlag.EVENING -> it.copy(notifications = n.copy(evening = action.enabled))
                    WebPreferenceFlag.ASK_BEFORE_HOME_ARRIVAL -> it.copy(
                        notifications = n.copy(askBeforeHomeArrival = action.enabled)
                    )
                    WebPreferenceFlag.DELETE_EVENING_STORY_AFTER_NEXT_MORNING -> it.copy(
                        privacy = it.privacy.copy(deleteEveningStoryAfterNextMorning = action.enabled)
                    )
                }
            }
            else -> return false
        }
        return true
    }

    fun saveCapturedPlace(home: Boolean, place: SavedPlace) {
        if (home) preferences.setHome(place) else preferences.setCompany(place)
    }

    fun snapshot(nowEpochMs: Long, zone: ZoneId): JsonObject {
        val prefs = preferences.load()
        records.maintainHistory(zone, prefs.privacy.deleteEveningStoryAfterNextMorning)
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val week = records.weeklyStats(today, zone)
        val history = records.listAll()
        val morningDates = history.filter {
            it.isFullyCompleted() && it.journeyFacts?.kind == JourneyKind.MORNING &&
                it.journeyFacts.finishedAtEpochMs != null
        }.map { Instant.ofEpochMilli(requireNotNull(it.journeyFacts?.finishedAtEpochMs)).atZone(zone).toLocalDate() }.toSet()
        var cursor = if (today in morningDates) today else today.minusDays(1)
        var morningStreak = 0
        while (cursor in morningDates) {
            morningStreak++
            cursor = cursor.minusDays(1)
        }
        return JsonObject().apply {
            add("preferences", gson.toJsonTree(prefs))
            add("week", JsonObject().apply {
                addProperty("elapsedSeconds", week.elapsedSeconds)
                addProperty("activityCount", week.activityCount)
                addProperty("completedCount", week.completedCount)
                addProperty("streakDays", week.streakDays)
                addProperty("morningStreakDays", morningStreak)
                add("undatedRecordIds", gson.toJsonTree(week.undatedRecordIds))
                add("days", JsonArray().apply {
                    week.days.forEach { day -> add(JsonObject().apply {
                        addProperty("date", day.date.toString())
                        addProperty("elapsedSeconds", day.elapsedSeconds)
                        addProperty("savedTimeElapsedSeconds", day.savedTimeElapsedSeconds)
                        addProperty("activityCount", day.activityCount)
                        addProperty("completedCount", day.completedCount)
                        add("completedJourneys", gson.toJsonTree(day.completedJourneys))
                        add("recordIds", gson.toJsonTree(day.records.map { it.record.id }))
                    }) }
                })
            })
            add("records", JsonArray().apply {
                history.forEach { record -> add(gson.toJsonTree(record).asJsonObject.apply {
                    addProperty("timeBasis", record.timeBasis().name)
                    addProperty("knownTimeEpochMs", record.knownTimeEpochMs())
                }) }
            })
        }
    }
}
