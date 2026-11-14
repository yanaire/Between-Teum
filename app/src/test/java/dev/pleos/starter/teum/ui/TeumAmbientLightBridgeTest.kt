package dev.pleos.starter.teum.ui

import dev.pleos.starter.teum.vehicle.ChangeOrigin
import dev.pleos.starter.teum.vehicle.DiscoveredAreaValue
import dev.pleos.starter.teum.vehicle.DiscoveredSetting
import dev.pleos.starter.teum.vehicle.VehicleSettingsDiscovery
import dev.pleos.starter.teum.vehicle.CommandStage
import dev.pleos.starter.teum.vehicle.Parking
import dev.pleos.starter.teum.vehicle.ParkingObservation
import dev.pleos.starter.teum.vehicle.SettingCapability
import dev.pleos.starter.teum.vehicle.SettingInspection
import dev.pleos.starter.teum.vehicle.UxObservation
import dev.pleos.starter.teum.vehicle.VehicleFeature
import dev.pleos.starter.teum.vehicle.VehicleSafety
import dev.pleos.starter.teum.vehicle.VehicleSetting
import dev.pleos.starter.teum.vehicle.VehicleSettingsPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeumAmbientLightBridgeTest {
    private val light = VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "ROW_1_LEFT")
    private val window = VehicleSetting(VehicleFeature.WINDOW_POSITION, "ROW_1_LEFT")
    private val lightFeature = WebVehicleFeature.AMBIENT_BRIGHTNESS
    private val windowFeature = WebVehicleFeature.WINDOW_POSITION

    private class FakePort : VehicleSettingsPort, VehicleSettingsDiscovery {
        val values = mutableMapOf<VehicleSetting, Double>()
        val capabilities = mutableMapOf<VehicleSetting, SettingCapability>()
        val readFailures = mutableMapOf<VehicleSetting, Exception>()
        val inspectCalls = mutableListOf<VehicleSetting>()
        val writes = mutableListOf<Pair<VehicleSetting, Double>>()
        val pendingReads = mutableListOf<(Result<SettingInspection>) -> Unit>()
        val pendingWrites = mutableListOf<(Result<Unit>) -> Unit>()
        var deferReads = false
        var deferWrites = false
        var applyWrite = true
        var closed = false

        init {
            values[VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "ROW_1_LEFT")] = 2.0
            values[VehicleSetting(VehicleFeature.WINDOW_POSITION, "ROW_1_LEFT")] = 0.0
            capabilities[VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "ROW_1_LEFT")] =
                SettingCapability(true, true, 0.0, 10.0, 1.0, "light test")
            capabilities[VehicleSetting(VehicleFeature.WINDOW_POSITION, "ROW_1_LEFT")] =
                SettingCapability(true, true, 0.0, 10.0, 1.0, "window test")
        }

        override fun discover(
            feature: VehicleFeature, result: (Result<List<DiscoveredSetting>>) -> Unit
        ) {
            result(Result.success(capabilities.filterKeys { it.feature == feature }.map { (setting, capability) ->
                DiscoveredSetting(setting, capability, listOf(DiscoveredAreaValue(setting.area, 1)))
            }))
        }

        override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
            inspectCalls += setting
            if (deferReads) {
                pendingReads += result
                return
            }
            completeRead(setting, result)
        }

        fun completeRead(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
            val failure = readFailures[setting]
            if (failure != null) result(Result.failure(failure))
            else result(Result.success(SettingInspection(
                requireNotNull(capabilities[setting]), requireNotNull(values[setting])
            )))
        }

        override fun subscribe(
            setting: VehicleSetting,
            changed: (Double?, ChangeOrigin) -> Unit
        ): AutoCloseable = AutoCloseable {}

        override fun write(
            setting: VehicleSetting,
            value: Double,
            permit: () -> Boolean,
            result: (Result<Unit>) -> Unit
        ) {
            if (!permit()) {
                result(Result.failure(IllegalStateException("cancelled")))
                return
            }
            writes += setting to value
            if (applyWrite) values[setting] = value
            if (deferWrites) pendingWrites += result else result(Result.success(Unit))
        }

        override fun close() {
            closed = true
        }
    }

    private class Fixture(initialSafety: VehicleSafety = parked()) {
        var now = 10_000L
        var epoch = 7L
        var foreground = true
        var changes = 0
        val port = FakePort()
        val bridge = TeumWebVehicleBridge(
            port, { now }, initialSafety, { epoch }, { foreground }, { changes++ }
        )

        fun safety(generation: Long = 1) = VehicleSafety(
            connected = true,
            parking = ParkingObservation(Parking.PARKED, now, "P"),
            ux = UxObservation(false, 0, now, "test"),
            generation = generation
        )

        companion object {
            private fun parked() = VehicleSafety(
                connected = true,
                parking = ParkingObservation(Parking.PARKED, 10_000, "P"),
                ux = UxObservation(false, 0, 10_000, "test"),
                generation = 1
            )
        }
    }

    @Test
    fun inspectionUsesReportedAmbientAreaAndRangeWithoutWriting() {
        val fixture = Fixture()

        fixture.bridge.inspect(lightFeature, fixture.epoch)

        val state = fixture.bridge.state(lightFeature)
        assertEquals(listOf(light), fixture.port.inspectCalls)
        assertEquals("ready", state.status)
        assertEquals("ROW_1_LEFT", state.area)
        assertEquals(0.0, requireNotNull(state.capability).min, 0.0)
        assertEquals(10.0, requireNotNull(state.capability).max, 0.0)
        assertEquals(2.0, state.currentValue!!, 0.0)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun permissionAndUnsupportedAreaFailuresAreShownWithoutSetterCalls() {
        val denied = Fixture().apply {
            port.readFailures[light] = SecurityException("Missing CONTROL_CAR_LIGHTS")
        }
        denied.bridge.inspect(lightFeature, denied.epoch)
        assertEquals("failed", denied.bridge.state(lightFeature).status)
        assertTrue(denied.bridge.state(lightFeature).error!!.contains("CONTROL_CAR_LIGHTS"))
        assertTrue(denied.port.writes.isEmpty())

        val unsupported = Fixture().apply {
            port.readFailures[window] = IllegalStateException("Window area unsupported: ROW_1_LEFT")
        }
        unsupported.bridge.inspect(windowFeature, unsupported.epoch)
        assertEquals("failed", unsupported.bridge.state(windowFeature).status)
        assertTrue(unsupported.bridge.state(windowFeature).error!!.contains("area unsupported"))
        assertTrue(unsupported.port.writes.isEmpty())
    }

    @Test
    fun reportedRangeRejectsUnsupportedBrightnessBeforePreviewOrWrite() {
        val fixture = Fixture()
        fixture.bridge.inspect(lightFeature, fixture.epoch)

        fixture.bridge.preview(lightFeature, 11.0, fixture.epoch)

        val state = fixture.bridge.state(lightFeature)
        assertEquals("failed", state.status)
        assertTrue(state.error!!.contains("0.0..10.0"))
        assertNull(state.command)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun missingWritePermissionLeavesTheObservedValueReadOnly() {
        val fixture = Fixture().apply {
            port.capabilities[light] = requireNotNull(port.capabilities[light]).copy(
                canWrite = false,
                reason = "Missing CONTROL_CAR_LIGHTS"
            )
        }

        fixture.bridge.inspect(lightFeature, fixture.epoch)
        fixture.bridge.preview(lightFeature, 5.0, fixture.epoch)

        val state = fixture.bridge.state(lightFeature)
        assertEquals("read_only", state.status)
        assertTrue(state.error!!.contains("CONTROL_CAR_LIGHTS"))
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun applyRequiresPreviewAndMatchingReadbackBeforeVerifiedState() {
        val fixture = Fixture()
        fixture.bridge.inspect(lightFeature, fixture.epoch)
        fixture.bridge.preview(lightFeature, 5.0, fixture.epoch)

        val preview = requireNotNull(fixture.bridge.state(lightFeature).command)
        assertEquals(CommandStage.PREVIEW, preview.stage)
        assertTrue(fixture.port.writes.isEmpty())
        fixture.bridge.confirm(preview.id + 1, fixture.epoch)
        assertTrue(fixture.port.writes.isEmpty())

        fixture.bridge.confirm(preview.id, fixture.epoch)

        val applied = fixture.bridge.state(lightFeature)
        assertEquals(CommandStage.READBACK, applied.command?.stage)
        assertEquals("verified", applied.status)
        assertTrue(applied.command!!.requestAcknowledged)
        assertEquals(5.0, applied.command!!.readback!!, 0.0)
        assertEquals(listOf(light to 5.0), fixture.port.writes)
    }

    @Test
    fun acknowledgedWindowSetWithDifferentReadbackRemainsUnknown() {
        val fixture = Fixture().apply { port.applyWrite = false }
        fixture.bridge.inspect(windowFeature, fixture.epoch)
        fixture.bridge.preview(windowFeature, 1.0, fixture.epoch)
        val preview = requireNotNull(fixture.bridge.state(windowFeature).command)

        fixture.bridge.confirm(preview.id, fixture.epoch)
        assertEquals("acknowledged", fixture.bridge.state(windowFeature).status)
        repeat(6) {
            fixture.now += 1_000
            fixture.bridge.updateSafety(fixture.safety())
            fixture.bridge.tick()
        }

        val result = fixture.bridge.state(windowFeature)
        assertEquals(listOf(window to 1.0), fixture.port.writes)
        assertEquals("mismatch", result.status)
        assertEquals(CommandStage.UNKNOWN, result.command?.stage)
        assertTrue(result.command!!.requestAcknowledged)
        assertEquals(0.0, result.command!!.readback!!, 0.0)
        assertEquals(0.0, result.currentValue!!, 0.0)
        assertFalse(result.status == "verified")
    }

    @Test
    fun safetyLossAfterPreviewPreventsTheSetter() {
        val fixture = Fixture()
        fixture.bridge.inspect(lightFeature, fixture.epoch)
        fixture.bridge.preview(lightFeature, 4.0, fixture.epoch)
        val preview = requireNotNull(fixture.bridge.state(lightFeature).command)
        fixture.bridge.updateSafety(fixture.safety().copy(
            parking = ParkingObservation(Parking.NOT_PARKED, fixture.now, "D")
        ))

        fixture.bridge.confirm(preview.id, fixture.epoch)

        assertTrue(fixture.port.writes.isEmpty())
        assertEquals("blocked", fixture.bridge.state(lightFeature).status)
        assertEquals("D", fixture.bridge.state(lightFeature).safetyReason)
    }

    @Test
    fun oldEpochInspectionCannotPublishLateCapabilities() {
        val fixture = Fixture().apply { port.deferReads = true }
        fixture.bridge.inspect(lightFeature, fixture.epoch)
        val pending = fixture.port.pendingReads.single()
        fixture.epoch++

        fixture.port.completeRead(light, pending)

        val state = fixture.bridge.state(lightFeature)
        assertEquals("checking", state.status)
        assertNull(state.capability)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun ambientGlobalAreaIsReadInsteadOfInventedDriverArea() {
        val fixture = Fixture()
        val global = VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "SEAT_GLOBAL")
        fixture.port.capabilities[global] = fixture.port.capabilities.remove(light)!!
        fixture.port.values[global] = 7.0
        fixture.port.values.remove(light)

        fixture.bridge.inspect(lightFeature, fixture.epoch)

        assertEquals(listOf(global), fixture.port.inspectCalls)
        assertEquals("SEAT_GLOBAL", fixture.bridge.state(lightFeature).area)
        assertEquals(7.0, fixture.bridge.state(lightFeature).currentValue!!, 0.0)
    }

    @Test
    fun invalidSdkValueRemainsVisibleWithoutEnablingAWrite() {
        val fixture = Fixture()
        fixture.port.values[light] = 99.0

        fixture.bridge.inspect(lightFeature, fixture.epoch)
        fixture.bridge.preview(lightFeature, 5.0, fixture.epoch)

        assertEquals(99.0, fixture.bridge.state(lightFeature).currentValue!!, 0.0)
        assertTrue(fixture.port.writes.isEmpty())
        assertTrue(fixture.bridge.state(lightFeature).status != "verified")
        assertTrue(fixture.bridge.state(lightFeature).command?.stage != CommandStage.PREVIEW)
    }

    @Test
    fun unsupportedAreaSelectionNeverReadsOrWritesThatArea() {
        val fixture = Fixture()

        fixture.bridge.inspect(lightFeature, fixture.epoch, "ROW_3_LEFT")

        assertTrue(fixture.port.inspectCalls.isEmpty())
        assertTrue(fixture.port.writes.isEmpty())
        assertEquals("unavailable", fixture.bridge.state(lightFeature).status)
    }

    @Test
    fun previewCannotReplaceAnUnresolvedNativeWrite() {
        val fixture = Fixture().apply { port.deferWrites = true }
        fixture.bridge.inspect(windowFeature, fixture.epoch)
        fixture.bridge.preview(windowFeature, 1.0, fixture.epoch)
        val first = fixture.bridge.state(windowFeature).command!!
        fixture.bridge.confirm(first.id, fixture.epoch)

        fixture.bridge.preview(windowFeature, 2.0, fixture.epoch)

        assertEquals(first.id, fixture.bridge.state(windowFeature).command!!.id)
        assertEquals(listOf(window to 1.0), fixture.port.writes)
        assertEquals(CommandStage.REQUESTED, fixture.bridge.state(windowFeature).command!!.stage)
    }

    @Test
    fun secondConfirmationShowsFreshValueInsteadOfRestoreBaseline() {
        val fixture = Fixture()
        fixture.bridge.inspect(lightFeature, fixture.epoch)
        fixture.bridge.preview(lightFeature, 5.0, fixture.epoch)
        fixture.bridge.confirm(fixture.bridge.state(lightFeature).command!!.id, fixture.epoch)

        fixture.bridge.preview(lightFeature, 8.0, fixture.epoch)

        val second = fixture.bridge.state(lightFeature).command!!
        assertEquals(2.0, second.initial!!, 0.0)
        assertEquals(5.0, second.previewValue!!, 0.0)
        assertEquals(8.0, second.target, 0.0)
    }

    @Test
    fun documentReloadDuringConfirmedPreflightNeverSendsSetter() {
        val fixture = Fixture()
        fixture.bridge.inspect(windowFeature, fixture.epoch)
        fixture.bridge.preview(windowFeature, 1.0, fixture.epoch)
        val preview = requireNotNull(fixture.bridge.state(windowFeature).command)
        fixture.port.deferReads = true
        fixture.bridge.confirm(preview.id, fixture.epoch)
        val preflight = fixture.port.pendingReads.single()

        fixture.epoch++
        fixture.port.completeRead(window, preflight)

        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun backgroundDuringConfirmedPreflightNeverSendsSetter() {
        val fixture = Fixture()
        fixture.bridge.inspect(lightFeature, fixture.epoch)
        fixture.bridge.preview(lightFeature, 4.0, fixture.epoch)
        val preview = requireNotNull(fixture.bridge.state(lightFeature).command)
        fixture.port.deferReads = true
        fixture.bridge.confirm(preview.id, fixture.epoch)
        val preflight = fixture.port.pendingReads.single()

        fixture.foreground = false
        fixture.port.completeRead(light, preflight)

        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun closeDiscardsUnresolvedSetterAndIgnoresLateAcknowledgment() {
        val fixture = Fixture().apply { port.deferWrites = true }
        fixture.bridge.inspect(lightFeature, fixture.epoch)
        fixture.bridge.preview(lightFeature, 4.0, fixture.epoch)
        val preview = requireNotNull(fixture.bridge.state(lightFeature).command)
        fixture.bridge.confirm(preview.id, fixture.epoch)
        val lateAck = fixture.port.pendingWrites.single()
        val changesBeforeClose = fixture.changes

        fixture.bridge.close()
        lateAck(Result.success(Unit))

        val state = fixture.bridge.state(lightFeature)
        assertTrue(fixture.port.closed)
        assertEquals(changesBeforeClose, fixture.changes)
        assertEquals(listOf(light to 4.0), fixture.port.writes)
        assertTrue(state.command?.requestAcknowledged != true)
        assertFalse(state.status == "verified")
    }
}
