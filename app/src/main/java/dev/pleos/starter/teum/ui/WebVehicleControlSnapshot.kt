package dev.pleos.starter.teum.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.pleos.starter.teum.vehicle.DiscoveredSetting
import dev.pleos.starter.teum.vehicle.SettingCapability
import dev.pleos.starter.teum.vehicle.VehicleCommand

internal data class WebVehicleControlSnapshot(
    val status: String = "idle",
    val area: String = "",
    val areas: List<DiscoveredSetting> = emptyList(),
    val capability: SettingCapability? = null,
    val currentValue: Double? = null,
    val command: VehicleCommand? = null,
    val error: String? = null,
    val safetyReason: String? = null
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("status", status)
        addProperty("area", area)
        add("areas", JsonArray().apply {
            areas.forEach { found ->
                add(JsonObject().apply {
                    addProperty("area", found.setting.area)
                    addProperty("observation", found.observation)
                    add("values", JsonArray().apply {
                        found.areaValues.forEach { value ->
                            add(JsonObject().apply {
                                addProperty("name", value.name)
                                addProperty("id", value.id)
                            })
                        }
                    })
                })
            }
        })
        addProperty("currentValue", currentValue)
        addProperty("error", error)
        addProperty("safetyReason", safetyReason)
        add("capability", capability?.let {
            JsonObject().apply {
                addProperty("canRead", it.canRead)
                addProperty("canWrite", it.canWrite)
                addProperty("min", it.min)
                addProperty("max", it.max)
                addProperty("step", it.step)
                addProperty("reason", it.reason)
                add("allowedValues", it.allowedValues?.let { values ->
                    JsonArray().apply { values.forEach { value -> add(value) } }
                })
                add("valueLabels", JsonObject().apply {
                    it.valueLabels.forEach { (value, label) -> addProperty(value.toString(), label) }
                })
            }
        })
        add("command", command?.let {
            JsonObject().apply {
                addProperty("id", it.id)
                addProperty("feature", it.setting.feature.name)
                addProperty("target", it.target)
                addProperty("initial", it.initial)
                addProperty("previewValue", it.previewValue)
                addProperty("stage", it.stage.name)
                addProperty("reason", it.reason)
                addProperty("requestAcknowledged", it.requestAcknowledged)
                addProperty("readback", it.readback)
            }
        })
    }

    fun capabilityFact(feature: WebVehicleFeature): JsonObject = JsonObject().apply {
        val readable = capability?.canRead == true && currentValue != null &&
            capability.accepts(currentValue)
        val readPermission = when (feature) {
            WebVehicleFeature.AMBIENT_BRIGHTNESS, WebVehicleFeature.AMBIENT_COLOR,
            WebVehicleFeature.AMBIENT_SWITCH -> "pleos.car.permission.CAR_LIGHTS"
            WebVehicleFeature.WINDOW_POSITION, WebVehicleFeature.SMART_GLASS_STATE,
            WebVehicleFeature.SMART_GLASS_LEVEL -> "pleos.car.permission.CAR_WINDOWS"
            WebVehicleFeature.SEAT_VENTILATION -> "pleos.car.permission.CAR_SEATS"
            else -> null
        }
        val writePermission = when (feature) {
            WebVehicleFeature.AMBIENT_BRIGHTNESS, WebVehicleFeature.AMBIENT_COLOR,
            WebVehicleFeature.AMBIENT_SWITCH -> "pleos.car.permission.CONTROL_CAR_LIGHTS"
            WebVehicleFeature.WINDOW_POSITION, WebVehicleFeature.SMART_GLASS_STATE,
            WebVehicleFeature.SMART_GLASS_LEVEL -> "pleos.car.permission.CONTROL_CAR_WINDOWS"
            WebVehicleFeature.SEAT_VENTILATION -> "pleos.car.permission.CONTROL_CAR_SEATS"
            else -> null
        }
        addProperty("status", when {
            safetyReason != null -> "unknown"
            readable -> "confirmed"
            status == "checking" -> "pending"
            status == "failed" -> "failed"
            else -> "unknown"
        })
        addProperty("label", when {
            safetyReason != null -> "주차·주행 제한 확인 필요"
            readable && capability?.canWrite == true -> "읽기·제어 지원 확인됨"
            readable -> "조회 지원 · 제어 권한 없음"
            status == "checking" -> "지원 확인 중"
            status == "failed" -> "지원 확인 실패"
            else -> "확인 전"
        })
        addProperty("writeReady", readable && capability?.canWrite == true && safetyReason == null)
        addProperty("readPermission", readPermission)
        addProperty("writePermission", writePermission)
        addProperty("area", area)
        addProperty("error", error)
    }
}
