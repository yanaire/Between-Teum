package dev.pleos.starter.teum.vehicle

import org.junit.Assert.*
import org.junit.Test

class SafeVehicleAdapterTest {
    private val hvac = VehicleSetting(VehicleFeature.HVAC_TEMPERATURE, "ROW_1_LEFT")
    private val light = VehicleSetting(VehicleFeature.AMBIENT_BRIGHTNESS, "ROW_1_LEFT")
    private class Port : VehicleSettingsPort {
        var value = 22.0
        var capability = SettingCapability(true, true, 16.0, 30.0, 0.5, "test vehicle")
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var applyWrite = true
        var delayWrite = false
        var delayRead = false
        var acknowledge = true
        var closed = false
        val sent = mutableListOf<Double>()
        val writes = mutableListOf<() -> Unit>()
        val reads = mutableListOf<() -> Unit>()
        val listeners = mutableMapOf<VehicleSetting, (Double?, ChangeOrigin) -> Unit>()
        override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
            val read = {
                val error = readFailure
                result(if (error != null) Result.failure(error) else Result.success(SettingInspection(capability, value)))
            }
            if (delayRead) reads += read else read()
        }
        override fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable {
            listeners[setting] = changed
            return AutoCloseable { listeners.remove(setting) }
        }
        override fun write(setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit) {
            val write = {
                if (!permit()) result(Result.failure(IllegalStateException("cancelled")))
                else {
                    sent += value
                    val error = writeFailure
                    if (error != null) result(Result.failure(error))
                    else {
                        if (applyWrite) this.value = value
                        if (acknowledge) result(Result.success(Unit))
                    }
                }
            }
            if (delayWrite) writes += write else write()
        }
        fun external(setting: VehicleSetting, value: Double?, origin: ChangeOrigin) {
            if (value != null) this.value = value
            listeners[setting]?.invoke(value, origin)
        }
        override fun close() { closed = true }
    }
    private class Fixture {
        var now = 0L
        val port = Port()
        val events = mutableListOf<VehicleCommand>()
        val adapter = SafeVehicleAdapter(port, { now }, events::add)
        init { adapter.updateSafety(parked()) }
        fun parked(generation: Long = 1) = VehicleSafety(true,
            ParkingObservation(Parking.PARKED, now, "P"),
            UxObservation(false, 0, now), generation)
        fun latest() = events.last()
        fun apply(setting: VehicleSetting, value: Double) {
            adapter.confirm(adapter.previewChange(setting, value), true)
        }
    }

    @Test fun safetyRequiresIndependentFreshParkingAndUnrestrictedUx() {
        val good = VehicleSafety(true, ParkingObservation(Parking.PARKED, 100),
            UxObservation(false, 0, 100), 1)
        val blocked = listOf(
            good.copy(connected = false),
            good.copy(parking = ParkingObservation()),
            good.copy(parking = ParkingObservation(Parking.NOT_PARKED, 100, "D at zero speed")),
            good.copy(ux = UxObservation()),
            good.copy(ux = UxObservation(true, 0, 100)),
            good.copy(ux = UxObservation(false, 1, 100)),
            good.copy(parking = ParkingObservation(Parking.PARKED, 101)),
            good.copy(ux = UxObservation(false, 0, 101))
        )
        blocked.forEach { assertNotNull(it.toString(), it.blockedReason(100)) }
        assertNull(good.blockedReason(3_100))
        assertEquals("Parking observation stale", good.blockedReason(3_101))
    }

    @Test fun denialUnsupportedRangeAndSeatReturnManualWithoutSending() {
        val cases = listOf(
            SettingCapability(false, true, 16.0, 30.0, .5, "read denied") to 24.0,
            SettingCapability(true, false, 16.0, 30.0, .5, "write denied") to 24.0,
            SettingCapability(true, true, 16.0, 30.0, .5, "range") to 31.0,
            SettingCapability(true, true, 16.0, 30.0, .5, "step") to 24.1,
            SettingCapability(true, true, 16.0, 30.0, .5, "nan") to Double.NaN
        )
        cases.forEach { (capability, value) ->
            val f = Fixture()
            f.port.capability = capability
            f.apply(hvac, value)
            assertEquals(CommandStage.MANUAL, f.latest().stage)
            assertTrue(f.port.sent.isEmpty())
        }
        val f = Fixture()
        f.apply(VehicleSetting(VehicleFeature.SEAT_POSITION, "ROW_1_LEFT"), 24.0)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
        assertTrue(f.port.sent.isEmpty())
    }

    @Test fun actualReadDenialNeverProducesPreviewOrSuccess() {
        val f = Fixture()
        f.port.readFailure = SecurityException("CAR_CLIMATE denied")
        f.apply(hvac, 24.0)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
        assertTrue(f.latest().reason.contains("CAR_CLIMATE denied"))
        assertTrue(f.port.sent.isEmpty())
    }

    @Test fun previewCapturesInitialAndRequiresExplicitSingleUseConsent() {
        val f = Fixture()
        val id = f.adapter.previewChange(hvac, 24.0)
        assertEquals(22.0, f.latest().initial!!, 0.0)
        assertEquals(CommandStage.PREVIEW, f.latest().stage)
        assertTrue(f.port.sent.isEmpty())
        f.adapter.confirm(id, false)
        f.adapter.confirm(id, true)
        assertEquals(CommandStage.FAILED, f.latest().stage)
        assertTrue(f.port.sent.isEmpty())
    }

    @Test fun requestedAcknowledgedAndReadbackAreDifferentEvents() {
        val f = Fixture()
        f.apply(hvac, 24.0)
        assertEquals(listOf(CommandStage.PREVIEW, CommandStage.QUEUED, CommandStage.REQUESTED,
            CommandStage.REQUESTED, CommandStage.READBACK), f.events.map { it.stage })
        assertFalse(f.events[2].requestAcknowledged)
        assertTrue(f.events[3].requestAcknowledged)
        assertNull(f.events[3].readback)
        assertEquals(24.0, f.latest().readback!!, 0.0)
        assertEquals(listOf(24.0), f.port.sent)
    }

    @Test fun acknowledgedWithoutMatchingReadbackIsUnknown() {
        val f = Fixture()
        f.port.applyWrite = false
        f.apply(hvac, 24.0)
        assertTrue(f.latest().requestAcknowledged)
        assertEquals(CommandStage.UNKNOWN, f.latest().stage)
        assertEquals(22.0, f.latest().readback!!, 0.0)
        f.adapter.previewRestore(hvac)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
    }

    @Test fun setterDenialIsFailedAndNoRestoreIsAvailable() {
        val f = Fixture()
        f.port.writeFailure = SecurityException("CONTROL_CAR_CLIMATE")
        f.apply(hvac, 24.0)
        assertEquals(CommandStage.FAILED, f.latest().stage)
        assertFalse(f.latest().requestAcknowledged)
        f.adapter.previewRestore(hvac)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
    }

    @Test fun onlyConfirmedRestoreReturnsToOriginalSnapshotAfterMultipleChanges() {
        val f = Fixture()
        f.apply(hvac, 24.0)
        f.apply(hvac, 25.0)
        f.adapter.cancelPending()
        val rejected = f.adapter.previewRestore(hvac)
        assertEquals(22.0, f.latest().target, 0.0)
        assertTrue(f.latest().requiresOriginConfirmation)
        f.adapter.confirm(rejected, true)
        assertEquals(listOf(24.0, 25.0), f.port.sent)
        val confirmed = f.adapter.previewRestore(hvac)
        f.adapter.confirm(confirmed, true, confirmUnknownOrigin = true)
        assertEquals(listOf(24.0, 25.0, 22.0), f.port.sent)
        assertEquals(CommandStage.READBACK, f.latest().stage)
    }

    @Test fun manualOverrideRemainsProtectedEvenIfValueReturnsToAppValue() {
        for (setting in listOf(hvac, light)) {
            val f = Fixture()
            f.apply(setting, 24.0)
            f.port.external(setting, 23.0, ChangeOrigin.MANUAL)
            f.port.external(setting, 24.0, ChangeOrigin.UNKNOWN)
            f.adapter.confirm(f.adapter.previewRestore(setting), true, true)
            assertEquals(listOf(24.0), f.port.sent)
            assertEquals(CommandStage.MANUAL, f.latest().stage)
        }
    }

    @Test fun unknownOriginWithDifferentValuePreservesManualSetting() {
        val f = Fixture()
        f.apply(hvac, 24.0)
        f.port.external(hvac, 23.0, ChangeOrigin.UNKNOWN)
        f.adapter.confirm(f.adapter.previewRestore(hvac), true, true)
        assertEquals(listOf(24.0), f.port.sent)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
    }

    @Test fun manualChangeAfterRestorePreviewInvalidatesItsConsent() {
        val f = Fixture()
        f.apply(hvac, 24.0)
        val restore = f.adapter.previewRestore(hvac)
        f.port.external(hvac, 23.0, ChangeOrigin.MANUAL)
        f.adapter.confirm(restore, true, true)
        assertEquals(listOf(24.0), f.port.sent)
        assertEquals(CommandStage.FAILED, f.latest().stage)
    }

    @Test fun sendTimeGateRejectsQueuedCommandsAfterEverySafetyLoss() {
        for (loss in listOf("D", "unknown", "restricted", "disconnected", "stale")) {
            val f = Fixture()
            f.port.delayWrite = true
            f.apply(hvac, 24.0)
            when (loss) {
                "D" -> f.adapter.updateSafety(f.parked().copy(parking = ParkingObservation(Parking.NOT_PARKED, 0, "D")))
                "unknown" -> f.adapter.updateSafety(f.parked().copy(parking = ParkingObservation()))
                "restricted" -> f.adapter.updateSafety(f.parked().copy(ux = UxObservation(true, 1, 0)))
                "disconnected" -> f.adapter.updateSafety(VehicleSafety(generation = 2))
                "stale" -> f.now = 3_001
            }
            f.port.writes.single().invoke()
            assertTrue(loss, f.port.sent.isEmpty())
            assertEquals(CommandStage.FAILED, f.latest().stage)
        }
    }

    @Test fun reconnectNeverReplaysOrRevivesOldSnapshotAndConsent() {
        val f = Fixture()
        f.apply(hvac, 24.0)
        f.port.delayWrite = true
        val restore = f.adapter.previewRestore(hvac)
        f.adapter.confirm(restore, true, true)
        repeat(4) {
            f.adapter.updateSafety(VehicleSafety(generation = it * 2L + 2))
            f.adapter.updateSafety(f.parked(it * 2L + 3))
            f.adapter.cancelPending()
        }
        f.port.writes.forEach { it() }
        f.adapter.confirm(restore, true, true)
        f.adapter.previewRestore(hvac)
        assertEquals(listOf(24.0), f.port.sent)
        assertEquals(CommandStage.MANUAL, f.latest().stage)
    }

    @Test fun stalePreflightAndRevokedPermissionAreCheckedBeforeSend() {
        for (revoke in listOf(true, false)) {
            val f = Fixture()
            val id = f.adapter.previewChange(hvac, 24.0)
            f.port.delayRead = true
            f.adapter.confirm(id, true)
            if (revoke) f.port.capability = f.port.capability.copy(canWrite = false) else f.now = 3_001
            f.port.reads.single().invoke()
            assertTrue(f.port.sent.isEmpty())
            assertEquals(CommandStage.FAILED, f.latest().stage)
        }
    }

    @Test fun queuedCommandCannotOverwriteManualUpdateAfterPreflight() {
        val f = Fixture()
        f.port.delayWrite = true
        f.apply(hvac, 24.0)
        f.port.external(hvac, 23.0, ChangeOrigin.MANUAL)
        f.port.writes.single().invoke()
        assertTrue(f.port.sent.isEmpty())
        assertEquals(CommandStage.FAILED, f.latest().stage)
    }

    @Test fun missingAckTimesOutAsUnknownWithoutRetry() {
        val f = Fixture()
        f.port.acknowledge = false
        f.apply(hvac, 24.0)
        f.now = 3_001
        f.adapter.tick()
        f.adapter.updateSafety(f.parked())
        f.adapter.tick()
        assertEquals(CommandStage.UNKNOWN, f.latest().stage)
        assertEquals(listOf(24.0), f.port.sent)
    }

    @Test fun pendingPreviewIsCancelledAndLateReadCannotResurrectIt() {
        val f = Fixture()
        f.port.delayRead = true
        val id = f.adapter.previewChange(hvac, 24.0)
        f.adapter.previewChange(hvac, 25.0)
        assertTrue(f.latest().reason.contains("pending"))
        f.adapter.cancelPending()
        f.port.reads.single().invoke()
        f.adapter.confirm(id, true)
        assertEquals(CommandStage.FAILED, f.latest().stage)
        assertTrue(f.port.sent.isEmpty())
    }

    @Test fun newAdapterHasNoPersistentCommandOrRestorationReplay() {
        val f = Fixture()
        f.apply(hvac, 24.0)
        f.adapter.close()
        assertTrue(f.port.closed)
        assertTrue(f.port.listeners.isEmpty())
        val replacement = Fixture()
        replacement.adapter.confirm(1, true, true)
        replacement.adapter.previewRestore(hvac)
        assertTrue(replacement.port.sent.isEmpty())
        assertEquals(CommandStage.MANUAL, replacement.latest().stage)
    }

    @Test fun partialFailureDoesNotTurnOtherSettingIntoSuccess() {
        val f = Fixture()
        f.apply(hvac, 24.0)
        f.port.writeFailure = SecurityException("light denied")
        f.apply(light, 25.0)
        assertEquals(CommandStage.READBACK, f.events.last { it.setting == hvac }.stage)
        assertEquals(CommandStage.FAILED, f.events.last { it.setting == light }.stage)
    }

    @Test fun cancellationFromDispatchObserverCannotSendACommand() {
        val port = Port()
        lateinit var adapter: SafeVehicleAdapter
        adapter = SafeVehicleAdapter(port, { 0 }) {
            if (it.stage == CommandStage.REQUESTED) adapter.cancelPending()
        }
        adapter.updateSafety(VehicleSafety(true, ParkingObservation(Parking.PARKED, 0),
            UxObservation(false, 0, 0), 1))
        adapter.confirm(adapter.previewChange(hvac, 24.0), true)
        assertTrue("A synchronous observer can revoke consent before native dispatch", port.sent.isEmpty())
    }
}
