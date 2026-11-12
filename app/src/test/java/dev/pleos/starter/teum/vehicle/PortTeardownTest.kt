package dev.pleos.starter.teum.vehicle

import org.junit.Assert.*
import org.junit.Test

class PortTeardownTest {
    @Test fun closeWhenIdleReleasesImmediately() {
        val lifecycle = PortLifecycle()
        var releases = 0
        lifecycle.onRelease = { releases++ }
        lifecycle.close()
        assertTrue(lifecycle.isClosed())
        assertEquals(1, releases)
    }

    @Test fun newCallsAreRejectedAfterClose() {
        val lifecycle = PortLifecycle()
        lifecycle.onRelease = { }
        assertTrue(lifecycle.beginCall())
        lifecycle.close()
        assertFalse(lifecycle.beginCall())
        assertFalse(lifecycle.beginCall())
    }

    @Test fun closeWithNativeInflightDefersReleaseUntilRealSettles() {
        val lifecycle = PortLifecycle()
        var releases = 0
        lifecycle.onRelease = { releases++ }
        val first = lifecycle.beginNative()
        val second = lifecycle.beginNative()
        assertEquals(2, lifecycle.nativeInflightCount())
        lifecycle.close()
        assertEquals(0, releases)
        first.settle()
        assertEquals(1, lifecycle.nativeInflightCount())
        assertEquals(0, releases)
        second.settle()
        assertEquals(0, lifecycle.nativeInflightCount())
        assertEquals(1, releases)
    }

    @Test fun releaseRunsOnceAcrossDoubleCloseAndExtraSettle() {
        val lifecycle = PortLifecycle()
        var releases = 0
        lifecycle.onRelease = { releases++ }
        val guard = lifecycle.beginNative()
        lifecycle.close()
        lifecycle.close()
        guard.settle()
        guard.settle()
        assertEquals(1, releases)
        assertEquals(0, lifecycle.nativeInflightCount())
    }

    @Test fun unansweredNativeCallRetainsHandleAfterClose() {
        // The SDK never fires this request's callback: close() must not
        // release, and no logical timeout path settles the guard. The handle
        // is retained; only a real native settle would release it.
        val lifecycle = PortLifecycle()
        var releases = 0
        lifecycle.onRelease = { releases++ }
        lifecycle.beginNative()
        lifecycle.close()
        assertEquals(1, lifecycle.nativeInflightCount())
        assertEquals(0, releases)
        assertTrue(lifecycle.isClosed())
        assertFalse(lifecycle.beginCall())
    }

    @Test fun failedUnregisterRetainsGuardAndHandle() {
        // Models an unregister that throws: the port logs and keeps the guard
        // unsettled, so a possibly live subscription blocks the release.
        val lifecycle = PortLifecycle()
        var releases = 0
        lifecycle.onRelease = { releases++ }
        lifecycle.beginNative()
        lifecycle.close()
        assertEquals(1, lifecycle.nativeInflightCount())
        assertEquals(0, releases)
    }

    @Test fun lateNativeSettleAfterCloseReleasesExactlyOnce() {
        val lifecycle = PortLifecycle()
        var releases = 0
        lifecycle.onRelease = { releases++ }
        val guard = lifecycle.beginNative()
        lifecycle.close()
        assertEquals(0, releases)
        guard.settle()
        assertEquals(1, releases)
        guard.settle()
        assertEquals(1, releases)
    }

    private val hvac = VehicleSetting(VehicleFeature.HVAC_TEMPERATURE, "ROW_1_LEFT")

    private class Port : VehicleSettingsPort {
        var value = 22.0
        var writes = mutableListOf<() -> Unit>()
        var sent = mutableListOf<Double>()
        var delayWrite = false
        override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
            result(Result.success(SettingInspection(
                SettingCapability(true, true, 16.0, 30.0, 0.5, "test"), value)))
        }
        override fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable =
            AutoCloseable { }
        override fun write(setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit) {
            val send = {
                if (!permit()) result(Result.failure(IllegalStateException("cancelled")))
                else {
                    sent += value
                    this.value = value
                    result(Result.success(Unit))
                }
            }
            if (delayWrite) writes += send else send()
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
    }

    @Test fun closedAdapterSendsNothingAndReportsClosure() {
        val f = Fixture()
        f.adapter.close()
        f.adapter.previewChange(hvac, 24.0)
        assertTrue(f.port.sent.isEmpty())
        assertTrue(f.port.writes.isEmpty())
        val latest = f.events.last()
        assertEquals("Adapter closed", latest.reason)
        assertEquals(CommandStage.MANUAL, latest.stage)
    }

    @Test fun cancelledQueueNeverReachesTheNativeSetter() {
        val f = Fixture()
        f.port.delayWrite = true
        f.adapter.confirm(f.adapter.previewChange(hvac, 24.0), true)
        f.adapter.cancelPending()
        assertEquals(1, f.port.writes.size)
        f.port.writes.single().invoke()
        assertTrue(f.port.sent.isEmpty())
        assertEquals(CommandStage.FAILED, f.events.last().stage)
    }

    @Test fun safetyLossBeforeDispatchNeverReachesTheNativeSetter() {
        val f = Fixture()
        f.port.delayWrite = true
        f.adapter.confirm(f.adapter.previewChange(hvac, 24.0), true)
        f.now = 3_001
        f.adapter.tick()
        f.port.writes.single().invoke()
        assertTrue(f.port.sent.isEmpty())
        assertEquals(CommandStage.FAILED, f.events.last().stage)
    }
}
