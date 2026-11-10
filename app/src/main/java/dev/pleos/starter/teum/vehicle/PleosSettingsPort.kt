package dev.pleos.starter.teum.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission

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
 */
class PleosSettingsPort(private val context: Context) : VehicleSettingsPort {
    private val handler = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context.applicationContext)
    private var closed = false
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
        SettingsWriteAudit.record(SettingsWriteAudit.Event.OPEN)
        handler.postDelayed(auditHeartbeat, 5_000)
    }

    private fun readPermission(setting: VehicleSetting) = when (setting.feature) {
        VehicleFeature.HVAC_TEMPERATURE -> "pleos.car.permission.CAR_CLIMATE"
        VehicleFeature.AMBIENT_BRIGHTNESS -> "pleos.car.permission.CAR_LIGHTS"
        VehicleFeature.SEAT_POSITION -> "pleos.car.permission.CAR_SEATS"
    }

    private fun writePermission(setting: VehicleSetting) = when (setting.feature) {
        VehicleFeature.HVAC_TEMPERATURE -> "pleos.car.permission.CONTROL_CAR_CLIMATE"
        VehicleFeature.AMBIENT_BRIGHTNESS -> "pleos.car.permission.CONTROL_CAR_LIGHTS"
        VehicleFeature.SEAT_POSITION -> "pleos.car.permission.CONTROL_CAR_SEATS"
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun checkRead(setting: VehicleSetting): SeatArea {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed) { "Vehicle port closed" }
        initializationError?.let { throw it }
        check(setting.feature != VehicleFeature.SEAT_POSITION) { "Seat safe stop/OEM contract unverified" }
        if (!granted(readPermission(setting))) throw SecurityException("Missing ${readPermission(setting)}")
        watchErrors[setting]?.let { throw IllegalStateException(it) }
        return SeatArea(SeatValue.valueOf(setting.area))
    }

    private fun <T> completion(result: (Result<T>) -> Unit): (Result<T>) -> Unit {
        var finished = false
        lateinit var timeout: Runnable
        val complete: (Result<T>) -> Unit = { response ->
            handler.post {
                if (!finished && !closed) {
                    finished = true
                    handler.removeCallbacks(timeout)
                    result(response)
                }
            }
        }
        timeout = Runnable { complete(Result.failure(VehicleCallbackTimeout())) }
        handler.postDelayed(timeout, 2_500)
        return complete
    }

    override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
        val complete = completion(result)
        val failed: (Exception) -> Unit = { complete(Result.failure(it)) }
        try {
            val area = checkRead(setting)
            when (setting.feature) {
                VehicleFeature.HVAC_TEMPERATURE -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_CLIMATE") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_CLIMATE")
                    vehicle.getHVAC().checkHvacTemperatureCapabilities(
                    { config, bounds, temperatures ->
                        handler.post {
                            if (!closed) try {
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
                                        if (value == null) failed(IllegalStateException("Temperature unavailable"))
                                        else {
                                            SettingsWriteAudit.record(SettingsWriteAudit.Event.INSPECTION)
                                            complete(Result.success(SettingInspection(capability, value.toDouble())))
                                        }
                                    }, failed)
                            } catch (e: Exception) { failed(e) }
                        }
                    }, failed)
                }
                VehicleFeature.AMBIENT_BRIGHTNESS -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_LIGHTS") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_LIGHTS")
                    vehicle.getLight().checkAmbientLightBrightnessCapabilities(
                    { config, bounds ->
                        handler.post {
                            if (!closed) try {
                                val range = bounds[area] ?: error("Ambient area unsupported: ${setting.area}")
                                val canRead = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                                check(canRead) { "Ambient brightness is not readable" }
                                // The installed setter annotation says CAR_LIGHTS. We additionally
                                // require the public control permission; that discrepancy is not a grant.
                                val capability = SettingCapability(true,
                                    config.accessType == AccessType.READ_WRITE && granted(writePermission(setting)),
                                    range.minValue.toDouble(), range.maxValue.toDouble(), 1.0,
                                    if (!granted(writePermission(setting))) "Missing ${writePermission(setting)}"
                                    else "Access ${config.accessType}; ${setting.area}; integer brightness")
                                if (context.checkSelfPermission("pleos.car.permission.CAR_LIGHTS") != PackageManager.PERMISSION_GRANTED)
                                    throw SecurityException("Missing pleos.car.permission.CAR_LIGHTS")
                                vehicle.getLight().getAmbientLightBrightness(area,
                                    { value ->
                                        if (value == null) failed(IllegalStateException("Brightness unavailable"))
                                        else {
                                            SettingsWriteAudit.record(SettingsWriteAudit.Event.INSPECTION)
                                            complete(Result.success(SettingInspection(capability, value.toDouble())))
                                        }
                                    }, failed)
                            } catch (e: Exception) { failed(e) }
                        }
                    }, failed)
                }
                VehicleFeature.SEAT_POSITION -> failed(IllegalStateException("Seat disabled"))
            }
        } catch (e: Exception) { failed(e) }
    }

    override fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable {
        watchers[setting]?.let { return it }
        val failed: (Exception) -> Unit = { error ->
            handler.post {
                if (!closed) {
                    watchErrors[setting] = "Observation failed: ${error.message}"
                    changed(null, ChangeOrigin.UNKNOWN)
                }
            }
        }
        try {
            val area = checkRead(setting)
            val close: AutoCloseable = when (setting.feature) {
                VehicleFeature.HVAC_TEMPERATURE -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_CLIMATE") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_CLIMATE")
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
                VehicleFeature.AMBIENT_BRIGHTNESS -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_LIGHTS") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_LIGHTS")
                    val listener = object : AmbientLightBrightnessListener {
                        override fun onBrightnessUpdated(brightness: Int?) {
                            handler.post { if (!closed) changed(brightness?.toDouble(), ChangeOrigin.UNKNOWN) }
                        }
                        override fun onFailed(exception: Exception) = failed(exception)
                    }
                    vehicle.getLight().registerAmbientLightBrightnessListener(area, listener)
                    AutoCloseable {
                        if (context.checkSelfPermission("pleos.car.permission.CAR_LIGHTS") != PackageManager.PERMISSION_GRANTED)
                            throw SecurityException("Missing pleos.car.permission.CAR_LIGHTS")
                        vehicle.getLight().unregisterAmbientLightBrightnessListener(area, listener)
                    }
                }
                VehicleFeature.SEAT_POSITION -> error("Seat disabled")
            }
            var released = false
            val subscription = AutoCloseable {
                if (!released) {
                    released = true
                    try { close.close() }
                    catch (e: Exception) { android.util.Log.w("TeumVehicle", "Settings unregister failed", e) }
                    watchers.remove(setting)
                }
            }
            watchers[setting] = subscription
            return subscription
        } catch (e: Exception) {
            watchErrors[setting] = "Subscription unavailable: ${e.message}"
            failed(e)
            return AutoCloseable { watchErrors.remove(setting) }
        }
    }

    // The required control permission depends on setting.feature; the exact
    // permission is checked below before permit or a native setter is called.
    @RequiresPermission(
        anyOf = ["pleos.car.permission.CONTROL_CAR_CLIMATE", "pleos.car.permission.CONTROL_CAR_LIGHTS"],
        conditional = true
    )
    override fun write(
        setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit
    ) {
        SettingsWriteAudit.record(SettingsWriteAudit.Event.ENTRY)
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
        try {
            val area = checkRead(setting)
            if (!granted(writePermission(setting))) throw SecurityException("Missing ${writePermission(setting)}")
            check(value.isFinite()) { "Non-finite setting" }
            // Called on the same main-thread turn as the setter, after all asynchronous preflight.
            check(permit()) { "Send cancelled by safety/consent gate" }
            when (setting.feature) {
                VehicleFeature.HVAC_TEMPERATURE -> {
                    if (context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_CLIMATE") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CONTROL_CAR_CLIMATE")
                    val hvac = vehicle.getHVAC()
                    nativeAttempted = true
                    SettingsWriteAudit.record(SettingsWriteAudit.Event.NATIVE_SETTER)
                    hvac.setHvacTemperature(
                        area, value.toFloat(), TemperatureUnit.CELSIUS, { _, _ -> acknowledged() }, failed)
                }
                VehicleFeature.AMBIENT_BRIGHTNESS -> {
                    if (context.checkSelfPermission("pleos.car.permission.CAR_LIGHTS") != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Missing pleos.car.permission.CAR_LIGHTS")
                    check(value == value.toInt().toDouble()) { "Brightness must be integer" }
                    val light = vehicle.getLight()
                    nativeAttempted = true
                    SettingsWriteAudit.record(SettingsWriteAudit.Event.NATIVE_SETTER)
                    light.setAmbientLightBrightness(
                        area, value.toInt(), { _, _ -> acknowledged() }, failed)
                }
                VehicleFeature.SEAT_POSITION -> failed(IllegalStateException("Seat disabled"))
            }
        } catch (e: Exception) { failed(e) }
    }

    override fun close() {
        if (closed) return
        SettingsWriteAudit.record(SettingsWriteAudit.Event.CLOSE)
        closed = true
        watchers.values.toList().forEach { it.close() }
        watchers.clear()
        handler.removeCallbacksAndMessages(null)
        vehicle.release()
    }
}
