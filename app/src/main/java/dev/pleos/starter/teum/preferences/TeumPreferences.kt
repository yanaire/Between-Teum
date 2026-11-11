package dev.pleos.starter.teum.preferences

import java.time.DayOfWeek

data class PlaceCoordinates(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class SavedPlace(val label: String = "", val coordinates: PlaceCoordinates? = null)

data class DailySchedule(
    val workStartMinute: Int? = null,
    val lunchReturnMinute: Int? = null,
    val walkingReserveMinutes: Int? = null,
    val workFromHomeDays: Set<DayOfWeek> = emptySet()
) {
    init {
        require(workStartMinute == null || workStartMinute in 0..1439)
        require(lunchReturnMinute == null || lunchReturnMinute in 0..1439)
        require(walkingReserveMinutes == null || walkingReserveMinutes in 1..10)
    }
}

/** Designer preference degrees, never Vehicle SDK actuator units. */
data class BackrestPreferences(val restDegrees: Int = 118, val napDegrees: Int = 132) {
    init {
        require(restDegrees in 106..146)
        require(napDegrees in 106..160)
    }
}

enum class RestSound { RAIN, WAVES, FOREST, CAMPFIRE, SINGING_BOWL, WIND, WHITE_NOISE, NONE }
enum class Atmosphere { CALM, FIRM, WARM, FOCUS, FLEX }
enum class JourneyNotification { MORNING, NAP, PREPARATION, EVENING }

data class NotificationPreferences(
    val morning: Boolean = true,
    val nap: Boolean = true,
    val preparation: Boolean = true,
    val evening: Boolean = true,
    val askBeforeHomeArrival: Boolean = true
) {
    fun enabled(flow: JourneyNotification): Boolean = when (flow) {
        JourneyNotification.MORNING -> morning
        JourneyNotification.NAP -> nap
        JourneyNotification.PREPARATION -> preparation
        JourneyNotification.EVENING -> evening
    }
}

data class PrivacyPreferences(val deleteEveningStoryAfterNextMorning: Boolean = true)

data class TeumPreferences(
    val company: SavedPlace = SavedPlace(),
    val home: SavedPlace = SavedPlace(),
    val schedule: DailySchedule = DailySchedule(),
    val backrest: BackrestPreferences = BackrestPreferences(),
    val restSound: RestSound = RestSound.NONE,
    val voiceGuide: Boolean = true,
    val notifications: NotificationPreferences = NotificationPreferences(),
    val atmosphere: Atmosphere? = null,
    val privacy: PrivacyPreferences = PrivacyPreferences()
)
