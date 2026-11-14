package dev.pleos.starter.teum.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission

/**
 * Bounded teardown for one-shot Vehicle calls, keyed on REAL native callbacks.
 *
 * Close revokes new work immediately ([beginCall] returns false). Every native
 * SDK request takes one [NativeGuard] via [beginNative]; the guard settles only
 * when that request's own onComplete/onFailed fires, or when its subscription
 * unregister succeeds. The wrapper timeout is purely logical: it fails the
 * caller with [VehicleCallbackTimeout] but never settles a guard, so a hung or
 * late native callback still blocks [onRelease]. [onRelease] runs a single time
 * when the port is closed and no native request is outstanding.
 *
 * Consequences, stated plainly: a native request the SDK never answers retains
 * the Vehicle handle indefinitely; the port leaks it rather than release ahead
 * of an unanswered callback. A failed unregister likewise retains its guard
 * (and the handle), because the live subscription may still deliver callbacks;
 * no retry is attempted once the subscription is released.
 * Pure Kotlin with no Android dependencies so the contract is unit-testable.
 */
internal class PortLifecycle {
    private var closed = false
    private var nativeInflight = 0
    private var released = false
    var onRelease: (() -> Unit)? = null

    @Synchronized fun beginCall(): Boolean = !closed

    @Synchronized fun beginNative(): NativeGuard {
        nativeInflight++
        return NativeGuard(this)
    }

    @Synchronized internal fun endNative() {
        if (nativeInflight > 0) nativeInflight--
        drain()
    }

    @Synchronized fun close() {
        closed = true
        drain()
    }

    @Synchronized fun isClosed(): Boolean = closed

    @Synchronized fun nativeInflightCount(): Int = nativeInflight

    private fun drain() {
        if (closed && nativeInflight == 0 && !released) {
            released = true
            onRelease?.invoke()
        }
    }
}

/** Exactly-once settlement for one native SDK request. First callback wins. */
internal class NativeGuard(private val lifecycle: PortLifecycle) {
    private var settled = false

    @Synchronized fun settle() {
        if (!settled) {
            settled = true
            lifecycle.endNative()
        }
    }

    @Synchronized fun isSettled(): Boolean = settled
}

/**
 * Process-wide same-setting native setter exclusion.
 *
 * A second physical setter for a setting with a live native request is
 * rejected before it is issued, so two setters can never overlap and a late
 * first callback cannot land after a second readback (reverse completion).
 * The guard belongs to the issuing port's own lifecycle, so each port still
 * releases only after its own real callbacks settle; this object only maps
 * the setting to the live guard. Entries exist solely while a setter is in
 * flight and are removed on settle.
 */
internal object NativeSetterExclusion {
    private val guards = java.util.concurrent.ConcurrentHashMap<VehicleSetting, NativeGuard>()

    private fun domain(feature: VehicleFeature): String = when (feature) {
        VehicleFeature.AMBIENT_BRIGHTNESS, VehicleFeature.AMBIENT_COLOR, VehicleFeature.AMBIENT_SWITCH -> "AMBIENT"
        VehicleFeature.SMART_GLASS_STATE, VehicleFeature.SMART_GLASS_LEVEL -> "SMART_GLASS"
        VehicleFeature.HVAC_TEMPERATURE, VehicleFeature.SEAT_POSITION,
        VehicleFeature.WINDOW_POSITION, VehicleFeature.SEAT_VENTILATION -> feature.name
    }

    @Synchronized fun tryBegin(setting: VehicleSetting, lifecycle: PortLifecycle): NativeGuard? {
        if (lifecycle.isClosed()) return null
        val areas = setting.area.split("+")
        if (guards.any { (other, live) ->
                !live.isSettled() && domain(other.feature) == domain(setting.feature) &&
                    (other.area == setting.area || other.area.endsWith("GLOBAL") ||
                        setting.area.endsWith("GLOBAL") || other.area.split("+").any { it in areas })
            }) return null
        val guard = lifecycle.beginNative()
        guards[setting] = guard
        return guard
    }

