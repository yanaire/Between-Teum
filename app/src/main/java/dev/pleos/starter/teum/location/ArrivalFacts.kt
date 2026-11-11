package dev.pleos.starter.teum.location

import dev.pleos.starter.teum.preferences.JourneyNotification
import dev.pleos.starter.teum.vehicle.VehicleSafety
import java.time.Instant
import java.time.ZoneId

/** Actual pedal input, not parking brake. Timestamp uses SystemClock.elapsedRealtime(). */
data class BrakeFact(
    val pressed: Boolean?,
    val observedAtElapsedMillis: Long,
    val safetyGeneration: Long
)

/** Explicit user schedule fact; no prototype or inferred calendar event. */
data class MeetingFact(val id: String, val startsAt: Instant, val zone: ZoneId)

data class ArrivalFacts(
    val foreground: Boolean,
    val location: LocationState,
    val safety: VehicleSafety,
    val brake: BrakeFact,
    /** Same monotonic clock domain as AaosSafetySource's existing P/UX observations. */
    val elapsedRealtimeMillis: Long,
    val userZone: ZoneId,
    val meeting: MeetingFact? = null,
    val excludedSlots: Set<JourneyNotification> = emptySet()
)

enum class SuggestionOutcome { PRESENTED, SKIPPED, DECLINED, COMPLETED }

data class ArrivalSuggestion(
    val token: String,
    val slot: JourneyNotification,
    val localDate: String,
    val visit: Long,
    val distanceMeters: Double,
    val availableMinutes: Long,
    val rawConfidence: Float,
    /** Explicit uncertainty even when the app's distance rule passes. */
    val precisionVerified: Boolean = false
)

sealed interface ArrivalDecision {
    data class Offer(val suggestion: ArrivalSuggestion) : ArrivalDecision
    data class Suppressed(val reason: String) : ArrivalDecision
}

data class VisitEntry(
    val date: String,
    val target: String,
    val visit: Long,
    val inside: Boolean,
    val token: String = "",
    val outcome: SuggestionOutcome? = null
)

data class ArrivalLedger(
    val entries: Map<JourneyNotification, VisitEntry> = emptyMap(),
    val cooldownUntilMillis: Long = 0,
    val lastEvaluatedMillis: Long = 0
)

interface ArrivalLedgerStore {
    fun load(): ArrivalLedger
    /** Must throw on failure; no offer is returned before its dedupe state is durable. */
    fun persist(ledger: ArrivalLedger)
}
