package dev.pleos.starter.teum.vehicle

enum class BackrestDirection { FORWARD, BACKWARD }
enum class BackrestAck { NONE, PENDING, ACKNOWLEDGED, FAILED }

data class BackrestArea(val id: String, val minRaw: Int, val maxRaw: Int, val writable: Boolean)
data class BackrestPosition(val raw: Int?, val observedAtMillis: Long)
data class BackrestSnapshot(
    val areas: List<BackrestArea>,
    val positions: Map<String, BackrestPosition>,
    val areaId: String?,
    val busy: Boolean,
    val requestAck: BackrestAck,
    val stopAck: BackrestAck,
    val rawPositionDelta: Long?,
    val blockedReason: String?,
    val closed: Boolean,
    val preparing: Boolean = false
)

/** Values are read from Android by the production constructor, never the WebView. */
data class BackrestEnvironment(
    val debuggable: Boolean,
    val hardware: String,
    val product: String,
    val fingerprint: String
) {
    fun allowed(): Boolean = debuggable && hardware == "ranchu" &&
        product == "crp_avd_arm64" && fingerprint == FINGERPRINT

    companion object {
        // Installed Pleos Android 34 image build.prop, recorded 2026-11-12.
        const val FINGERPRINT = "Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys"
    }
}

interface BackrestPort : AutoCloseable {
    fun permissionsGranted(): Boolean
    fun inspect(done: (List<BackrestArea>) -> Unit, failed: (Exception) -> Unit)
    fun observe(areaId: String, position: (Int?) -> Unit, failed: (Exception) -> Unit): AutoCloseable
    fun read(areaId: String, position: (Int?) -> Unit, failed: (Exception) -> Unit)
    /** null direction is the SDK STOP command; STOP must bypass movement authorization. */
    fun move(areaId: String, direction: BackrestDirection?, done: () -> Unit, failed: (Exception) -> Unit)
}

fun interface BackrestScheduler {
    /** Must arm before returning; throw if the deadline cannot be scheduled. Never inline action. */
    fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable
}

/** Shared by native controllers so a replaced document cannot overlap unresolved motion. */
class BackrestCommandGate {
    private var owner: Any? = null

    @Synchronized internal fun acquire(candidate: Any): Boolean {
        if (owner != null) return false
        owner = candidate
        return true
    }

    @Synchronized internal fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }
}

/**
 * One isolated emulator controller. All entrypoints/callbacks are serialized.
 * Native scheduling bounds a pulse without a WebView pointer-up. Android scheduling
 * is not a real-time guarantee, and automatic STOP after process death is UNPROVEN.
 * This unit intentionally cannot enable release builds or real vehicles.
 *
 * A pulse remains busy until the movement callback and a subsequent STOP callback
 * settle. A STOP sent before the movement callback is followed by another STOP on
 * that late callback, since callback order does not prove physical execution order.
 * Missing callbacks retain the SDK handle and block further pulses; movement is
 * never replayed. Acknowledgments never count as physical movement evidence.
 */