    fun settle(setting: VehicleSetting, guard: NativeGuard?) {
        if (guard != null) {
            guard.settle()
            guards.remove(setting, guard)
        }
    }

    fun liveCount(): Int = guards.size
}

/**
 * Unregister with a retryable detached state.
 *
 * Success runs [onDetached] exactly once. Failure leaves the subscription
 * attached: the guard (and handle) stay live and a later [close] retries the
 * unregister instead of silently dropping it.
 */
internal class GuardedSubscription(
    private val unregister: () -> Unit,
    private val onDetached: () -> Unit,
    private val onFailure: (Exception) -> Unit
) : AutoCloseable {
    private var released = false

    @Synchronized
    override fun close() {
        if (released) return
        released = true
        try {
            unregister()
            onDetached()
        } catch (e: Exception) {
            released = false
            onFailure(e)
        }
    }
}

/** Local process counters; no setting values, user text, analytics or persistence. */
internal object SettingsWriteAudit {
    enum class Event { OPEN, HEARTBEAT, CLOSE, ENTRY, REJECTED, NATIVE_SETTER, ACK, NATIVE_FAILURE, INSPECTION }
    private val counts = LongArray(Event.entries.size)

    @Synchronized fun record(event: Event): String {
        counts[event.ordinal]++
        val json = org.json.JSONObject()
            .put("event", event.name)
            .put("pid", android.os.Process.myPid())
            .put("elapsedMs", android.os.SystemClock.elapsedRealtime())
        Event.entries.forEach { json.put(it.name, counts[it.ordinal]) }
        return json.toString().also { android.util.Log.i("TeumWriteAudit", it) }
    }
}

/**
 * Vehicle 2.3.2 binding. No grants, reflection, retries, or fabricated readbacks.
 * Call on the main thread. The SDK has no documented initialize(connection)
 * overload; only successful capability/get callbacks establish usable access.
 *
 * Teardown: close() stops new SDK calls at once, unregisters live listeners so
 * no further subscription update is delivered, and keeps every in-flight
 * native request tracked until its own callback fires; the Vehicle release
 * runs only after the last real native request settles. A call the SDK never
 * answers still fails its caller via the 2.5s logical timeout, but that timeout
 * never settles the native guard: with no native callback the handle is
 * retained, not released, because releasing ahead of an unanswered request
 * would violate the SDK contract. The permit check and its native setter run
 * on the same main-thread turn with no async gap, so a close cannot land
 * between consent and send; after close, write() is rejected before permit
 * is even consulted. One guard spans a whole capability-then-get chain: the
 * follow-up get is issued before the capability guard settles, and a close
 * landing mid-chain aborts the follow-up so no new SDK call is issued.
 * Same-setting physical setters are mutually excluded process-wide until the
 * real native callback settles: a duplicate write() is rejected before permit,
 * so logical timeout, consent cancel, or close cannot open a second
 * overlapping setter and a late first callback cannot land after a second
 * readback.
 */
