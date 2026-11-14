package dev.pleos.starter.teum.vehicle

import kotlin.math.abs
import kotlin.math.round

enum class VehicleFeature {
    HVAC_TEMPERATURE, AMBIENT_BRIGHTNESS, SEAT_POSITION, WINDOW_POSITION,
    SEAT_VENTILATION, SMART_GLASS_STATE, SMART_GLASS_LEVEL, AMBIENT_COLOR, AMBIENT_SWITCH
}
data class VehicleSetting(val feature: VehicleFeature, val area: String)
enum class ChangeOrigin { APP, MANUAL, UNKNOWN }
enum class CommandStage { PREVIEW, QUEUED, REQUESTED, READBACK, FAILED, UNKNOWN, MANUAL }

data class SettingCapability(
    val canRead: Boolean,
    val canWrite: Boolean,
    val min: Double,
    val max: Double,
    val step: Double,
    val reason: String,
    val allowedValues: List<Double>? = null,
    val valueLabels: Map<Double, String> = emptyMap()
) {
    fun accepts(value: Double): Boolean = value.isFinite() && min.isFinite() && max.isFinite() &&
        step.isFinite() && step > 0 && min <= max && value in min..max &&
        abs((value - min) / step - round((value - min) / step)) < 0.001 &&
        (allowedValues == null || value in allowedValues)
}

data class SettingInspection(val capability: SettingCapability, val value: Double)
data class DiscoveredAreaValue(val name: String, val id: Int)
data class DiscoveredSetting(
    val setting: VehicleSetting,
    val capability: SettingCapability,
    val areaValues: List<DiscoveredAreaValue>,
    val observation: String = "NATIVE_LISTENER"
)

/** Optional discovery surface; existing ports and fakes need not implement it. */
interface VehicleSettingsDiscovery {
    fun discover(feature: VehicleFeature, result: (Result<List<DiscoveredSetting>>) -> Unit)
}
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
    val readback: Double? = null,
    val previewValue: Double? = null
)
