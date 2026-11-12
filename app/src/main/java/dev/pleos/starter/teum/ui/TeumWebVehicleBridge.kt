package dev.pleos.starter.teum.ui

import com.google.gson.JsonObject
import dev.pleos.starter.teum.vehicle.CommandStage
import dev.pleos.starter.teum.vehicle.SafeVehicleAdapter
import dev.pleos.starter.teum.vehicle.SettingCapability
import dev.pleos.starter.teum.vehicle.SettingInspection
import dev.pleos.starter.teum.vehicle.VehicleCommand
import dev.pleos.starter.teum.vehicle.VehicleFeature
import dev.pleos.starter.teum.vehicle.VehicleSafety
import dev.pleos.starter.teum.vehicle.VehicleSetting
import dev.pleos.starter.teum.vehicle.VehicleSettingsPort

internal data class WebVehicleControlSnapshot(
    val status: String = "idle",
    val area: String = "ROW_1_LEFT",
    val capability: SettingCapability? = null,
    val currentValue: Double? = null,
    val command: VehicleCommand? = null,
    val error: String? = null,
    val safetyReason: String? = null
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("status", status)
        addProperty("area", area)
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
            }
        })
        add("command", command?.let {
            JsonObject().apply {
                addProperty("id", it.id)
                addProperty("feature", it.setting.feature.name)
                addProperty("target", it.target)
                addProperty("initial", it.initial)
                addProperty("stage", it.stage.name)
                addProperty("reason", it.reason)
                addProperty("requestAcknowledged", it.requestAcknowledged)
                addProperty("readback", it.readback)
            }
        })
    }

    fun capabilityFact(feature: WebVehicleFeature): JsonObject = JsonObject().apply {
        val readable = capability?.canRead == true && currentValue?.isFinite() == true
        val readPermission = when (feature) {
            WebVehicleFeature.AMBIENT_BRIGHTNESS -> "pleos.car.permission.CAR_LIGHTS"
            WebVehicleFeature.WINDOW_POSITION -> "pleos.car.permission.CAR_WINDOWS"
            else -> null
        }
        val writePermission = when (feature) {
            WebVehicleFeature.AMBIENT_BRIGHTNESS -> "pleos.car.permission.CONTROL_CAR_LIGHTS"
            WebVehicleFeature.WINDOW_POSITION -> "pleos.car.permission.CONTROL_CAR_WINDOWS"
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

/** Main-thread owner for explicit WebView vehicle previews and confirmed writes. */
internal class TeumWebVehicleBridge(
    private val port: VehicleSettingsPort,
    nowMillis: () -> Long,
    initialSafety: VehicleSafety,
    private val currentEpoch: () -> Long,
    private val isForeground: () -> Boolean,
    private val onChange: () -> Unit
) : AutoCloseable {
    private val settings = mapOf(
        WebVehicleFeature.AMBIENT_BRIGHTNESS to
            VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, DRIVER_AREA),
        WebVehicleFeature.WINDOW_POSITION to
            VehicleSetting(VehicleFeature.WINDOW_POSITION, DRIVER_AREA)
    )
    private val owners = mutableMapOf<WebVehicleFeature, Long>()
    private val adapter = SafeVehicleAdapter(port, nowMillis, ::onCommand)
    @Volatile
    private var states = settings.mapValues { (_, setting) ->
        WebVehicleControlSnapshot(area = setting.area)
    }
    private var closed = false

    init {
        adapter.updateSafety(initialSafety)
    }

    fun state(feature: WebVehicleFeature): WebVehicleControlSnapshot =
        states[feature] ?: WebVehicleControlSnapshot()

    fun controlsJson(): JsonObject = JsonObject().apply {
        states.forEach { (feature, snapshot) -> add(feature.name, snapshot.toJson()) }
    }

    fun capabilityFactsJson(): JsonObject = JsonObject().apply {
        states.forEach { (feature, snapshot) ->
            add(when (feature) {
                WebVehicleFeature.AMBIENT_BRIGHTNESS -> "light"
                WebVehicleFeature.WINDOW_POSITION -> "window"
                else -> feature.name.lowercase()
            }, snapshot.capabilityFact(feature))
        }
    }

    fun inspect(feature: WebVehicleFeature, requestEpoch: Long) {
        val setting = settings[feature] ?: return
        if (!current(requestEpoch)) return
        owners[feature] = requestEpoch
        val blocked = adapter.blockedReason()
        if (blocked != null) {
            update(feature, WebVehicleControlSnapshot(status = "blocked", area = setting.area,
                error = blocked, safetyReason = blocked))
            return
        }
        update(feature, WebVehicleControlSnapshot(status = "checking", area = setting.area))
        val callback: (Result<SettingInspection>) -> Unit = callback@{ result ->
            if (!current(requestEpoch) || owners[feature] != requestEpoch) return@callback
            adapter.blockedReason()?.let { blocked ->
                update(feature, WebVehicleControlSnapshot(
                    status = "blocked", area = setting.area, error = blocked, safetyReason = blocked
                ))
                return@callback
            }
            result.fold(
                onSuccess = { inspected ->
                    val capability = inspected.capability
                    if (!capability.canRead || !capability.accepts(inspected.value)) {
                        update(feature, WebVehicleControlSnapshot(
                            status = "failed", area = setting.area, capability = capability,
                            error = if (!capability.canRead) "Ambient brightness is not readable"
                                else "Current brightness is outside the reported capability range"
                        ))
                    } else {
                        update(feature, WebVehicleControlSnapshot(
                            status = if (capability.canWrite) "ready" else "read_only",
                            area = setting.area, capability = capability, currentValue = inspected.value,
                            error = capability.reason.takeUnless { capability.canWrite }
                        ))
                    }
                },
                onFailure = { error ->
                    update(feature, WebVehicleControlSnapshot(
                        status = "failed", area = setting.area, error = error.message ?: error.javaClass.simpleName
                    ))
                }
            )
        }
        try {
            port.inspect(setting, callback)
        } catch (error: Exception) {
            if (current(requestEpoch) && owners[feature] == requestEpoch) {
                update(feature, WebVehicleControlSnapshot(
                    status = "failed", area = setting.area, error = error.message ?: error.javaClass.simpleName
                ))
            }
        }
    }

    fun preview(feature: WebVehicleFeature, value: Double, requestEpoch: Long) {
        val setting = settings[feature] ?: return
        if (!current(requestEpoch) || owners[feature] != requestEpoch) return
        val previous = state(feature)
        val blocked = adapter.blockedReason()
        if (blocked != null) {
            update(feature, previous.copy(status = "blocked", error = blocked, safetyReason = blocked))
            return
        }
        val capability = previous.capability
        if (capability == null || previous.currentValue == null) {
            update(feature, previous.copy(status = "failed", error = "Read the supported range before previewing a change"))
            return
        }
        if (!capability.canWrite) {
            update(feature, previous.copy(status = "read_only", error = capability.reason))
            return
        }
        if (!capability.accepts(value)) {
            update(feature, previous.copy(status = "failed",
                error = "Value is outside the reported range ${capability.min}..${capability.max} with step ${capability.step}"))
            return
        }
        adapter.cancelPending()
        update(feature, previous.copy(status = "checking", command = null, error = null, safetyReason = null))
        adapter.previewChange(setting, value)
    }

    fun confirm(previewId: Long, requestEpoch: Long) {
        if (!current(requestEpoch)) return
        val feature = states.entries.firstOrNull { (_, snapshot) ->
            snapshot.command?.let { it.id == previewId && it.stage == CommandStage.PREVIEW } == true
        }?.key ?: return
        if (owners[feature] != requestEpoch) return
        adapter.confirm(previewId, consent = true)
    }

    fun updateSafety(safety: VehicleSafety) {
        adapter.updateSafety(safety)
        val blocked = adapter.blockedReason()
        settings.forEach { (feature, _) ->
            val snapshot = state(feature)
            if (blocked != null) {
                update(feature, snapshot.copy(status = "blocked", error = blocked, safetyReason = blocked))
            } else if (snapshot.safetyReason != null) {
                update(feature, snapshot.copy(
                    status = if (snapshot.capability?.canWrite == true) "ready" else "read_only",
                    error = snapshot.capability?.reason?.takeUnless { snapshot.capability.canWrite },
                    safetyReason = null
                ))
            }
        }
    }

    fun tick() {
        adapter.tick()
        val blocked = adapter.blockedReason()
        if (blocked != null) settings.forEach { (feature, _) ->
            val snapshot = state(feature)
            if (snapshot.safetyReason != blocked)
                update(feature, snapshot.copy(status = "blocked", error = blocked, safetyReason = blocked))
        }
    }

    fun cancelPending() {
        if (!closed) adapter.cancelPending()
    }

    private fun onCommand(command: VehicleCommand) {
        val feature = settings.entries.firstOrNull { it.value == command.setting }?.key ?: return
        val owner = owners[feature] ?: return
        if (closed || !current(owner)) return
        val previous = state(feature)
        val status = when (command.stage) {
            CommandStage.PREVIEW -> "awaiting_confirmation"
            CommandStage.QUEUED -> "checking"
            CommandStage.REQUESTED -> if (command.requestAcknowledged) "acknowledged" else "requesting"
            CommandStage.READBACK -> "verified"
            CommandStage.FAILED -> "failed"
            CommandStage.UNKNOWN -> if (command.readback != null && command.readback != command.target)
                "mismatch" else "unknown"
            CommandStage.MANUAL -> "unavailable"
        }
        val error = if (command.stage in setOf(CommandStage.FAILED, CommandStage.UNKNOWN, CommandStage.MANUAL))
            command.reason else null
        update(feature, previous.copy(
            status = status,
            currentValue = command.readback ?: previous.currentValue,
            command = command,
            error = error
        ))
    }

    private fun current(epoch: Long): Boolean =
        !closed && isForeground() && epoch == currentEpoch()

    private fun update(feature: WebVehicleFeature, snapshot: WebVehicleControlSnapshot) {
        states = states + (feature to snapshot)
        onChange()
    }

    /**
     * Releasing the port invalidates local consent and suppresses this bridge's callbacks. If a
     * setter was dispatched without a completed readback, its outcome stays unknown; the next
     * foreground creates an idle bridge and must inspect again. No command is retained or replayed.
     */
    override fun close() {
        if (closed) return
        closed = true
        adapter.close()
    }

    companion object {
        const val DRIVER_AREA = "ROW_1_LEFT"
    }
}
