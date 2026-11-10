package dev.pleos.starter.teum.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleInterruptionTest {
    private val setting = VehicleSetting(VehicleFeature.HVAC_TEMPERATURE, "ROW_1_LEFT")
    private val parked = VehicleSafety(true, ParkingObservation(Parking.PARKED, 0),
        UxObservation(false, 0, 0), 1)

    private class Port : VehicleSettingsPort {
        var value = 22.0
        val sent = mutableListOf<Double>()
        var listener: (Double?, ChangeOrigin) -> Unit = { _, _ -> }
        var afterSend: () -> Unit = {}
        var failure: Exception? = null
        override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
            result(Result.success(SettingInspection(
                SettingCapability(true, true, 16.0, 30.0, 0.5, "Supported"), value)))
        }
        override fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable {
            listener = changed
            return AutoCloseable { listener = { _, _ -> } }
        }
        override fun write(setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit) {
            if (!permit()) {
                result(Result.failure(IllegalStateException("Permit revoked")))
                return
            }
            sent += value
            this.value = value
            afterSend()
            result(failure?.let { Result.failure(it) } ?: Result.success(Unit))
        }
        override fun close() = Unit
    }

    @Test fun safetyLossIsVisibleBeforeCancellationObserversCanStartAnotherCommand() {
        val port = Port()
        val events = mutableListOf<VehicleCommand>()
        lateinit var adapter: SafeVehicleAdapter
        adapter = SafeVehicleAdapter(port, { 0 }) {
            events += it
            if (it.stage == CommandStage.FAILED) {
                adapter.confirm(adapter.previewChange(setting, 25.0), true)
            }
        }
        adapter.updateSafety(parked)
        adapter.previewChange(setting, 24.0)

        adapter.updateSafety(parked.copy(parking = ParkingObservation(Parking.NOT_PARKED, 0, "Drive")))

        assertTrue(port.sent.isEmpty())
        assertEquals(CommandStage.MANUAL, events.last().stage)
        assertNotNull(adapter.blockedReason())
    }

    @Test fun normalCancellationCannotReenterWithFreshConsentDuringInvalidation() {
        val port = Port()
        lateinit var adapter: SafeVehicleAdapter
        adapter = SafeVehicleAdapter(port, { 0 }) {
            if (it.stage == CommandStage.FAILED) {
                adapter.confirm(adapter.previewChange(setting, 25.0), true)
            }
        }
        adapter.updateSafety(parked)
        adapter.previewChange(setting, 24.0)

        adapter.cancelPending()

        assertTrue(port.sent.isEmpty())
        assertNull(adapter.blockedReason())
    }

    @Test fun dispatchObserverLosingTrustOrChangingSettingRevokesFinalPermit() {
        for (interrupt in listOf("drive", "manual", "close")) {
            val port = Port()
            lateinit var adapter: SafeVehicleAdapter
            adapter = SafeVehicleAdapter(port, { 0 }) {
                if (it.stage == CommandStage.REQUESTED) {
                    when (interrupt) {
                        "drive" -> adapter.updateSafety(parked.copy(parking = ParkingObservation()))
                        "manual" -> port.listener(23.0, ChangeOrigin.MANUAL)
                        "close" -> adapter.close()
                    }
                }
            }
            adapter.updateSafety(parked)

            adapter.confirm(adapter.previewChange(setting, 24.0), true)

            assertTrue(interrupt, port.sent.isEmpty())
        }
    }

    @Test fun nativeCallbackTimeoutIsUnknownEvenWhenTheSettingMayHaveChanged() {
        val port = Port()
        val events = mutableListOf<VehicleCommand>()
        val adapter = SafeVehicleAdapter(port, { 0 }, events::add)
        adapter.updateSafety(parked)
        port.failure = VehicleCallbackTimeout()

        adapter.confirm(adapter.previewChange(setting, 24.0), true)

        assertEquals(listOf(24.0), port.sent)
        assertEquals(CommandStage.UNKNOWN, events.last().stage)
        assertFalse(events.last().requestAcknowledged)
        adapter.previewRestore(setting)
        assertEquals(CommandStage.MANUAL, events.last().stage)
    }

    @Test fun matchingReadbackAfterManualInterruptionDoesNotClaimAppApplication() {
        val port = Port()
        val events = mutableListOf<VehicleCommand>()
        val adapter = SafeVehicleAdapter(port, { 0 }, events::add)
        adapter.updateSafety(parked)
        port.afterSend = {
            port.listener(23.0, ChangeOrigin.MANUAL)
            port.listener(24.0, ChangeOrigin.UNKNOWN)
        }

        adapter.confirm(adapter.previewChange(setting, 24.0), true)

        assertEquals(listOf(24.0), port.sent)
        assertEquals(CommandStage.UNKNOWN, events.last().stage)
        adapter.previewRestore(setting)
        assertEquals(CommandStage.MANUAL, events.last().stage)
    }
}
