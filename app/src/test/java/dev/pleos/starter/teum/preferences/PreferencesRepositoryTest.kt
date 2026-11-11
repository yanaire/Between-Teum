package dev.pleos.starter.teum.preferences

import java.time.DayOfWeek
import org.junit.Assert.*
import org.junit.Test

class PreferencesRepositoryTest {
    @Test
    fun freshSettingsHaveNoInventedPlacesScheduleOrAtmosphere() {
        val actual = PreferencesRepository(InMemoryPreferenceStore()).load()
        assertEquals(SavedPlace(), actual.company)
        assertEquals(SavedPlace(), actual.home)
        assertEquals(DailySchedule(), actual.schedule)
        assertNull(actual.atmosphere)
        assertEquals(RestSound.NONE, actual.restSound)
    }

    @Test
    fun updatesPreserveOtherSettingsAndSurviveRepositoryRecreation() {
        val store = InMemoryPreferenceStore()
        val repo = PreferencesRepository(store)
        repo.setCompany(SavedPlace("Work", PlaceCoordinates(12.5, -23.5)))
        repo.setHome(SavedPlace("Home"))
        repo.setSchedule(DailySchedule(530, 790, 7, setOf(DayOfWeek.FRIDAY)))
        repo.setBackrest(BackrestPreferences(120, 150))
        repo.setRestSound(RestSound.FOREST)
        repo.setVoiceGuide(false)
        repo.setNotifications(NotificationPreferences(false, true, false, true, false))
        repo.setAtmosphere(Atmosphere.FLEX)
        repo.setPrivacy(PrivacyPreferences(false))
        val actual = PreferencesRepository(store).load()
        assertEquals(TeumPreferences(
            SavedPlace("Work", PlaceCoordinates(12.5, -23.5)), SavedPlace("Home"),
            DailySchedule(530, 790, 7, setOf(DayOfWeek.FRIDAY)), BackrestPreferences(120, 150),
            RestSound.FOREST, false, NotificationPreferences(false, true, false, true, false),
            Atmosphere.FLEX, PrivacyPreferences(false)
        ), actual)
    }

    @Test
    fun originalSoundChoicesAndNoSoundRoundTrip() {
        for (sound in RestSound.entries) {
            val expected = TeumPreferences(restSound = sound)
            assertEquals(expected, PreferenceJson.decode(PreferenceJson.encode(expected)))
        }
    }

    @Test
    fun placesCanBeExplicitlyUnsetWithoutClearingOtherSettings() {
        val repo = PreferencesRepository(InMemoryPreferenceStore())
        repo.setHome(SavedPlace("Home", PlaceCoordinates(1.0, 2.0)))
        repo.setVoiceGuide(false)
        repo.setHome(SavedPlace())
        assertEquals(TeumPreferences(voiceGuide = false), repo.load())
    }

    @Test
    fun invalidPreferencePayloadsAreRejectedAtBoundary() {
        val invalid = listOf(
            "{}", "{\"version\":2}", "{\"version\":1,\"version\":1}",
            "{\"version\":\"1\"}", "{\"version\":1,\"companyLatitude\":12}",
            "{\"version\":1,\"companyLatitude\":91,\"companyLongitude\":0}",
            "{\"version\":1,\"workStartMinute\":1440}",
            "{\"version\":1,\"walkingReserveMinutes\":0}",
            "{\"version\":1,\"restDegrees\":105}",
            "{\"version\":1,\"napDegrees\":161}",
            "{\"version\":1,\"voiceGuide\":\"false\"}",
            "{\"version\":1,\"restSound\":\"MUSIC\"}",
            "{\"version\":1,\"workFromHomeDays\":\"FUNDAY\"}",
            "{\"version\":1,\"workStartMinute\":4294967296}",
            "{\"version\":1,\"workStartMinute\":1.5}",
            "{\"version\":1,\"workStartMinute\":1.0}",
            "{\"version\":1} trailing"
        )
        invalid.forEach { assertNull(it, PreferenceJson.decode(it)) }
    }

    @Test
    fun invalidTypedUpdatesFailBeforePersisting() {
        val repo = PreferencesRepository(InMemoryPreferenceStore())
        assertThrows(IllegalArgumentException::class.java) {
            repo.setSchedule(DailySchedule(walkingReserveMinutes = 11))
        }
        assertThrows(IllegalArgumentException::class.java) { PlaceCoordinates(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { BackrestPreferences(napDegrees = 200) }
        assertEquals(TeumPreferences(), repo.load())
    }
}
