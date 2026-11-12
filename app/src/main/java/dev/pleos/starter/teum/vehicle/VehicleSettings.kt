package dev.pleos.starter.teum.vehicle

import kotlin.math.abs
import kotlin.math.round

enum class VehicleFeature { HVAC_TEMPERATURE, AMBIENT_BRIGHTNESS, SEAT_POSITION, WINDOW_POSITION }
data class VehicleSetting(val feature: VehicleFeature, val area: String)
enum class ChangeOrigin { APP, MANUAL, UNKNOWN }
enum class CommandStage { PREVIEW, QUEUED, REQUESTED, READBACK, FAILED, UNKNOWN, MANUAL }

data class SettingCapability(
    val canRead: Boolean,
    val canWrite: Boolean,
    val min: Double,
    val max: Double,
    val step: Double,
    val reason: String
) {
    fun accepts(value: Double): Boolean = value.isFinite() && min.isFinite() && max.isFinite() &&
        step.isFinite() && step > 0 && min <= max && value in min..max &&
        abs((value - min) / step - round((value - min) / step)) < 0.001
}

data class SettingInspection(val capability: SettingCapability, val value: Double)
class VehicleCallbackTimeout : IllegalStateException("Vehicle callback timeout; outcome unknown")

/** Implementations serialize callbacks and invoke permit immediately before the physical setter. */
interface VehicleSettingsPort : AutoCloseable {
    fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit)
    fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable
    fun write(setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit)
}

data class VehicleCommand(
    val id: Long,
    val setting: VehicleSetting,
    val target: Double,
    val initial: Double?,
    val stage: CommandStage,
    val reason: String,
    val isRestore: Boolean = false,
    val requiresOriginConfirmation: Boolean = false,
    val requestAcknowledged: Boolean = false,
    val readback: Double? = null
)
