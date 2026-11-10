package dev.pleos.starter.teum.vehicle

enum class Parking { PARKED, NOT_PARKED, UNKNOWN }

data class ParkingObservation(
    val state: Parking = Parking.UNKNOWN,
    val observedAtMillis: Long = -1,
    val reason: String = "No authoritative gear observation"
)

data class UxObservation(
    val requiresDistractionOptimization: Boolean? = null,
    val activeRestrictions: Int? = null,
    val observedAtMillis: Long = -1,
    val reason: String = "No restriction observation"
)

data class VehicleSafety(
    val connected: Boolean = false,
    val parking: ParkingObservation = ParkingObservation(),
    val ux: UxObservation = UxObservation(),
    val generation: Long = 0
) {
    fun blockedReason(nowMillis: Long): String? = when {
        !connected -> "Vehicle service disconnected"
        parking.state != Parking.PARKED -> parking.reason
        !fresh(parking.observedAtMillis, nowMillis) -> "Parking observation stale"
        ux.requiresDistractionOptimization == null || ux.activeRestrictions == null -> ux.reason
        !fresh(ux.observedAtMillis, nowMillis) -> "UX restrictions stale"
        ux.requiresDistractionOptimization || ux.activeRestrictions != 0 -> "UX restrictions active"
        else -> null
    }

    companion object {
        const val MAX_AGE_MILLIS = 3_000L
        fun fresh(observed: Long, now: Long): Boolean =
            observed >= 0 && now >= observed && now - observed <= MAX_AGE_MILLIS
    }
}
