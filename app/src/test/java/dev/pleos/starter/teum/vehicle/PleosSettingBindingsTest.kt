package dev.pleos.starter.teum.vehicle

import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.SmartGlassLevelListener
import ai.pleos.playground.vehicle.listener.SmartGlassStateListener
import ai.pleos.playground.vehicle.listener.VehicleListener
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** IPC boundary fixtures, using the installed SDK interfaces and real area/value classes. */
@Suppress("UNCHECKED_CAST")
class PleosSettingBindingsTest {
    private val readWrite = CapabilityConfig(AccessType.READ_WRITE)

    private fun <T : Any> api(type: Class<T>, invoke: (String, Array<out Any?>) -> Unit): T =
        requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            invoke(method.name, arguments ?: emptyArray())
            null
        }))

    private fun catalog(
        light: Light = api(Light::class.java) { name, _ -> error("Unexpected Light.$name") },
        seat: Seat = api(Seat::class.java) { name, _ -> error("Unexpected Seat.$name") },
        window: Window = api(Window::class.java) { name, _ -> error("Unexpected Window.$name") },
        writable: Boolean = true,
        dispatch: (() -> Unit) -> Unit = { it() }
    ) = PleosSettingBindings(
        { api(HVAC::class.java) { name, _ -> error("Unexpected HVAC.$name") } },
        { light }, { seat }, { window }, { writable }, dispatch
    )

    private fun discover(catalog: PleosSettingBindings, feature: VehicleFeature): List<DiscoveredSetting> {
        var response: List<DiscoveredSetting>? = null
        catalog.discover(feature, { response = it }, { throw it })
        return requireNotNull(response)
    }

    @Test fun brightnessUsesReturnedCombinedAreaAndVehicleBoundsForReadAndWrite() {
        val area = SeatArea(SeatValue.ROW_2_RIGHT, SeatValue.ROW_2_LEFT)
        var physicalValue = 13
        val light = api(Light::class.java) { name, args ->
            when (name) {
                "checkAmbientLightBrightnessCapabilities" ->
                    (args[0] as (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit)(
                        readWrite, mapOf(area to AreaBounds(7, 29)))
                "getAmbientLightBrightness" -> {
                    assertSame(area, args[0])
                    (args[1] as (Int?) -> Unit)(physicalValue)
                }
                "setAmbientLightBrightness" -> {
                    assertSame(area, args[0])
                    physicalValue = args[1] as Int
                    (args[2] as (SeatArea, Int) -> Unit)(area, physicalValue)
                }
                else -> error(name)
            }
        }
        val catalog = catalog(light = light)
        val metadata = discover(catalog, VehicleFeature.AMBIENT_BRIGHTNESS).single()
        val binding = catalog.bindings.getValue(metadata.setting)

        binding.write(19.0, {}, { throw it })
        var actual: Double? = null
        binding.read({ actual = it }, { throw it })

        assertEquals("ROW_2_RIGHT+ROW_2_LEFT", metadata.setting.area)
        assertEquals(listOf(
            DiscoveredAreaValue("ROW_2_RIGHT", SeatValue.ROW_2_RIGHT.id),
            DiscoveredAreaValue("ROW_2_LEFT", SeatValue.ROW_2_LEFT.id)), metadata.areaValues)
        assertEquals(7.0, metadata.capability.min, 0.0)
        assertEquals(29.0, metadata.capability.max, 0.0)
        assertEquals(19.0, actual)
    }

    @Test fun emptyReturnedSeatAreaRemainsGlobalWithoutGuessingASeat() {
        val area = SeatArea()
        val catalog = catalog(light = api(Light::class.java) { _, args ->
            (args[0] as (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit)(
                readWrite, mapOf(area to AreaBounds(3, 8)))
        })

        val metadata = discover(catalog, VehicleFeature.AMBIENT_BRIGHTNESS).single()

        assertEquals("SEAT_GLOBAL", metadata.setting.area)
        assertEquals(emptyList<DiscoveredAreaValue>(), metadata.areaValues)
        assertSame(area, catalog.seatAreas[metadata.setting])
        assertFalse(catalog.bindings.containsKey(VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "ROW_1_LEFT")))
    }

    @Test fun switchUsesSparseSdkCodesAndRejectsUnsupportedIntermediateValues() {
        val area = SeatArea(SeatValue.ROW_1_RIGHT)
        var command: LightSwitch? = null
        val catalog = catalog(light = api(Light::class.java) { name, args ->
            when (name) {
                "checkAmbientLightSwitchCapabilities" ->
                    (args[0] as (CapabilityConfig, Map<SeatArea, List<LightSwitch>>) -> Unit)(
                        readWrite, mapOf(area to listOf(LightSwitch.OFF, LightSwitch.AUTOMATIC)))
                "setAmbientLightSwitch" -> {
                    command = args[1] as LightSwitch
                    (args[2] as (SeatArea, LightSwitch?) -> Unit)(area, command)
                }
                else -> error(name)
            }
        })
        val metadata = discover(catalog, VehicleFeature.AMBIENT_SWITCH).single()

        catalog.bindings.getValue(metadata.setting).write(256.0, {}, { throw it })

        assertEquals(LightSwitch.AUTOMATIC, command)
        assertEquals(listOf(0.0, 256.0), metadata.capability.allowedValues)
        assertEquals(mapOf(0.0 to "OFF", 256.0 to "AUTOMATIC"), metadata.capability.valueLabels)
        assertFalse(metadata.capability.accepts(1.0))
        assertFalse(metadata.capability.accepts(3.0))
        assertEquals("POLL_GETTER", metadata.observation)
    }

    @Test fun smartGlassGlobalGetterIsIndependentOfSetterAcknowledgement() {
        var physicalState = false
        var acknowledge: ((Boolean?) -> Unit)? = null
        val catalog = catalog(window = api(Window::class.java) { name, args ->
            when (name) {
                "checkSmartGlassStateCapabilities" ->
                    (args[0] as (CapabilityConfig) -> Unit)(readWrite)
                "setSmartGlassState" -> {
                    assertEquals(true, args[0])
                    acknowledge = args[1] as (Boolean?) -> Unit
                }
                "getCurrentSmartGlassState" ->
                    (args[0] as (Boolean?) -> Unit)(physicalState)
                else -> error(name)
            }
        })
        val metadata = discover(catalog, VehicleFeature.SMART_GLASS_STATE).single()
        val binding = catalog.bindings.getValue(metadata.setting)
        var acknowledged = false
        binding.write(1.0, { acknowledged = true }, { throw it })
        assertFalse(acknowledged)

        requireNotNull(acknowledge)(true)
        var readback: Double? = null
        binding.read({ readback = it }, { throw it })

        assertTrue(acknowledged)
        assertEquals("GLOBAL", metadata.setting.area)
        assertEquals(emptyList<DiscoveredAreaValue>(), metadata.areaValues)
        assertEquals(listOf(0.0, 1.0), metadata.capability.allowedValues)
        assertEquals(0.0, readback)
        physicalState = true
        binding.read({ readback = it }, { throw it })
        assertEquals(1.0, readback)
    }

    @Test fun ventilationAndGlassLevelsUseReturnedBoundsAndExactAreas() {
        val seatArea = SeatArea(SeatValue.ROW_2_CENTER)
        val windowArea = WindowArea(WindowValue.ROOF_TOP_2, WindowValue.ROOF_TOP_1)
        val catalog = catalog(
            seat = api(Seat::class.java) { name, args ->
                when (name) {
                    "checkSeatVentilationCapabilities" ->
                        (args[0] as (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit)(
                            readWrite, mapOf(seatArea to AreaBounds(2, 7)))
                    "setHvacSeatVentilation" -> {
                        assertSame(seatArea, args[0])
                        assertEquals(5, args[1])
                        (args[2] as (SeatArea, Int?) -> Unit)(seatArea, 5)
                    }
                    else -> error(name)
                }
            },
            window = api(Window::class.java) { name, args ->
                when (name) {
                    "checkSmartGlassLevelCapabilities" ->
                        (args[0] as (CapabilityConfig, Map<WindowArea, AreaBounds<Int>>) -> Unit)(
                            readWrite, mapOf(windowArea to AreaBounds(4, 12)))
                    "setSmartGlassLevel" -> {
                        assertSame(windowArea, args[0])
                        assertEquals(9, args[1])
                        (args[2] as (WindowArea, Int?) -> Unit)(windowArea, 9)
                    }
                    else -> error(name)
                }
            })
        val ventilation = discover(catalog, VehicleFeature.SEAT_VENTILATION).single()
        val glass = discover(catalog, VehicleFeature.SMART_GLASS_LEVEL).single()

        catalog.bindings.getValue(ventilation.setting).write(5.0, {}, { throw it })
        catalog.bindings.getValue(glass.setting).write(9.0, {}, { throw it })

        assertEquals(2.0, ventilation.capability.min, 0.0)
        assertEquals(7.0, ventilation.capability.max, 0.0)
        assertFalse(ventilation.capability.accepts(0.0))
        assertEquals(4.0, glass.capability.min, 0.0)
        assertEquals(12.0, glass.capability.max, 0.0)
        assertEquals("ROOF_TOP_2+ROOF_TOP_1", glass.setting.area)
    }

    @Test fun colorPreservesBothOpaqueAndObservedZeroAlphaSignedIntegers() {
        val area = SeatArea()
        val catalog = catalog(light = api(Light::class.java) { _, args ->
            (args[0] as (CapabilityConfig, List<SeatArea>) -> Unit)(readWrite, listOf(area))
        })

        val capability = discover(catalog, VehicleFeature.AMBIENT_COLOR).single().capability

        assertTrue(capability.accepts(-16777216.0))
        assertTrue(capability.accepts(-1.0))
        assertTrue(capability.accepts(16777215.0))
        assertTrue(capability.accepts(0.0))
        assertFalse(capability.accepts(Int.MIN_VALUE.toDouble() - 1))
        assertFalse(capability.accepts(Int.MAX_VALUE.toDouble() + 1))
        assertFalse(capability.accepts(4294967295.0))
        assertFalse(capability.accepts(-2.5))
    }

    @Test fun deniedWritePermissionAndReadOnlyAccessNeverAdvertiseControl() {
        for ((access, permission) in listOf(AccessType.READ to true, AccessType.READ_WRITE to false)) {
            val catalog = catalog(writable = permission, light = api(Light::class.java) { _, args ->
                (args[0] as (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit)(
                    CapabilityConfig(access), mapOf(SeatArea() to AreaBounds(1, 10)))
            })

            val metadata = discover(catalog, VehicleFeature.AMBIENT_BRIGHTNESS).single()

            assertTrue(metadata.capability.canRead)
            assertFalse(metadata.capability.canWrite)
        }
    }

    @Test fun capabilityPublicationWaitsForMainDispatch() {
        val queued = mutableListOf<() -> Unit>()
        val catalog = catalog(dispatch = { queued.add(it) }, light = api(Light::class.java) { _, args ->
            (args[0] as (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit)(
                readWrite, mapOf(SeatArea() to AreaBounds(1, 10)))
        })
        var delivered = false

        catalog.discover(VehicleFeature.AMBIENT_BRIGHTNESS, { delivered = true }, { throw it })

        assertFalse(delivered)
        assertTrue(catalog.bindings.isEmpty())
        queued.single().invoke()
        assertTrue(delivered)
        assertEquals(setOf(VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "SEAT_GLOBAL")), catalog.bindings.keys)
    }

    @Test fun nativeObservationForwardsActualUpdatesAndUnregistersSameAreaAndListener() {
        for (feature in listOf(VehicleFeature.AMBIENT_BRIGHTNESS, VehicleFeature.AMBIENT_COLOR,
            VehicleFeature.SEAT_VENTILATION, VehicleFeature.SMART_GLASS_LEVEL, VehicleFeature.SMART_GLASS_STATE)) {
            val seatArea = SeatArea(SeatValue.ROW_2_RIGHT, SeatValue.ROW_2_LEFT)
            val windowArea = WindowArea(WindowValue.ROOF_TOP_2)
            var registered: VehicleListener? = null
            var detached = false
            val invoke: (String, Array<out Any?>) -> Unit = { name, args ->
                when {
                    name == "checkSmartGlassStateCapabilities" ->
                        (args[0] as (CapabilityConfig) -> Unit)(readWrite)
                    name == "checkAmbientLightBrightnessColorCapabilities" ->
                        (args[0] as (CapabilityConfig, List<SeatArea>) -> Unit)(readWrite, listOf(seatArea))
                    name == "checkSmartGlassLevelCapabilities" ->
                        (args[0] as (CapabilityConfig, Map<WindowArea, AreaBounds<Int>>) -> Unit)(
                            readWrite, mapOf(windowArea to AreaBounds(0, 20)))
                    name.startsWith("check") ->
                        (args[0] as (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit)(
                            readWrite, mapOf(seatArea to AreaBounds(0, 20)))
                    name.startsWith("register") -> {
                        if (feature != VehicleFeature.SMART_GLASS_STATE)
                            assertSame(if (feature == VehicleFeature.SMART_GLASS_LEVEL) windowArea else seatArea, args[0])
                        registered = args.last() as VehicleListener
                    }
                    name.startsWith("unregister") -> {
                        if (feature != VehicleFeature.SMART_GLASS_STATE)
                            assertSame(if (feature == VehicleFeature.SMART_GLASS_LEVEL) windowArea else seatArea, args[0])
                        assertSame(registered, args.last())
                        detached = true
                    }
                    else -> error(name)
                }
            }
            val catalog = catalog(light = api(Light::class.java, invoke),
                seat = api(Seat::class.java, invoke), window = api(Window::class.java, invoke))
            val metadata = discover(catalog, feature).single()
            val updates = mutableListOf<Double?>()
            var observedFailure: Exception? = null

            val subscription = catalog.observe(metadata.setting, { updates.add(it) }, { observedFailure = it })
            val listener = requireNotNull(registered)
            when (feature) {
                VehicleFeature.AMBIENT_BRIGHTNESS -> (listener as AmbientLightBrightnessListener).onBrightnessUpdated(17)
                VehicleFeature.AMBIENT_COLOR -> (listener as AmbientLightBrightnessColorListener).onBrightnessColorUpdated(-12345)
                VehicleFeature.SEAT_VENTILATION -> (listener as HvacSeatVentilationListener).onHvacSeatVentilationUpdated(6)
                VehicleFeature.SMART_GLASS_LEVEL -> (listener as SmartGlassLevelListener).onSmartGlassLevelUpdated(8)
                VehicleFeature.SMART_GLASS_STATE -> (listener as SmartGlassStateListener).onSmartGlassStateUpdated(true)
                else -> error("Fixture feature")
            }
            val failure = IllegalStateException("Native listener failed")
            listener.onFailed(failure)
            subscription.close()

            val expected = when (feature) {
                VehicleFeature.AMBIENT_BRIGHTNESS -> 17.0
                VehicleFeature.AMBIENT_COLOR -> -12345.0
                VehicleFeature.SEAT_VENTILATION -> 6.0
                VehicleFeature.SMART_GLASS_LEVEL -> 8.0
                VehicleFeature.SMART_GLASS_STATE -> 1.0
                else -> error("Fixture feature")
            }
            assertEquals(listOf(expected), updates)
            assertSame(failure, observedFailure)
            assertTrue(detached)
            assertEquals("NATIVE_LISTENER", metadata.observation)
        }
    }

    @Test fun combinedAndSingleAreaSettersRemainExcludedUntilNativeSettlement() {
        val lifecycle = PortLifecycle()
        var released = false
        lifecycle.onRelease = { released = true }
        val combined = VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "ROW_1_LEFT+ROW_1_RIGHT")
        val single = VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "ROW_1_RIGHT")
        val color = VehicleSetting(VehicleFeature.AMBIENT_COLOR, "ROW_1_LEFT")
        val native = requireNotNull(NativeSetterExclusion.tryBegin(combined, lifecycle))
        try {
            lifecycle.close()

            assertNull(NativeSetterExclusion.tryBegin(single, PortLifecycle()))
            assertNull(NativeSetterExclusion.tryBegin(color, PortLifecycle()))
            assertFalse(released)
        } finally {
            NativeSetterExclusion.settle(combined, native)
        }
        assertTrue(released)
        val next = requireNotNull(NativeSetterExclusion.tryBegin(single, PortLifecycle()))
        NativeSetterExclusion.settle(single, next)
    }
}
