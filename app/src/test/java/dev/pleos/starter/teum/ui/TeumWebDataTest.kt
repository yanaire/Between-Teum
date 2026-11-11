package dev.pleos.starter.teum.ui

import com.google.gson.JsonObject
import dev.pleos.starter.teum.content.SituationIds
import dev.pleos.starter.teum.preferences.InMemoryPreferenceStore
import dev.pleos.starter.teum.preferences.PreferencesRepository
import dev.pleos.starter.teum.preferences.RestSound
import dev.pleos.starter.teum.preferences.TeumPreferences
import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.records.InMemoryRecordStore
import dev.pleos.starter.teum.records.JourneyFacts
import dev.pleos.starter.teum.records.JourneyKind
import dev.pleos.starter.teum.records.RecordsRepository
import dev.pleos.starter.teum.records.newActivityRecord
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TeumWebDataTest {
    @Test
    fun projectionUsesTodayOnlyWhenSelectedDateIsOutsideDisplayedWeek() {
        val zone = ZoneId.of("Asia/Seoul")
        val owner = TeumWebData(
            PreferencesRepository(InMemoryPreferenceStore()), RecordsRepository(InMemoryRecordStore())
        )
        val cases = listOf(
            Triple("2026-11-08T15:30:00Z", "2026-11-08", "2026-11-09"),
            Triple("2026-11-11T03:00:00Z", "2026-11-09", "2026-11-09"),
            Triple("2026-11-11T03:00:00Z", "2026-11-15", "2026-11-15"),
            Triple("2026-11-11T03:00:00Z", "2026-11-16", "2026-11-11")
        )
        for ((instant, selection, expected) in cases) {
            val now = Instant.parse(instant).toEpochMilli()
            val projected = TeumWebProjection().project(
                owner.snapshot(now, zone), TeumWebRoute.R_02, 1, now, zone,
                TeumPreferences(), null, null, LocalDate.parse(selection), emptySet(), null,
                20, "light", JsonObject(), JsonObject(), "test", JsonObject(), null, null
            )
            assertEquals("selectedDate for $instant and $selection", expected, projected["selectedDate"].asString)
            assertEquals("day.date for $instant and $selection", expected, projected.getAsJsonObject("day")["date"].asString)
        }
    }

    @Test
    fun browserSettingsPersistThroughTheirNativeOwnersWithoutChangingOtherFlags() {
        val store = InMemoryPreferenceStore()
        val owner = TeumWebData(PreferencesRepository(store), RecordsRepository(InMemoryRecordStore()))
        owner.applyPreference(TeumWebAction.SavePlaceLabel(true, "<집>"))
        owner.applyPreference(TeumWebAction.SaveSchedule(530, null, 7))
        owner.applyPreference(TeumWebAction.WorkFromHomeDay(DayOfWeek.FRIDAY, true))
        owner.applyPreference(TeumWebAction.SelectSound(RestSound.WIND))
        owner.applyPreference(TeumWebAction.SetPreferenceFlag(WebPreferenceFlag.NAP, false))
        val actual = PreferencesRepository(store).load()
        assertEquals("<집>", actual.home.label)
        assertEquals("", actual.company.label)
        assertEquals(530, actual.schedule.workStartMinute)
        assertNull(actual.schedule.lunchReturnMinute)
        assertEquals(setOf(DayOfWeek.FRIDAY), actual.schedule.workFromHomeDays)
        assertEquals(RestSound.WIND, actual.restSound)
        assertFalse(actual.notifications.nap)
        assertTrue(actual.notifications.morning)
        assertTrue(actual.voiceGuide)
    }

    @Test
    fun projectionKeepsQuickCompletionSeparateFromMorningStreakAndUsesActualFinish() {
        val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        val records = RecordsRepository(InMemoryRecordStore()) { now }
        records.save(newActivityRecord(
            SituationIds.COMMUTE_MORNING, "숨 고르기", 1, listOf(0), 180,
            ActivityOutcome.COMPLETED, nowEpochMs = now,
            journeyFacts = JourneyFacts(JourneyKind.QUICK_BREATHING,
                finishedAtEpochMs = now - 3_600_000)
        ))
        val state = TeumWebData(PreferencesRepository(InMemoryPreferenceStore()), records)
            .snapshot(now, ZoneId.of("UTC"))
        val week = state.getAsJsonObject("week")
        assertEquals(180L, week["elapsedSeconds"].asLong)
        assertEquals(1, week["streakDays"].asInt)
        assertEquals(0, week["morningStreakDays"].asInt)
        val row = state.getAsJsonArray("records").single().asJsonObject
        assertEquals("ACTIVITY_FINISH", row["timeBasis"].asString)
        assertEquals(now - 3_600_000, row["knownTimeEpochMs"].asLong)
    }
}
