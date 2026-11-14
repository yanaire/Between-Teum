package dev.pleos.starter.teum.ui

import com.google.gson.JsonObject
import dev.pleos.starter.teum.vehicle.VehicleSettingsDiscovery
import dev.pleos.starter.teum.vehicle.CommandStage
import dev.pleos.starter.teum.vehicle.SafeVehicleAdapter
import dev.pleos.starter.teum.vehicle.SettingInspection
import dev.pleos.starter.teum.vehicle.VehicleCommand
import dev.pleos.starter.teum.vehicle.VehicleFeature
import dev.pleos.starter.teum.vehicle.VehicleSafety
import dev.pleos.starter.teum.vehicle.VehicleSetting
import dev.pleos.starter.teum.vehicle.VehicleSettingsPort

/** Main-thread owner for explicit WebView vehicle previews and confirmed writes. */
internal class TeumWebVehicleBridge(
    private val port: VehicleSettingsPort,
    nowMillis: () -> Long,
    initialSafety: VehicleSafety,
    private val currentEpoch: () -> Long,
    private val isForeground: () -> Boolean,
    private val onChange: () -> Unit
) : AutoCloseable {
    private val features = mapOf(
        WebVehicleFeature.AMBIENT_BRIGHTNESS to VehicleFeature.AMBIENT_BRIGHTNESS,
        WebVehicleFeature.AMBIENT_COLOR to VehicleFeature.AMBIENT_COLOR,
        WebVehicleFeature.AMBIENT_SWITCH to VehicleFeature.AMBIENT_SWITCH,
        WebVehicleFeature.WINDOW_POSITION to VehicleFeature.WINDOW_POSITION,
        WebVehicleFeature.SEAT_VENTILATION to VehicleFeature.SEAT_VENTILATION,
        WebVehicleFeature.SMART_GLASS_STATE to VehicleFeature.SMART_GLASS_STATE,
        WebVehicleFeature.SMART_GLASS_LEVEL to VehicleFeature.SMART_GLASS_LEVEL
    )
    private val discovery = requireNotNull(port as? VehicleSettingsDiscovery)
    private val settings = mutableMapOf<WebVehicleFeature, VehicleSetting>()
    private val inspections = mutableMapOf<WebVehicleFeature, Long>()
    private val owners = mutableMapOf<WebVehicleFeature, Long>()
    private val adapter = SafeVehicleAdapter(port, nowMillis, ::onCommand)
    @Volatile
    private var states = features.mapValues { WebVehicleControlSnapshot() }
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

    fun inspect(feature: WebVehicleFeature, requestEpoch: Long, area: String? = null) {
        val nativeFeature = features[feature] ?: return
        if (!current(requestEpoch) || commandInFlight()) return
        adapter.cancelPending()
        owners[feature] = requestEpoch
        val request = (inspections[feature] ?: 0L) + 1
        inspections[feature] = request
        val blocked = adapter.blockedReason()
        if (blocked != null) {
            update(feature, state(feature).copy(status = "blocked",
                error = blocked, safetyReason = blocked))
            return
        }
        val requestedArea = area ?: settings[feature]?.area
        update(feature, state(feature).copy(status = "checking", command = null, error = null))
        discovery.discover(nativeFeature) discovered@{ result ->
            if (!current(requestEpoch) || inspections[feature] != request) return@discovered
            val found = result.getOrElse {
                update(feature, WebVehicleControlSnapshot(status = "failed", error = it.message))
                return@discovered
            }
            val chosen = if (requestedArea == null) found.firstOrNull()
                else found.firstOrNull { it.setting.area == requestedArea }
            if (chosen == null) {
                settings.remove(feature)
                update(feature, WebVehicleControlSnapshot(status = "unavailable", areas = found,
                    error = "The requested area is not supported"))
                return@discovered
            }
            settings[feature] = chosen.setting
            update(feature, WebVehicleControlSnapshot(status = "checking",
                area = chosen.setting.area, areas = found))
            inspectSelected(feature, chosen.setting, requestEpoch, request)
        }
    }

    private fun inspectSelected(
        feature: WebVehicleFeature, setting: VehicleSetting, requestEpoch: Long, request: Long
    ) {
        val callback: (Result<SettingInspection>) -> Unit = callback@{ result ->
            if (!current(requestEpoch) || owners[feature] != requestEpoch ||
                inspections[feature] != request) return@callback
            adapter.blockedReason()?.let { blocked ->
                update(feature, state(feature).copy(
                    status = "blocked", area = setting.area, error = blocked, safetyReason = blocked
                ))
                return@callback
            }
            result.fold(
                onSuccess = { inspected ->
                    val capability = inspected.capability
                    if (!capability.canRead || !capability.accepts(inspected.value)) {
                        update(feature, state(feature).copy(
                            status = "failed", area = setting.area, capability = capability,
                            currentValue = inspected.value,
                            error = if (!capability.canRead) "Setting is not readable"
                                else "Current value ${inspected.value} is outside the reported capability range"
                        ))
                    } else {
                        update(feature, state(feature).copy(
                            status = if (capability.canWrite) "ready" else "read_only",
                            area = setting.area, capability = capability, currentValue = inspected.value,
                            error = capability.reason.takeUnless { capability.canWrite }
                        ))
                    }
                },
                onFailure = { error ->
                    update(feature, state(feature).copy(
                        status = "failed", area = setting.area, error = error.message ?: error.javaClass.simpleName
                    ))
                }
            )
        }
        try {
            port.inspect(setting, callback)
        } catch (error: Exception) {
            if (current(requestEpoch) && owners[feature] == requestEpoch &&
                inspections[feature] == request) {
                update(feature, state(feature).copy(
                    status = "failed", area = setting.area, error = error.message ?: error.javaClass.simpleName
                ))
            }
        }
    }

    fun preview(feature: WebVehicleFeature, value: Double, requestEpoch: Long) {
        val setting = settings[feature] ?: return
        if (!current(requestEpoch) || owners[feature] != requestEpoch || commandInFlight()) return
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
        adapter.confirm(previewId, consent = true,
            executionAllowed = { current(requestEpoch) && owners[feature] == requestEpoch })
    }

    fun updateSafety(safety: VehicleSafety) {
        adapter.updateSafety(safety)
        val blocked = adapter.blockedReason()
        features.forEach { (feature, _) ->
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
        if (blocked != null) features.forEach { (feature, _) ->
            val snapshot = state(feature)
            if (snapshot.safetyReason != blocked)
                update(feature, snapshot.copy(status = "blocked", error = blocked, safetyReason = blocked))
        }
    }

    fun cancelPending() {
        if (!closed) adapter.cancelPending()
    }

    fun commandInFlight(): Boolean = states.values.any {
        it.command?.stage in setOf(CommandStage.QUEUED, CommandStage.REQUESTED)
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

}
