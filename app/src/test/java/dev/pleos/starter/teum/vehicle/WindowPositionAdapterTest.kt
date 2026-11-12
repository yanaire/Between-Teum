package dev.pleos.starter.teum.vehicle

import org.junit.Assert.*
import org.junit.Test

/**
 * WINDOW_POSITION behavior through the existing safety adapter.
 *
 * The real [PleosSettingsPort] window path (capability/bounds/get/set/listeners
 * against Vehicle 2.3.2) needs the emulator service and is not exercised here.
 * These tests pin the adapter contract the port plugs into: an SDK ack is never
 * success by itself; only an independent readback that matches the target is.
 */
class WindowPositionAdapterTest {
    private val window = VehicleSetting(VehicleFeature.WINDOW_POSITION, "ROW_1_LEFT")
    private val seat = VehicleSetting(VehicleFeature.SEAT_POSITION, "ROW_1_LEFT")

    private class Port : VehicleSettingsPort {
        var value = 0.0
        var capability = SettingCapability(true, true, 0.0, 10.0, 1.0, "window 0..10")
        var applyWrite = true
        val sent = mutableListOf<Double>()
        override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
            result(Result.success(SettingInspection(capability, value)))
        }
        override fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable =
            AutoCloseable { }
        override fun write(setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit) {
            if (!permit()) result(Result.failure(IllegalStateException("cancelled")))
            else {
                sent += value
                if (applyWrite) this.value = value
                result(Result.success(Unit))
            }
        }
        override fun close() { }
    }

    private class Fixture {
        var now = 0L
        val port = Port()
        val events = mutableListOf<VehicleCommand>()
        val adapter = SafeVehicleAdapter(port, { now }, events::add)
        init {
            adapter.updateSafety(VehicleSafety(true,
                ParkingObservation(Parking.PARKED, now, "P"),
                UxObservation(false, 0, now), 1))
        }
        fun latest() = events.last()
    }

    @Test fun windowPositionUsesIntegerGridOverReportedBounds() {
        val capability = SettingCapability(true, true, 0.0, 10.0, 1.0, "window 0..10")
        assertTrue(capability.accepts(1.0))
        assertTrue(capability.accepts(0.0))
        assertTrue(capability.accepts(10.0))
        assertFalse(capability.accepts(0.5))
        assertFalse(capability.accepts(11.0))
        assertFalse(capability.accepts(Double.NaN))
    }

    @Test fun acknowledgedSetWithoutMatchingReadbackIsUnknownNotSuccess() {
        // Sample experiment: range 0..10, set 1 acknowledged, independent get stayed 0.
        val f = Fixture()
        f.port.applyWrite = false
        f.adapter.confirm(f.adapter.previewChange(window, 1.0), true)
        assertTrue(f.latest().requestAcknowledged)
        assertEquals(CommandStage.UNKNOWN, f.latest().stage)
        assertEquals(0.0, f.latest().readback!!, 0.0)
        assertEquals(listOf(1.0), f.port.sent)
    }

    @Test fun mismatchedReadbackLeavesNoVerifiedRestore() {
        val f = Fixture()
        f.port.applyWrite = false
        f.adapter.confirm(f.adapter.previewChange(window, 1.0), true)
        f.adapter.previewRestore(window)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
        assertTrue(f.port.sent.size == 1)
    }

    @Test fun matchingIndependentReadbackConfirmsWindowChange() {
        val f = Fixture()
        f.adapter.confirm(f.adapter.previewChange(window, 1.0), true)
        assertEquals(CommandStage.READBACK, f.latest().stage)
        assertEquals(1.0, f.latest().readback!!, 0.0)
        assertTrue(f.latest().requestAcknowledged)
    }

    @Test fun windowPreviewFlowsWhileSeatStaysDisabled() {
        val f = Fixture()
        val id = f.adapter.previewChange(window, 1.0)
        assertEquals(CommandStage.PREVIEW, f.latest().stage)
        assertEquals(0.0, f.latest().initial!!, 0.0)
        f.adapter.confirm(id, false)
        assertEquals(CommandStage.FAILED, f.latest().stage)
        assertTrue(f.port.sent.isEmpty())

        f.adapter.previewChange(seat, 1.0)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
        assertTrue(f.port.sent.isEmpty())
    }

    @Test fun fractionalWindowTargetIsRejectedWithoutSending() {
        val f = Fixture()
        f.adapter.confirm(f.adapter.previewChange(window, 1.5), true)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
        assertTrue(f.port.sent.isEmpty())
    }
}