open class BackrestController(
    private val port: BackrestPort,
    private val environment: BackrestEnvironment,
    private val now: () -> Long,
    private val scheduler: BackrestScheduler,
    private val commandGate: BackrestCommandGate = BackrestCommandGate()
) : AutoCloseable {
    private var vehicleSafety = VehicleSafety()
    private var foreground = false
    private var epoch = 0L
    private var inspection = 0L
    private var inspecting = false
    private var closed = false
    private var portClosed = false
    private var error: String? = null
    private var areas = emptyList<BackrestArea>()
    private val positions = mutableMapOf<String, BackrestPosition>()
    private val subscriptions = mutableListOf<AutoCloseable>()
    private val observationErrors = mutableSetOf<String>()
    private var active: Pulse? = null
    private var last: Pulse? = null
    private var pendingStart: Preflight? = null

    private class Preflight(
        val area: BackrestArea,
        val direction: BackrestDirection,
        val duration: Long,
        val epoch: Long,
        val generation: Long,
        val requestedAt: Long
    )

    private class Pulse(val area: BackrestArea, val baseline: Int, val deadline: Long) {
        var request = BackrestAck.PENDING
        var stop = BackrestAck.NONE
        var stopping = false
        var stopSerial = 0L
        var stopPending = 0
        var delta: Long? = null
        var timer: AutoCloseable? = null
    }

    @Synchronized fun inspect() {
        if (closed || inspecting || active != null || pendingStart != null) return
        clearSubscriptions()
        val token = ++inspection
        inspecting = true
        areas = emptyList()
        positions.clear()
        observationErrors.clear()
        error = null
        if (!port.permissionsGranted()) {
            inspecting = false
            error = "Seat read/control permissions missing"
            return
        }
        port.inspect({ discovered ->
            synchronized(this) {
                if (!closed && token == inspection) {
                    inspecting = false
                    areas = discovered
                    discovered.forEach { area ->
                        fun accept(raw: Int?) {
                            synchronized(this) {
                                if (!closed && token == inspection) record(area, raw)
                            }
                        }
                        fun fail(e: Exception) {
                            synchronized(this) {
                                if (!closed && token == inspection) observationFailed(area, e)
                            }
                        }
                        subscriptions += port.observe(area.id, ::accept, ::fail)
                        // An old GET must not overwrite a newer listener event.
                        val before = positions[area.id]
                        val requestedAt = now()
                        port.read(area.id, { raw ->
                            synchronized(this) {
                                if (!closed && token == inspection && positions[area.id] === before &&
                                    VehicleSafety.fresh(requestedAt, now())) record(area, raw, requestedAt)
                            }
                        }, ::fail)
                    }
                }
            }
        }, { e ->
            synchronized(this) {
                if (!closed && token == inspection) {
                    inspecting = false
                    error = e.message ?: "Backrest discovery failed"
                }
            }
        })
    }

    private fun record(area: BackrestArea, raw: Int?, observedAt: Long = now()) {
        val valid = raw?.takeIf { it in area.minRaw..area.maxRaw }
        positions[area.id] = BackrestPosition(valid, observedAt)
        last?.takeIf { it.area.id == area.id }?.let { pulse ->
            if (valid == null) {
                error = "Raw position unavailable"
                stop()
            } else {
                pulse.delta = valid.toLong() - pulse.baseline.toLong()
            }
        }
    }

    private fun observationFailed(area: BackrestArea, e: Exception) {
        observationErrors += area.id
        positions.remove(area.id)
        error = e.message ?: "Backrest observation failed"
        if (pendingStart?.area?.id == area.id) pendingStart = null
        if (active?.area?.id == area.id) stop()
    }

    @Synchronized fun safety(value: VehicleSafety, foreground: Boolean, epoch: Long) {
        val lostEpoch = this.epoch != epoch || vehicleSafety.generation != value.generation
        this.vehicleSafety = value
        this.foreground = foreground
        this.epoch = epoch
        if (lostEpoch || movementBlock() != null) stop()
    }

    private fun movementBlock(): String? = when {
        closed -> "Backrest closed"
        !environment.allowed() -> "Isolated debuggable Pleos emulator only"
        !foreground -> "App not foreground"
        !port.permissionsGranted() -> "Seat read/control permissions missing"
        else -> vehicleSafety.blockedReason(now())
    }

    /**
     * true means a click's fresh GET preflight was admitted, NOT that movement
     * occurred. busy includes this preflight; preparing distinguishes it from a
     * directional request. Human decision time since inspect is unrestricted.
     */
    @Synchronized fun start(
        areaId: String,
        direction: BackrestDirection,
        pulseMillis: Long = 500
    ): Boolean {
        if (active != null || pendingStart != null || inspecting) return false
        error = movementBlock()
        if (error != null) return false
        val area = areas.singleOrNull { it.id == areaId && it.writable }
        if (area == null || areaId in observationErrors || pulseMillis !in 1..500) {
            error = "Discovered writable area, healthy listener and 1..500ms pulse required"
            return false
        }
        val preflight = Preflight(area, direction, pulseMillis, epoch, vehicleSafety.generation, now())
        last = null
        pendingStart = preflight
        port.read(areaId, { raw ->
            synchronized(this) {
                if (pendingStart === preflight) {
                    pendingStart = null
                    val block = movementBlock()
                    val current = areas.singleOrNull { it.id == areaId && it.writable }
                    if (block != null || epoch != preflight.epoch ||
                        vehicleSafety.generation != preflight.generation ||
                        current !== preflight.area || areaId in observationErrors ||
                        !VehicleSafety.fresh(preflight.requestedAt, now()) ||
                        raw == null || raw !in area.minRaw..area.maxRaw) {
                        error = block ?: "Backrest click preflight expired or unavailable"
                    } else {
                        positions[areaId] = BackrestPosition(raw, preflight.requestedAt)
                        beginPulse(preflight, raw)
                    }
                }
            }
        }, { e ->
            synchronized(this) {
                if (pendingStart === preflight) {
                    pendingStart = null
                    error = e.message ?: "Backrest click GET failed"
                }
            }
        })
        return true
    }

    private fun beginPulse(preflight: Preflight, raw: Int) {
        val area = preflight.area
        val deadline = minOf(
            now() + preflight.duration,
            vehicleSafety.parking.observedAtMillis + VehicleSafety.MAX_AGE_MILLIS + 1,
            vehicleSafety.ux.observedAtMillis + VehicleSafety.MAX_AGE_MILLIS + 1,
            preflight.requestedAt + VehicleSafety.MAX_AGE_MILLIS + 1
        )
        val pulse = Pulse(area, raw, deadline)
        if (!commandGate.acquire(pulse)) {
            error = "Previous movement or STOP has not finished"
            return
        }
        active = pulse
        last = pulse
        try {
            // The watchdog is armed BEFORE the directional request crosses IPC.
            pulse.timer = scheduler.schedule(deadline - now()) {
                synchronized(this) { if (active === pulse) stop() }
            }
        } catch (e: RuntimeException) {
            active = null
            commandGate.release(pulse)
            pulse.request = BackrestAck.FAILED
            error = e.message ?: "Native deadline unavailable"
            return
        }
        // Scheduling itself may consume time; do not send after expiry or safety loss.
        if (now() >= deadline || movementBlock() != null) {
            pulse.timer?.close()
            active = null
            commandGate.release(pulse)
            pulse.request = BackrestAck.FAILED
            return
        }
        port.move(area.id, preflight.direction, { requestSettled(pulse, true) }, { e ->
            synchronized(this) { error = e.message ?: "Movement request failed" }
            requestSettled(pulse, false)
        })
    }

    @Synchronized private fun requestSettled(pulse: Pulse, success: Boolean) {
        if (pulse.request != BackrestAck.PENDING) return
        pulse.request = if (success) BackrestAck.ACKNOWLEDGED else BackrestAck.FAILED
        if (!success || pulse.stopping || closed || movementBlock() != null || now() >= pulse.deadline) {
            // Never restart from a callback. A late request callback requires a new STOP.
            sendStop(pulse)
        }
    }

    @Synchronized fun stop() {
        // Invalidate the explicit click before any late GET callback can arrive.
        pendingStart = null
        val pulse = active ?: return
        if (!pulse.stopping || pulse.stop == BackrestAck.FAILED) sendStop(pulse)
    }

    private fun sendStop(pulse: Pulse) {
        pulse.stopping = true
        pulse.timer?.close()
        pulse.timer = null
        val serial = ++pulse.stopSerial
        pulse.stop = BackrestAck.PENDING
        pulse.stopPending++
        var settled = false
        fun settle(success: Boolean, e: Exception? = null) {
            synchronized(this) {
                if (!settled) {
                    settled = true
                    if (e != null) error = e.message ?: "STOP failed; further movement blocked"
                    stopSettled(pulse, serial, success)
                }
            }
        }
        port.move(pulse.area.id, null, { settle(true) }, { settle(false, it) })
    }

    @Synchronized private fun stopSettled(pulse: Pulse, serial: Long, success: Boolean) {
        pulse.stopPending--
        if (serial == pulse.stopSerial) {
            pulse.stop = if (success) BackrestAck.ACKNOWLEDGED else BackrestAck.FAILED
        }
        if (pulse.request != BackrestAck.PENDING && pulse.stopPending == 0 &&
            pulse.stop == BackrestAck.ACKNOWLEDGED) {
            active = null
            commandGate.release(pulse)
        }
        releaseIfClosed()
    }

    @Synchronized fun tick() {
        pendingStart?.let {
            if (movementBlock() != null || !VehicleSafety.fresh(it.requestedAt, now())) {
                pendingStart = null
                error = "Backrest click preflight expired"
            }
        }
        active?.let {
            // A failed STOP is latched; ticks must not create an automatic retry loop.
            if (!it.stopping && (movementBlock() != null || now() >= it.deadline ||
                positions[it.area.id]?.let { p -> VehicleSafety.fresh(p.observedAtMillis, now()) } != true)) stop()
        }
    }

    @Synchronized fun snapshot(): BackrestSnapshot = BackrestSnapshot(
        areas.toList(), positions.toMap(), pendingStart?.area?.id ?: last?.area?.id,
        active != null || pendingStart != null,
        last?.request ?: BackrestAck.NONE, last?.stop ?: BackrestAck.NONE,
        last?.delta, movementBlock() ?: error, closed, pendingStart != null
    )

    private fun clearSubscriptions() {
        subscriptions.forEach { it.close() }
        subscriptions.clear()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        inspection++
        clearSubscriptions()
        stop()
        releaseIfClosed()
    }

    private fun releaseIfClosed() {
        if (closed && active == null && !portClosed) {
            portClosed = true
            port.close()
        }
    }
}
