package dev.pleos.starter.teum.location

import dev.pleos.starter.teum.preferences.JourneyNotification
import dev.pleos.starter.teum.preferences.PlaceCoordinates
import dev.pleos.starter.teum.preferences.PreferencesRepository
import dev.pleos.starter.teum.vehicle.VehicleSafety
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Serialized foreground evaluator. Feed every safety/location/lifecycle change and tick.
 * Distance is spherical coordinate separation, not OEM accuracy or route ETA.
 * App policy: enter <=150m, exit >=250m; once per local date/slot, 30m dismissal cooldown.
 * A foreground fresh in-radius fix is a visit observation, not proof of a physical arrival.
 */
class ForegroundArrivalPolicy(
    private val preferences: PreferencesRepository,
    private val store: ArrivalLedgerStore,
    private val clock: Clock
) {
    private var pending: ArrivalSuggestion? = null

    @Synchronized
    fun evaluate(facts: ArrivalFacts, allowNewOffer: Boolean = true): ArrivalDecision {
        val now = clock.millis()
        val ledger = store.load()
        fun suppress(reason: String): ArrivalDecision {
            pending = null
            return ArrivalDecision.Suppressed(reason)
        }
        if (!facts.foreground) return suppress("Background")
        if (facts.userZone != clock.zone) return suppress("Timezone mismatch")
        if (now < ledger.lastEvaluatedMillis) return suppress("Clock moved backward")
        facts.safety.blockedReason(facts.elapsedRealtimeMillis)?.let { return suppress(it) }
        if (facts.brake.safetyGeneration != facts.safety.generation ||
            facts.brake.pressed != true ||
            !VehicleSafety.fresh(facts.brake.observedAtElapsedMillis, facts.elapsedRealtimeMillis)) {
            return suppress("Fresh pressed brake unavailable")
        }
        val fix = (facts.location as? LocationState.Ready)?.fix
            ?: return suppress("Location unavailable")
        fix.error(now)?.let { return suppress("Location $it") }
        val settings = preferences.load()
        val local = clock.instant().atZone(facts.userZone)
        val date = local.toLocalDate().toString()
        val minute = local.hour * 60 + local.minute
        val schedule = settings.schedule
        val meeting = facts.meeting
        if (meeting != null && meeting.zone != facts.userZone) return suppress("Meeting timezone mismatch")
        val reserve = schedule.walkingReserveMinutes?.toLong()
        val workRemaining = schedule.workStartMinute?.minus(minute)?.toLong()
        val lunchRemaining = schedule.lunchReturnMinute?.minus(minute)?.toLong()
        val meetingRemaining = meeting?.let {
            java.time.Duration.between(clock.instant(), it.startsAt).toMinutes()
        }
        val slot: JourneyNotification
        val target: PlaceCoordinates
        var available: Long
        when {
            meetingRemaining != null && meetingRemaining in 1..30 -> {
                slot = JourneyNotification.PREPARATION
                target = settings.company.coordinates ?: return suppress("Company unset")
                available = meetingRemaining
            }
            workRemaining != null && workRemaining in 1..60 -> {
                slot = JourneyNotification.MORNING
                target = settings.company.coordinates ?: return suppress("Company unset")
                available = workRemaining - (reserve ?: return suppress("Walking reserve unset"))
            }
            lunchRemaining != null && lunchRemaining in 1..90 -> {
                slot = JourneyNotification.NAP
                target = settings.company.coordinates ?: return suppress("Company unset")
                available = lunchRemaining - (reserve ?: return suppress("Walking reserve unset"))
            }
            minute in 17 * 60 until 24 * 60 -> {
                slot = JourneyNotification.EVENING
                target = settings.home.coordinates ?: return suppress("Home unset")
                available = java.time.Duration.between(local, local.toLocalDate().plusDays(1)
                    .atStartOfDay(facts.userZone)).toMinutes()
                if (!settings.notifications.askBeforeHomeArrival) return suppress("Home prompt disabled")
            }
            else -> return suppress("Outside app schedule window")
        }
        if (slot != JourneyNotification.EVENING &&
            local.dayOfWeek in schedule.workFromHomeDays) return suppress("Work from home day")
        if (!settings.notifications.enabled(slot)) return suppress("Slot disabled")
        if (slot in facts.excludedSlots) return suppress("Slot already completed or skipped")
        if (available < 1) return suppress("No available time")
        // atZone shifts nonexistent DST times; reject these rather than invent a schedule.
        val deadlineMinute = when (slot) {
            JourneyNotification.MORNING -> schedule.workStartMinute
            JourneyNotification.NAP -> schedule.lunchReturnMinute
            else -> null
        }
        if (deadlineMinute != null) {
            val time = LocalTime.of(deadlineMinute / 60, deadlineMinute % 60)
            val offsets = facts.userZone.rules.getValidOffsets(local.toLocalDate().atTime(time))
            if (offsets.size != 1) return suppress("Ambiguous or missing schedule time")
            available = availableMinutes(local, deadlineMinute, reserve ?: 0)
            if (available < 1) return suppress("Schedule elapsed")
        }
        val distance = distanceMeters(fix.coordinates(), target)
        val identity = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${target.latitude},${target.longitude}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val previous = ledger.entries[slot]
        val same = previous?.date == date && previous.target == identity
        val inside = distance <= ENTER_METERS ||
            (same && previous?.inside == true && distance < EXIT_METERS)
        val visit = if (same) {
            requireNotNull(previous).visit + if (inside && !previous.inside) 1 else 0
        } else if (inside) 1 else 0
        // Preserve daily suppression even when saved place coordinates change.
        val shown = previous?.date == date && previous.token.isNotEmpty()
        val entry = VisitEntry(date, identity, visit, inside,
            if (previous?.date == date) previous.token else "",
            if (previous?.date == date) previous.outcome else null)
        var updated = ledger.copy(entries = ledger.entries + (slot to entry), lastEvaluatedMillis = now)
        if (!inside) {
            store.persist(updated)
            return suppress("Outside arrival radius")
        }
        val current = pending
        if (current != null && current.slot == slot && current.localDate == date &&
            current.token == entry.token && same) {
            store.persist(updated)
            val refreshed = current.copy(distanceMeters = distance, availableMinutes = available)
            pending = refreshed
            return ArrivalDecision.Offer(refreshed)
        }
        if (shown || now < ledger.cooldownUntilMillis) {
            store.persist(updated)
            return suppress(if (shown) "Already presented today" else "Cooldown")
        }
        if (!allowNewOffer) return suppress("Presentation not admitted")
        val token = "$date/${facts.userZone.id}/${slot.name}/$visit/$now"
        val suggestion = ArrivalSuggestion(token, slot, date, visit, distance, available, fix.confidence)
        updated = updated.copy(entries = updated.entries +
            (slot to entry.copy(token = token, outcome = SuggestionOutcome.PRESENTED)))
        store.persist(updated)
        pending = suggestion
        return ArrivalDecision.Offer(suggestion)
    }

    /** Call on the actual skip/decline/completion event; never infer session completion. */
    @Synchronized
    fun resolve(suggestion: ArrivalSuggestion, outcome: SuggestionOutcome): Boolean {
        require(outcome != SuggestionOutcome.PRESENTED)
        val ledger = store.load()
        val entry = ledger.entries[suggestion.slot] ?: return false
        if (entry.token != suggestion.token || entry.outcome == SuggestionOutcome.COMPLETED) return false
        store.persist(ledger.copy(
            entries = ledger.entries + (suggestion.slot to entry.copy(outcome = outcome)),
            cooldownUntilMillis = maxOf(ledger.cooldownUntilMillis, clock.millis() + COOLDOWN_MILLIS)
        ))
        pending = null
        return true
    }

    /** Immediately removes the in-memory offer on stop; durable suppression survives resume. */
    @Synchronized
    fun cancel() { pending = null }

    private fun availableMinutes(local: ZonedDateTime, minute: Int, reserve: Long): Long =
        java.time.Duration.between(local, local.toLocalDate()
            .atTime(minute / 60, minute % 60).atZone(local.zone)).toMinutes() - reserve

    companion object {
        const val ENTER_METERS = 150.0
        const val EXIT_METERS = 250.0
        const val COOLDOWN_MILLIS = 30 * 60_000L

        fun distanceMeters(a: PlaceCoordinates, b: PlaceCoordinates): Double {
            val lat = Math.toRadians(b.latitude - a.latitude)
            val lon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(lat / 2) * sin(lat / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
                sin(lon / 2) * sin(lon / 2)
            return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
