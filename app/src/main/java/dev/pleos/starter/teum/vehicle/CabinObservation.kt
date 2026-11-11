package dev.pleos.starter.teum.vehicle

enum class CabinReadStatus { STOPPED, PENDING, DENIED, UNSUPPORTED, UNREADABLE, READABLE, FAILED }
enum class CabinPositionStatus { PENDING, OBSERVED, MISSING, FAILED }

internal class CabinCallbackEpoch {
    private var epoch = 0L

    fun invalidate(): Long = ++epoch
    fun current(): Long = epoch
    fun accepts(token: Long): Boolean = token == epoch
}

data class CabinAreaObservation(
    val identity: List<String>,
    val rawMin: Int,
    val rawMax: Int,
    val rawPosition: Int? = null,
    val status: CabinPositionStatus = CabinPositionStatus.PENDING,
    val error: String? = null
)

/** SDK integer coordinates have no proven conversion to physical angles or distances. */
data class CabinObservation(
    val status: CabinReadStatus = CabinReadStatus.STOPPED,
    val access: String? = null,
    val areas: Map<String, CabinAreaObservation> = emptyMap(),
    val error: String? = null,
    val permissionGranted: Boolean? = null
)
