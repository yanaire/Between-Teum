package dev.pleos.starter.teum.vehicle

import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.SmartGlassLevelListener
import ai.pleos.playground.vehicle.listener.SmartGlassStateListener

// Vehicle 2.3.2 LightSwitch.kt maps to these wire constants in vehicle-data 1.1.11.
// That constants class is absent from the exported SDK compile API. These are
// the installed constants' exact values (AUTOMATIC is 256), never enum ordinals.
internal fun LightSwitch.settingCode(): Double = when (this) {
    LightSwitch.OFF -> 0.0
    LightSwitch.ON -> 1.0
    LightSwitch.DAYTIME_RUNNING -> 2.0
    LightSwitch.AUTOMATIC -> 256.0
}

internal fun SeatArea.discoveredValues() = values.map { DiscoveredAreaValue(it.name, it.id) }
internal fun WindowArea.discoveredValues() = values.map { DiscoveredAreaValue(it.name, it.id) }
internal fun discoveredAreaId(values: List<DiscoveredAreaValue>, global: String): String =
    if (values.isEmpty()) global else values.joinToString("+") { it.name }

/** The returned area objects are captured, never reconstructed or split into guessed zones. */
internal class PleosSettingBindings(
    private val hvac: () -> HVAC,
    private val light: () -> Light,
    private val seat: () -> Seat,
    private val window: () -> Window,
    private val canWrite: (VehicleFeature) -> Boolean,
    private val dispatch: (() -> Unit) -> Unit = { it() }
) {
    private fun <A> main1(fail: (Exception) -> Unit, action: (A) -> Unit): (A) -> Unit =
        { a -> dispatch { try { action(a) } catch (error: Exception) { fail(error) } } }
    private fun <A, B> main2(fail: (Exception) -> Unit, action: (A, B) -> Unit): (A, B) -> Unit =
        { a, b -> dispatch { try { action(a, b) } catch (error: Exception) { fail(error) } } }
    private fun <A, B, C> main3(fail: (Exception) -> Unit, action: (A, B, C) -> Unit): (A, B, C) -> Unit =
        { a, b, c -> dispatch { try { action(a, b, c) } catch (error: Exception) { fail(error) } } }
    internal data class Binding(
        val discovery: DiscoveredSetting,
        val read: ((Double?) -> Unit, (Exception) -> Unit) -> Unit,
        val write: (Double, () -> Unit, (Exception) -> Unit) -> Unit
    )

    val bindings = mutableMapOf<VehicleSetting, Binding>()
    val seatAreas = mutableMapOf<VehicleSetting, SeatArea>()
    val windowAreas = mutableMapOf<VehicleSetting, WindowArea>()

    fun observe(setting: VehicleSetting, changed: (Double?) -> Unit, fail: (Exception) -> Unit): AutoCloseable {
        check(bindings.getValue(setting).discovery.capability.canRead) { "Setting is not readable" }
        return when (setting.feature) {
            VehicleFeature.AMBIENT_BRIGHTNESS -> {
                val area = seatAreas.getValue(setting)
                val listener = object : AmbientLightBrightnessListener {
                    override fun onBrightnessUpdated(brightness: Int?) = changed(brightness?.toDouble())
                    override fun onFailed(exception: Exception) = fail(exception)
                }
                light().registerAmbientLightBrightnessListener(area, listener)
                AutoCloseable { light().unregisterAmbientLightBrightnessListener(area, listener) }
            }
            VehicleFeature.AMBIENT_COLOR -> {
                val area = seatAreas.getValue(setting)
                val listener = object : AmbientLightBrightnessColorListener {
                    override fun onBrightnessColorUpdated(argbColor: Int?) = changed(argbColor?.toDouble())
                    override fun onFailed(exception: Exception) = fail(exception)
                }
                light().registerAmbientLightBrightnessColorListener(area, listener)
                AutoCloseable { light().unregisterAmbientLightBrightnessColorListener(area, listener) }
            }
            VehicleFeature.SEAT_VENTILATION -> {
                val area = seatAreas.getValue(setting)
                val listener = object : HvacSeatVentilationListener {
                    override fun onHvacSeatVentilationUpdated(ventilation: Int?) = changed(ventilation?.toDouble())
                    override fun onFailed(exception: Exception) = fail(exception)
                }
                seat().registerHvacSeatVentilation(area, listener)
                AutoCloseable { seat().unregisterHvacSeatVentilation(area, listener) }
            }
            VehicleFeature.SMART_GLASS_LEVEL -> {
                val area = windowAreas.getValue(setting)
                val listener = object : SmartGlassLevelListener {
                    override fun onSmartGlassLevelUpdated(level: Int?) = changed(level?.toDouble())
                    override fun onFailed(exception: Exception) = fail(exception)
                }
                window().registerSmartGlassLevel(area, listener)
                AutoCloseable { window().unregisterSmartGlassLevel(area, listener) }
            }
            VehicleFeature.SMART_GLASS_STATE -> {
                val listener = object : SmartGlassStateListener {
                    override fun onSmartGlassStateUpdated(state: Boolean?) =
                        changed(state?.let { if (it) 1.0 else 0.0 })
                    override fun onFailed(exception: Exception) = fail(exception)
                }
                window().registerSmartGlassState(listener)
                AutoCloseable { window().unregisterSmartGlassState(listener) }
            }
            VehicleFeature.AMBIENT_SWITCH -> error("Ambient switch has no native listener; poll its getter")
            VehicleFeature.HVAC_TEMPERATURE, VehicleFeature.SEAT_POSITION,
            VehicleFeature.WINDOW_POSITION -> error("Observation handled by the existing port")
        }
    }

    fun discover(feature: VehicleFeature, done: (List<DiscoveredSetting>) -> Unit, fail: (Exception) -> Unit) {
        fun capability(config: CapabilityConfig, min: Double, max: Double, step: Double = 1.0,
                       allowed: List<Double>? = null, labels: Map<Double, String> = emptyMap()) =
            SettingCapability(
                config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE,
                (config.accessType == AccessType.WRITE || config.accessType == AccessType.READ_WRITE) && canWrite(feature),
                min, max, step, "SDK ${config.accessType}; exact capability area",
                allowed, labels
            )
        fun publish(items: List<Binding>) {
            bindings.keys.removeAll { it.feature == feature }
            items.forEach { bindings[it.discovery.setting] = it }
            done(items.map { it.discovery })
        }
        fun seatBinding(area: SeatArea, cap: SettingCapability,
                        read: ((Double?) -> Unit, (Exception) -> Unit) -> Unit,
                        write: (Double, () -> Unit, (Exception) -> Unit) -> Unit): Binding {
            val values = area.discoveredValues()
            val setting = VehicleSetting(feature, discoveredAreaId(values, "SEAT_GLOBAL"))
            seatAreas[setting] = area
            return Binding(DiscoveredSetting(setting, cap, values,
                if (feature == VehicleFeature.AMBIENT_SWITCH) "POLL_GETTER" else "NATIVE_LISTENER"), read, write)
        }
        fun windowBinding(area: WindowArea, cap: SettingCapability,
                          read: ((Double?) -> Unit, (Exception) -> Unit) -> Unit,
                          write: (Double, () -> Unit, (Exception) -> Unit) -> Unit): Binding {
            val values = area.discoveredValues()
            val setting = VehicleSetting(feature, discoveredAreaId(values, "WINDOW_GLOBAL"))
            windowAreas[setting] = area
            return Binding(DiscoveredSetting(setting, cap, values), read, write)
        }
        when (feature) {
            VehicleFeature.AMBIENT_BRIGHTNESS -> light().checkAmbientLightBrightnessCapabilities(main2(fail) { config, bounds ->
                publish(bounds.map { (area, range) ->
                    seatBinding(area, capability(config, range.minValue.toDouble(), range.maxValue.toDouble()),
                        { ok, bad -> light().getAmbientLightBrightness(area, { ok(it?.toDouble()) }, bad) },
                        { value, ok, bad -> light().setAmbientLightBrightness(area, value.toInt(), { _, _ -> ok() }, bad) })
                })
            }, fail)
            VehicleFeature.AMBIENT_COLOR -> light().checkAmbientLightBrightnessColorCapabilities(main2(fail) { config, areas ->
                publish(areas.map { area ->
                    // Keep the SDK's signed Int unchanged. The emulator also returns
                    // 0x00FFFFFF; a zero alpha byte must not discard an observed color.
                    seatBinding(area, capability(config, Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()),
                        { ok, bad -> light().getAmbientLightBrightnessColor(area, { ok(it?.toDouble()) }, bad) },
                        { value, ok, bad -> light().setAmbientLightBrightnessColor(area, value.toInt(), { _, _ -> ok() }, bad) })
                })
            }, fail)
            VehicleFeature.AMBIENT_SWITCH -> light().checkAmbientLightSwitchCapabilities(main2(fail) { config, areas ->
                publish(areas.map { (area, switches) ->
                    val labels = switches.associate { it.settingCode() to it.name }
                    val codes = labels.keys.toList()
                    seatBinding(area, capability(config, codes.minOrNull() ?: Double.NaN,
                        codes.maxOrNull() ?: Double.NaN, allowed = codes, labels = labels),
                        { ok, bad -> light().getAmbientLightSwitch(area, { ok(it?.settingCode()) }, bad) },
                        { value, ok, bad ->
                            val command = switches.single { it.settingCode() == value }
                            light().setAmbientLightSwitch(area, command, { _, _ -> ok() }, bad)
                        })
                })
            }, fail)
            VehicleFeature.SEAT_VENTILATION -> seat().checkSeatVentilationCapabilities(main2(fail) { config, bounds ->
                publish(bounds.map { (area, range) ->
                    seatBinding(area, capability(config, range.minValue.toDouble(), range.maxValue.toDouble()),
                        { ok, bad -> seat().getHvacSeatVentilationState(area, { ok(it?.toDouble()) }, bad) },
                        { value, ok, bad -> seat().setHvacSeatVentilation(area, value.toInt(), { _, _ -> ok() }, bad) })
                })
            }, fail)
            VehicleFeature.SMART_GLASS_LEVEL -> window().checkSmartGlassLevelCapabilities(main2(fail) { config, bounds ->
                publish(bounds.map { (area, range) ->
                    windowBinding(area, capability(config, range.minValue.toDouble(), range.maxValue.toDouble()),
                        { ok, bad -> window().getCurrentSmartGlassLevel(area, { ok(it?.toDouble()) }, bad) },
                        { value, ok, bad -> window().setSmartGlassLevel(area, value.toInt(), { _, _ -> ok() }, bad) })
                })
            }, fail)
            VehicleFeature.SMART_GLASS_STATE -> window().checkSmartGlassStateCapabilities(main1(fail) { config ->
                val setting = VehicleSetting(feature, "GLOBAL")
                publish(listOf(Binding(DiscoveredSetting(setting,
                    capability(config, 0.0, 1.0, allowed = listOf(0.0, 1.0),
                        labels = mapOf(0.0 to "OFF", 1.0 to "ON")), emptyList()),
                    { ok, bad -> window().getCurrentSmartGlassState({ ok(it?.let { state -> if (state) 1.0 else 0.0 }) }, bad) },
                    { value, ok, bad -> window().setSmartGlassState(value == 1.0, { ok() }, bad) })))
            }, fail)
            VehicleFeature.WINDOW_POSITION -> window().checkWindowPositionCapabilities(main2(fail) { config, bounds ->
                val items = bounds.map { (area, range) ->
                    val values = area.discoveredValues()
                    val setting = VehicleSetting(feature, discoveredAreaId(values, "WINDOW_GLOBAL"))
                    windowAreas[setting] = area
                    DiscoveredSetting(setting, capability(config, range.minValue.toDouble(), range.maxValue.toDouble()), values)
                }
                done(items)
            }, fail)
            VehicleFeature.HVAC_TEMPERATURE -> hvac().checkHvacTemperatureCapabilities(main3(fail) { config, bounds, temperatures ->
                val temperature = requireNotNull(temperatures) { "Temperature configuration unavailable" }
                done(bounds.map { (area, range) ->
                    val values = area.discoveredValues()
                    val setting = VehicleSetting(feature, discoveredAreaId(values, "SEAT_GLOBAL"))
                    seatAreas[setting] = area
                    DiscoveredSetting(setting, capability(config,
                        maxOf(range.minValue, temperature.minCelsius).toDouble(),
                        minOf(range.maxValue, temperature.maxCelsius).toDouble(),
                        temperature.incrementCelsius.toDouble()), values)
                })
            }, fail)
            VehicleFeature.SEAT_POSITION -> fail(UnsupportedOperationException("Seat movement disabled"))
        }
    }
}