class PleosSettingsPort(private val context: Context) : VehicleSettingsPort, VehicleSettingsDiscovery {
    private val handler = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context.applicationContext)
    private var closed = false
    private val lifecycle = PortLifecycle()
    private val discovered = PleosSettingBindings(
        { vehicle.getHVAC() }, { vehicle.getLight() }, { vehicle.getSeat() }, { vehicle.getWindow() },
        { feature -> granted(writePermission(VehicleSetting(feature, ""))) },
        { action -> handler.post { action() } }
    )
    private val pendingDiscovered = mutableSetOf<() -> Unit>()
    private val watchers = mutableMapOf<VehicleSetting, AutoCloseable>()
    private val watchErrors = mutableMapOf<VehicleSetting, String>()
    private val initializationError: Exception? = try {
        vehicle.initialize()
        null
    } catch (e: RuntimeException) { e }
    private val auditHeartbeat = object : Runnable {
        override fun run() {
            if (!closed) {
                SettingsWriteAudit.record(SettingsWriteAudit.Event.HEARTBEAT)
                handler.postDelayed(this, 5_000)
            }
        }
    }

    init {
        // Guard settles can fire on an SDK callback thread; the release itself
        // always runs on the main handler. Deferring it opens no gap: close()
        // sets the closed flag synchronously, so no new SDK call is admitted
        // while the release is queued.
        lifecycle.onRelease = { handler.post { vehicle.release() } }
        SettingsWriteAudit.record(SettingsWriteAudit.Event.OPEN)
        handler.postDelayed(auditHeartbeat, 5_000)
    }

    private fun readPermission(setting: VehicleSetting) = when (setting.feature) {
        VehicleFeature.HVAC_TEMPERATURE -> "pleos.car.permission.CAR_CLIMATE"
        VehicleFeature.AMBIENT_BRIGHTNESS, VehicleFeature.AMBIENT_COLOR,
        VehicleFeature.AMBIENT_SWITCH -> "pleos.car.permission.CAR_LIGHTS"
        VehicleFeature.SEAT_POSITION, VehicleFeature.SEAT_VENTILATION -> "pleos.car.permission.CAR_SEATS"
        VehicleFeature.WINDOW_POSITION, VehicleFeature.SMART_GLASS_LEVEL,
        VehicleFeature.SMART_GLASS_STATE -> "pleos.car.permission.CAR_WINDOWS"
    }

    private fun writePermission(setting: VehicleSetting) = when (setting.feature) {
        VehicleFeature.HVAC_TEMPERATURE -> "pleos.car.permission.CONTROL_CAR_CLIMATE"
        VehicleFeature.AMBIENT_BRIGHTNESS, VehicleFeature.AMBIENT_COLOR,
        VehicleFeature.AMBIENT_SWITCH -> "pleos.car.permission.CONTROL_CAR_LIGHTS"
        VehicleFeature.SEAT_POSITION, VehicleFeature.SEAT_VENTILATION -> "pleos.car.permission.CONTROL_CAR_SEATS"
        VehicleFeature.WINDOW_POSITION, VehicleFeature.SMART_GLASS_LEVEL,
        VehicleFeature.SMART_GLASS_STATE -> "pleos.car.permission.CONTROL_CAR_WINDOWS"
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun checkUsable(setting: VehicleSetting) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed) { "Vehicle port closed" }
        initializationError?.let { throw it }
        if (!granted(readPermission(setting))) throw SecurityException("Missing ${readPermission(setting)}")
        watchErrors[setting]?.let { throw IllegalStateException(it) }
    }

    private fun seatArea(setting: VehicleSetting): SeatArea {
        check(setting.feature != VehicleFeature.SEAT_POSITION) { "Seat safe stop/OEM contract unverified" }
        return discovered.seatAreas[setting] ?: SeatArea(SeatValue.valueOf(setting.area))
    }

    private fun windowArea(setting: VehicleSetting) =
        discovered.windowAreas[setting] ?: WindowArea(WindowValue.valueOf(setting.area))

    private fun usesDiscoveredBinding(feature: VehicleFeature) = when (feature) {
        VehicleFeature.HVAC_TEMPERATURE, VehicleFeature.WINDOW_POSITION, VehicleFeature.SEAT_POSITION -> false
        VehicleFeature.AMBIENT_BRIGHTNESS, VehicleFeature.AMBIENT_COLOR, VehicleFeature.AMBIENT_SWITCH,
        VehicleFeature.SEAT_VENTILATION, VehicleFeature.SMART_GLASS_STATE, VehicleFeature.SMART_GLASS_LEVEL -> true
    }

    private inner class DiscoveredCall<T>(private val result: (Result<T>) -> Unit) {
        var active = true
            private set
        private val cancel: () -> Unit = { finish(Result.failure(IllegalStateException("Vehicle port closed"))) }
        private val timeout = Runnable { finish(Result.failure(VehicleCallbackTimeout())) }
        init {
            pendingDiscovered.add(cancel)
            handler.postDelayed(timeout, 2_500)
        }
        fun finish(response: Result<T>) {
            if (!active) return
            active = false
            handler.removeCallbacks(timeout)
            pendingDiscovered.remove(cancel)
            result(response)
        }
    }

    override fun discover(feature: VehicleFeature, result: (Result<List<DiscoveredSetting>>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val call = DiscoveredCall(result)
        var guard: NativeGuard? = null
        try {
            checkUsable(VehicleSetting(feature, ""))
            guard = lifecycle.beginNative()
            discovered.discover(feature, { items ->
                handler.post {
                    guard.settle()
                    if (call.active) call.finish(
                        if (closed) Result.failure(IllegalStateException("Vehicle port closed"))
                        else Result.success(items))
                }
            }, { error -> handler.post { guard.settle(); call.finish(Result.failure(error)) } })
        } catch (error: Exception) {
            guard?.settle()
            call.finish(Result.failure(error))
        }
    }

    private fun inspectDiscovered(
        setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit,
        allowFollowup: () -> Boolean = { true }
    ) {
        val call = DiscoveredCall(result)
        discover(setting.feature) { response ->
            if (!call.active) return@discover
            if (!allowFollowup()) {
                call.finish(Result.failure(IllegalStateException("Observation cancelled")))
                return@discover
            }
            response.fold(onFailure = { call.finish(Result.failure(it)) }, onSuccess = { items ->
                var guard: NativeGuard? = null
                try {
                    checkUsable(setting)
                    val metadata = items.singleOrNull { it.setting == setting } ?: error("Unsupported exact area: $setting")
                    check(metadata.capability.canRead) { "Setting is not readable" }
                    val binding = discovered.bindings.getValue(setting)
                    guard = lifecycle.beginNative()
                    binding.read({ value ->
                        handler.post {
                            guard.settle()
                            if (call.active) {
                                val read = if (closed) Result.failure(IllegalStateException("Vehicle port closed"))
                                else if (value == null) Result.failure(IllegalStateException("Setting unavailable"))
                                else {
                                    SettingsWriteAudit.record(SettingsWriteAudit.Event.INSPECTION)
                                    Result.success(SettingInspection(metadata.capability, value))
                                }
                                call.finish(read)
                            }
                        }
                    }, { error -> handler.post { guard.settle(); call.finish(Result.failure(error)) } })
                } catch (error: Exception) { guard?.settle(); call.finish(Result.failure(error)) }
            })
        }
    }

    /** Explicit getter polling; no fabricated switch listener and no setter replay. */
    private fun subscribeDiscovered(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable {
        watchers[setting]?.let { return it }
        if (setting.feature != VehicleFeature.AMBIENT_SWITCH) return subscribeNativeDiscovered(setting, changed)
        var active = true
        var inFlight = false
        val poll = object : Runnable {
            override fun run() {
                if (!active || closed || inFlight) return
                inFlight = true
                inspectDiscovered(setting, result = { response ->
                    inFlight = false
                    if (active && !closed) {
                        changed(response.getOrNull()?.value, ChangeOrigin.UNKNOWN)
                        // A logical timeout cannot authorize another native getter.
                        if (active && !closed && response.exceptionOrNull() !is VehicleCallbackTimeout)
                            handler.postDelayed(this, 1_000)
                    }
                }, allowFollowup = { active && !closed })
            }
        }
        val subscription = AutoCloseable {
            if (active) {
                active = false
                handler.removeCallbacks(poll)
                watchers.remove(setting)
            }
        }
        watchers[setting] = subscription
        handler.postDelayed(poll, 1_000)
        return subscription
    }

    private fun subscribeNativeDiscovered(
        setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit
    ): AutoCloseable {
        var active = true
        var native: AutoCloseable? = null
        var guard: NativeGuard? = null
        val subscription = GuardedSubscription(
            unregister = { active = false; native?.close() },
            onDetached = { guard?.settle(); watchers.remove(setting) },
            onFailure = { error -> android.util.Log.w("TeumVehicle", "Settings unregister failed; handle retained", error) }
        )
        val failed: (Exception) -> Unit = { error ->
            handler.post {
                if (active && !closed) {
                    watchErrors[setting] = "Observation failed: ${error.message}"
                    changed(null, ChangeOrigin.UNKNOWN)
                }
            }
        }
        watchers[setting] = subscription
        discover(setting.feature) { response ->
            if (!active || closed) return@discover
            response.fold(onFailure = { failed(it as? Exception ?: IllegalStateException(it)) }, onSuccess = { items ->
                try {
                    checkUsable(setting)
                    check(items.any { it.setting == setting && it.capability.canRead }) { "Unsupported readable area" }
                    guard = lifecycle.beginNative()
                    native = discovered.observe(setting, { value ->
                        handler.post { if (active && !closed) changed(value, ChangeOrigin.UNKNOWN) }
                    }, failed)
                } catch (error: Exception) {
                    guard?.settle()
                    failed(error)
                }
            })
        }
        return subscription
    }

    private fun writeDiscovered(
        setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit
    ) {
        val call = DiscoveredCall(result)
        var guard: NativeGuard? = null
        try {
            checkUsable(setting)
            if (!granted(writePermission(setting))) throw SecurityException("Missing ${writePermission(setting)}")
            val binding = discovered.bindings[setting] ?: error("Exact setting must be discovered before write")
            check(binding.discovery.capability.canWrite && binding.discovery.capability.accepts(value)) {
                "Value or access unsupported by discovered capability"
            }
            guard = NativeSetterExclusion.tryBegin(setting, lifecycle)
            check(guard != null) { "Native setter already in flight" }
            check(permit()) { "Send cancelled by safety/consent gate" }
            check(!closed && call.active) { "Vehicle port closed" }
            SettingsWriteAudit.record(SettingsWriteAudit.Event.NATIVE_SETTER)
            binding.write(value, {
                NativeSetterExclusion.settle(setting, guard)
                handler.post {
                    SettingsWriteAudit.record(SettingsWriteAudit.Event.ACK)
                    call.finish(Result.success(Unit))
                }
            }, { error ->
                NativeSetterExclusion.settle(setting, guard)
                handler.post {
                    SettingsWriteAudit.record(SettingsWriteAudit.Event.NATIVE_FAILURE)
                    call.finish(Result.failure(error))
                }
            })
        } catch (error: Exception) {
            NativeSetterExclusion.settle(setting, guard)
            call.finish(Result.failure(error))
        }
    }

    private fun <T> completion(result: (Result<T>) -> Unit): (Result<T>) -> Unit {
        var finished = false
        lateinit var timeout: Runnable
        val complete: (Result<T>) -> Unit = { response ->
            handler.post {
                if (!finished) {
                    finished = true
                    handler.removeCallbacks(timeout)
                    // Logical delivery only. Native settlement is tracked
                    // separately by NativeGuard, so a timeout never implies
                    // the SDK answered.
                    // Delivered even after close: the adapter guards every late
                    // result by epoch/pending id, so nothing can resurrect.
                    result(response)
                }
            }
        }
        timeout = Runnable { complete(Result.failure(VehicleCallbackTimeout())) }
        handler.postDelayed(timeout, 2_500)
        return complete
    }

    override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
        if (usesDiscoveredBinding(setting.feature)) {
            inspectDiscovered(setting, result)
            return
        }
        val complete = completion(result)
        val failed: (Exception) -> Unit = { complete(Result.failure(it)) }
        var nativeGuard: NativeGuard? = null
        try {
            checkUsable(setting)
            check(lifecycle.beginCall()) { "Vehicle port closed" }
            when (setting.feature) {
                VehicleFeature.HVAC_TEMPERATURE -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_CLIMATE") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_CLIMATE")
                    val area = seatArea(setting)
                    nativeGuard = lifecycle.beginNative()
                    vehicle.getHVAC().checkHvacTemperatureCapabilities(
                    { config, bounds, temperatures ->
                        handler.post {
                            try {
                                check(!closed) { "Vehicle port closed" }
                                val range = bounds[area] ?: error("Temperature area unsupported: ${setting.area}")
                                val temperatureConfig = requireNotNull(temperatures) { "Temperature configuration unavailable" }
                                val canRead = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                                check(canRead) { "Temperature is not readable" }
                                check(temperatureConfig.incrementCelsius > 0) { "Temperature increment unknown" }
                                val min = maxOf(range.minValue, temperatureConfig.minCelsius).toDouble()
                                val max = minOf(range.maxValue, temperatureConfig.maxCelsius).toDouble()
                                val grid = (min - temperatureConfig.minCelsius) / temperatureConfig.incrementCelsius
                                check(kotlin.math.abs(grid - kotlin.math.round(grid)) < 0.001) { "Inconsistent temperature bounds" }
                                val capability = SettingCapability(true,
                                    config.accessType == AccessType.READ_WRITE && granted(writePermission(setting)),
                                    min, max, temperatureConfig.incrementCelsius.toDouble(),
                                    if (!granted(writePermission(setting))) "Missing ${writePermission(setting)}"
                                    else "Access ${config.accessType}; ${setting.area}; Celsius")
                                if (context.checkSelfPermission("pleos.car.permission.CAR_CLIMATE") != PackageManager.PERMISSION_GRANTED)
                                    throw SecurityException("Missing pleos.car.permission.CAR_CLIMATE")
                                vehicle.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS,
                                    { value ->
                                        nativeGuard?.settle()
                                        if (value == null) failed(IllegalStateException("Temperature unavailable"))
                                        else {
                                            SettingsWriteAudit.record(SettingsWriteAudit.Event.INSPECTION)
                                            complete(Result.success(SettingInspection(capability, value.toDouble())))
                                        }
                                    }, { e -> nativeGuard?.settle(); failed(e) })
                            } catch (e: Exception) { nativeGuard?.settle(); failed(e) }
                        }
                    }, { e -> nativeGuard?.settle(); failed(e) })
                }
                VehicleFeature.SEAT_POSITION -> failed(IllegalStateException("Seat disabled"))
                VehicleFeature.AMBIENT_BRIGHTNESS, VehicleFeature.SEAT_VENTILATION, VehicleFeature.SMART_GLASS_LEVEL,
                VehicleFeature.SMART_GLASS_STATE, VehicleFeature.AMBIENT_COLOR,
                VehicleFeature.AMBIENT_SWITCH -> error("Discovered setting routed above")
                VehicleFeature.WINDOW_POSITION -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_WINDOWS") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_WINDOWS")
                    val area = windowArea(setting)
                    nativeGuard = lifecycle.beginNative()
                    vehicle.getWindow().checkWindowPositionCapabilities(
                    { config, bounds ->
                        handler.post {
                            try {
                                check(!closed) { "Vehicle port closed" }
                                val range = bounds[area] ?: error("Window area unsupported: ${setting.area}")
                                val canRead = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                                check(canRead) { "Window position is not readable" }
                                val capability = SettingCapability(true,
                                    config.accessType == AccessType.READ_WRITE && granted(writePermission(setting)),
                                    range.minValue.toDouble(), range.maxValue.toDouble(), 1.0,
                                    if (!granted(writePermission(setting))) "Missing ${writePermission(setting)}"
                                    else "Access ${config.accessType}; ${setting.area}; integer position")
                                if (context.checkSelfPermission("pleos.car.permission.CAR_WINDOWS") != PackageManager.PERMISSION_GRANTED)
                                    throw SecurityException("Missing pleos.car.permission.CAR_WINDOWS")
                                vehicle.getWindow().getCurrentWindowPosition(area,
                                    { value ->
                                        nativeGuard?.settle()
                                        if (value == null) failed(IllegalStateException("Window position unavailable"))
                                        else {
                                            SettingsWriteAudit.record(SettingsWriteAudit.Event.INSPECTION)
                                            complete(Result.success(SettingInspection(capability, value.toDouble())))
                                        }
                                    }, { e -> nativeGuard?.settle(); failed(e) })
                            } catch (e: Exception) { nativeGuard?.settle(); failed(e) }
                        }
                    }, { e -> nativeGuard?.settle(); failed(e) })
                }
            }
        } catch (e: Exception) { nativeGuard?.settle(); failed(e) }
    }

    override fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable {
        if (usesDiscoveredBinding(setting.feature)) return subscribeDiscovered(setting, changed)
        watchers[setting]?.let { return it }
        val failed: (Exception) -> Unit = { error ->
            handler.post {
                if (!closed) {
                    watchErrors[setting] = "Observation failed: ${error.message}"
                    changed(null, ChangeOrigin.UNKNOWN)
                }
            }
        }
        var nativeGuard: NativeGuard? = null
        try {
            checkUsable(setting)
            val close: AutoCloseable = when (setting.feature) {
                VehicleFeature.HVAC_TEMPERATURE -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_CLIMATE") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_CLIMATE")
                    val area = seatArea(setting)
                    nativeGuard = lifecycle.beginNative()
                    val listener = object : HvacTemperatureValueListener {
                        override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) {
                            handler.post { if (!closed) changed(celsius?.toDouble(), ChangeOrigin.UNKNOWN) }
                        }
                        override fun onFailed(exception: Exception) = failed(exception)
                    }
                    vehicle.getHVAC().registerHvacTemperature(area, listener)
                    AutoCloseable {
                        if (context.checkSelfPermission("pleos.car.permission.CAR_CLIMATE") != PackageManager.PERMISSION_GRANTED)
                            throw SecurityException("Missing pleos.car.permission.CAR_CLIMATE")
                        vehicle.getHVAC().unregisterHvacTemperature(area, listener)
                    }
                }
                VehicleFeature.SEAT_POSITION -> error("Seat disabled")
                VehicleFeature.AMBIENT_BRIGHTNESS, VehicleFeature.SEAT_VENTILATION, VehicleFeature.SMART_GLASS_LEVEL,
                VehicleFeature.SMART_GLASS_STATE, VehicleFeature.AMBIENT_COLOR,
                VehicleFeature.AMBIENT_SWITCH -> error("Discovered setting routed above")
                VehicleFeature.WINDOW_POSITION -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_WINDOWS") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_WINDOWS")
                    val area = windowArea(setting)
                    nativeGuard = lifecycle.beginNative()
                    val listener = object : WindowPositionListener {
                        override fun onWindowPositionUpdated(position: Int?) {
                            handler.post { if (!closed) changed(position?.toDouble(), ChangeOrigin.UNKNOWN) }
                        }
                        override fun onFailed(exception: Exception) = failed(exception)
                    }
                    vehicle.getWindow().registerWindowPosition(area, listener)
                    AutoCloseable {
                        if (context.checkSelfPermission("pleos.car.permission.CAR_WINDOWS") != PackageManager.PERMISSION_GRANTED)
                            throw SecurityException("Missing pleos.car.permission.CAR_WINDOWS")
                        vehicle.getWindow().unregisterWindowPosition(area, listener)
                    }
                }
            }
            val subscription = GuardedSubscription(
                unregister = { close.close() },
                onDetached = { nativeGuard?.settle(); watchers.remove(setting) },
                onFailure = { e -> android.util.Log.w("TeumVehicle", "Settings unregister failed; will retry", e) }
            )
            watchers[setting] = subscription
            return subscription
        } catch (e: Exception) {
            nativeGuard?.settle()
            watchErrors[setting] = "Subscription unavailable: ${e.message}"
            failed(e)
            return AutoCloseable { watchErrors.remove(setting) }
        }
    }

    // The required control permission depends on setting.feature; the exact
    // permission is checked below before permit or a native setter is called.
    @RequiresPermission(
        anyOf = ["pleos.car.permission.CONTROL_CAR_CLIMATE", "pleos.car.permission.CONTROL_CAR_LIGHTS",
            "pleos.car.permission.CONTROL_CAR_WINDOWS", "pleos.car.permission.CONTROL_CAR_SEATS"],
        conditional = true
    )
    override fun write(
        setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit
    ) {
        SettingsWriteAudit.record(SettingsWriteAudit.Event.ENTRY)
        if (usesDiscoveredBinding(setting.feature)) {
            writeDiscovered(setting, value, permit, result)
            return
        }
        var nativeAttempted = false
        val complete = completion(result)
        val failed: (Exception) -> Unit = {
            SettingsWriteAudit.record(if (nativeAttempted) SettingsWriteAudit.Event.NATIVE_FAILURE else SettingsWriteAudit.Event.REJECTED)
            complete(Result.failure(it))
        }
        val acknowledged: () -> Unit = {
            SettingsWriteAudit.record(SettingsWriteAudit.Event.ACK)
            complete(Result.success(Unit))
        }
        var nativeGuard: NativeGuard? = null
        try {
            checkUsable(setting)
            check(lifecycle.beginCall()) { "Vehicle port closed" }
            if (!granted(writePermission(setting))) throw SecurityException("Missing ${writePermission(setting)}")
            check(value.isFinite()) { "Non-finite setting" }
            // Duplicate physical setters are rejected here, before permit is
            // consulted, so a cancelled or timed-out first setter still
            // blocks a second one until its real native callback settles.
            nativeGuard = NativeSetterExclusion.tryBegin(setting, lifecycle)
            check(nativeGuard != null) { "Same-setting setter already in flight" }
            // Called on the same main-thread turn as the setter, after all asynchronous preflight.
            check(permit()) { "Send cancelled by safety/consent gate" }
            when (setting.feature) {
                VehicleFeature.HVAC_TEMPERATURE -> {
                    if (context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_CLIMATE") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CONTROL_CAR_CLIMATE")
                    val area = seatArea(setting)
                    val hvac = vehicle.getHVAC()
                    nativeAttempted = true
                    SettingsWriteAudit.record(SettingsWriteAudit.Event.NATIVE_SETTER)
                    hvac.setHvacTemperature(
                        area, value.toFloat(), TemperatureUnit.CELSIUS,
                        { _, _ -> NativeSetterExclusion.settle(setting, nativeGuard); acknowledged() },
                        { e -> NativeSetterExclusion.settle(setting, nativeGuard); failed(e) })
                }
                VehicleFeature.SEAT_POSITION -> error("Seat disabled")
                VehicleFeature.AMBIENT_BRIGHTNESS, VehicleFeature.SEAT_VENTILATION, VehicleFeature.SMART_GLASS_LEVEL,
                VehicleFeature.SMART_GLASS_STATE, VehicleFeature.AMBIENT_COLOR,
                VehicleFeature.AMBIENT_SWITCH -> error("Discovered setting routed above")
                VehicleFeature.WINDOW_POSITION -> {
                    if (context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_WINDOWS") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CONTROL_CAR_WINDOWS")
                    val area = windowArea(setting)
                    check(value == value.toInt().toDouble()) { "Window position must be integer" }
                    val window = vehicle.getWindow()
                    nativeAttempted = true
                    SettingsWriteAudit.record(SettingsWriteAudit.Event.NATIVE_SETTER)
                    window.setWindowPosition(
                        area, value.toInt(),
                        { _, _ -> NativeSetterExclusion.settle(setting, nativeGuard); acknowledged() },
                        { e -> NativeSetterExclusion.settle(setting, nativeGuard); failed(e) })
                }
            }
        } catch (e: Exception) { NativeSetterExclusion.settle(setting, nativeGuard); failed(e) }
    }

    override fun close() {
        if (closed) return
        SettingsWriteAudit.record(SettingsWriteAudit.Event.CLOSE)
        closed = true
        pendingDiscovered.toList().forEach { it() }
        watchers.values.toList().forEach { it.close() }
        // No blind clear: detached subscriptions removed themselves, while a
        // failed unregister stays mapped with its guard live so the handle is
        // retained (not released) and a later close of that handle can retry.
        // Heartbeat only: in-flight call timeouts must still fire so each
        // outstanding callback settles and the Vehicle release is not rushed.
        handler.removeCallbacks(auditHeartbeat)
        lifecycle.close()
    }
}
