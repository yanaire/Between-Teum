package dev.pleos.starter.teum.location

import dev.pleos.starter.teum.preferences.PlaceCoordinates

/** Confidence is raw SDK data. Neither a probability nor a radius is documented. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val confidence: Float,
    val timestampMillis: Long
) {
    fun error(nowMillis: Long): FixError? = when {
        !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 -> FixError.COORDINATES
        !confidence.isFinite() -> FixError.CONFIDENCE
        timestampMillis <= 0 -> FixError.TIMESTAMP
        timestampMillis > nowMillis -> FixError.FUTURE
        nowMillis - timestampMillis > MAX_AGE_MILLIS -> FixError.STALE
        else -> null
    }

    fun coordinates(): PlaceCoordinates = PlaceCoordinates(latitude, longitude)

    companion object {
        /** App freshness budget; not an OEM accuracy or delivery guarantee. */
        const val MAX_AGE_MILLIS = 30_000L
    }
}

enum class FixError { COORDINATES, CONFIDENCE, TIMESTAMP, FUTURE, STALE }
enum class LocationPrivacy { UNKNOWN, DELIVERY_OBSERVED, ACCESS_ERROR }

sealed interface LocationState {
    data object Stopped : LocationState
    data object Unsupported : LocationState
    data object PermissionDenied : LocationState
    data object Waiting : LocationState
    data object Missing : LocationState
    data class Invalid(val reason: FixError) : LocationState
    data class Ready(val fix: LocationFix) : LocationState
    data class Failed(val exception: Exception) : LocationState
}
