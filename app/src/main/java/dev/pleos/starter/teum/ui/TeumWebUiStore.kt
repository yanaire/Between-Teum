package dev.pleos.starter.teum.ui

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import dev.pleos.starter.teum.location.MeetingFact

/** Shell metadata only; stories, measurements and records belong to RecordsRepository. */
class TeumWebUiStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "teum_web_ui_private_v1", Context.MODE_PRIVATE
    )

    fun onboardingCompleted(): Boolean = prefs.getBoolean("onboarding_completed", false)

    fun completeOnboarding() {
        check(prefs.edit().putBoolean("onboarding_completed", true).commit())
    }

    fun meetingAtEpochMs(): Long? =
        prefs.getLong("meeting_at_epoch_ms", 0).takeIf { it > 0 }

    fun meetingFact(): MeetingFact? {
        val instant = meetingAtEpochMs() ?: return null
        val zone = prefs.getString("meeting_zone", null) ?: return null
        return MeetingFact("selected-$instant", Instant.ofEpochMilli(instant), ZoneId.of(zone))
    }

    /** A time explicitly chosen today, moved to tomorrow only when already past. */
    fun saveMeeting(minuteOfDay: Int, nowEpochMs: Long, zone: ZoneId): Long {
        require(minuteOfDay in 0..1439)
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        var meeting = now.toLocalDate().atStartOfDay(zone)
            .withHour(minuteOfDay / 60).withMinute(minuteOfDay % 60)
        if (!meeting.isAfter(now)) meeting = meeting.plusDays(1)
        val epoch = meeting.toInstant().toEpochMilli()
        check(prefs.edit().putLong("meeting_at_epoch_ms", epoch).putString("meeting_zone", zone.id)
            .remove("meeting_feedback").commit())
        return epoch
    }

    fun meetingFeedback(): String? = prefs.getString("meeting_feedback", null)

    fun skippedOn(date: LocalDate): Set<String> =
        if (prefs.getString("skip_date", null) == date.toString())
            prefs.getStringSet("skip_slots", emptySet()).orEmpty().toSet() else emptySet()

    fun skip(slot: String, date: LocalDate) {
        require(slot in setOf("am", "noon", "meet", "pm"))
        check(prefs.edit().putString("skip_date", date.toString())
            .putStringSet("skip_slots", skippedOn(date) + slot).commit())
    }

    fun saveMeetingFeedback(feedback: String, nowEpochMs: Long): Boolean {
        require(feedback in setOf("well", "okay", "hard"))
        val meeting = meetingAtEpochMs() ?: return false
        if (nowEpochMs <= meeting) return false
        check(prefs.edit().putString("meeting_feedback", feedback).commit())
        return true
    }
}
