package dev.pleos.starter.teum.vehicle

import org.junit.Assert.*
import org.junit.Test

class BackrestControllerTest {
    private class Fixture(
        environment: BackrestEnvironment = BackrestEnvironment(true, "ranchu", "crp_avd_arm64", BackrestEnvironment.FINGERPRINT),
        commandGate: BackrestCommandGate = BackrestCommandGate()
    ) {
        var now = 100L
        var scheduledBeforeMove = false
        var scheduleFails = false
        val tasks = mutableListOf<Task>()
        val port = FakePort { scheduledBeforeMove = tasks.any { !it.cancelled } }
        val controller = BackrestController(port, environment, { now }, BackrestScheduler { delay, action ->
            if (scheduleFails) throw IllegalStateException("scheduler unavailable")
            val task = Task(now + delay, action)
            tasks += task
            AutoCloseable { task.cancelled = true }
        }, commandGate)
        init {
            controller.safety(safe(), true, 1)
            controller.inspect()
            port.readDone?.invoke(50)
        }
        fun safe() = VehicleSafety(true, ParkingObservation(Parking.PARKED, now),
            UxObservation(false, 0, now), 1)
        fun start(duration: Long = 500) = controller.start("seat", BackrestDirection.FORWARD, duration)
        fun advance(delta: Long) {
            now += delta
            tasks.filter { !it.cancelled && it.at <= now }.toList().forEach {
                it.cancelled = true
                it.action()
            }
        }
    }
    private class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
    private class Command(
        val direction: BackrestDirection?,
        val done: () -> Unit,
        val failed: (Exception) -> Unit
    )
    private class FakePort(val onMove: () -> Unit) : BackrestPort {
        var permitted = true
        var readDone: ((Int?) -> Unit)? = null
        var listener: ((Int?) -> Unit)? = null
        var listenerFailed: ((Exception) -> Unit)? = null
        var detached = false
        var closed = false
        var writable = true
        var autoRead = true
        val commands = mutableListOf<Command>()
        override fun permissionsGranted() = permitted
        override fun inspect(done: (List<BackrestArea>) -> Unit, failed: (Exception) -> Unit) =
            done(listOf(BackrestArea("seat", 0, 100, writable)))
        override fun observe(areaId: String, position: (Int?) -> Unit, failed: (Exception) -> Unit): AutoCloseable {
            listener = position
            listenerFailed = failed
            return AutoCloseable { detached = true }
        }
        override fun read(areaId: String, position: (Int?) -> Unit, failed: (Exception) -> Unit) {
            readDone = position
            if (autoRead) position(50)
        }
        override fun move(areaId: String, direction: BackrestDirection?, done: () -> Unit, failed: (Exception) -> Unit) {
            onMove()
            commands += Command(direction, done, failed)
        }
        override fun close() { closed = true }
    }

    @Test fun deadlineIsArmedBeforeMovementAndStopsAt500WithoutPointerUp() {
        val f = Fixture()
        assertTrue(f.start())
        assertTrue(f.scheduledBeforeMove)
        f.advance(499)
        assertEquals(1, f.port.commands.size)
        f.advance(1)
        assertEquals(listOf(BackrestDirection.FORWARD, null), f.port.commands.map { it.direction })
        assertEquals(BackrestAck.PENDING, f.controller.snapshot().stopAck)
    }

    @Test fun schedulingFailureAndInvalidDurationsNeverIssueMovement() {
        val f = Fixture()
        for (duration in listOf(-1L, 0L, 501L, Long.MAX_VALUE)) assertFalse(f.start(duration))
        f.scheduleFails = true
        assertTrue(f.start())
        assertEquals(BackrestAck.FAILED, f.controller.snapshot().requestAck)
        assertTrue(f.port.commands.isEmpty())
    }

    @Test fun releaseAndOtherEmulatorIdentitiesFailClosed() {
        val valid = BackrestEnvironment(true, "ranchu", "crp_avd_arm64", BackrestEnvironment.FINGERPRINT)
        for (environment in listOf(valid.copy(debuggable = false), valid.copy(hardware = "goldfish"),
            valid.copy(product = "other"), valid.copy(fingerprint = valid.fingerprint + "x"),
            valid.copy(fingerprint = "Pleos/crp_avd_arm64/unknown"))) {
            val f = Fixture(environment)
            assertFalse(f.start())
            assertTrue(f.port.commands.isEmpty())
        }
    }

    @Test fun requestAndStopAcksDoNotProveMovementButListenerDeltaDoes() {
        val f = Fixture()
        f.start()
        f.port.commands[0].done()
        f.advance(500)
        f.port.commands[1].done()
        val acknowledged = f.controller.snapshot()
        assertEquals(BackrestAck.ACKNOWLEDGED, acknowledged.requestAck)
        assertEquals(BackrestAck.ACKNOWLEDGED, acknowledged.stopAck)
        assertNull(acknowledged.rawPositionDelta)
        assertFalse(acknowledged.busy)
        f.port.listener?.invoke(47)
        assertEquals(-3L, f.controller.snapshot().rawPositionDelta)
    }

    @Test fun pendingMovementKeepsExclusionAndLateAckSendsOnlyStop() {
        val f = Fixture()
        f.start()
        f.advance(500)
        f.port.commands[1].done()
        assertFalse(f.start())
        f.port.commands[0].done()
        assertEquals(listOf(BackrestDirection.FORWARD, null, null), f.port.commands.map { it.direction })
        assertTrue(f.controller.snapshot().busy)
        f.port.commands[2].done()
        assertFalse(f.controller.snapshot().busy)
        f.port.commands[0].done()
        f.port.commands[2].done()
        assertEquals(3, f.port.commands.size)
    }

    @Test fun replacementControllerCannotMoveUntilOldMovementAndStopsSettle() {
        val gate = BackrestCommandGate()
        val oldDocument = Fixture(commandGate = gate)
        oldDocument.start()
        oldDocument.controller.close()
        oldDocument.port.commands[1].done()
        val newDocument = Fixture(commandGate = gate)

        newDocument.start()
        assertTrue(newDocument.port.commands.isEmpty())
        assertTrue(oldDocument.controller.snapshot().busy)
        oldDocument.port.commands[0].done()
        newDocument.start()
        assertTrue(newDocument.port.commands.isEmpty())
        oldDocument.port.commands[2].done()
        newDocument.start()

        assertEquals(listOf(BackrestDirection.FORWARD), newDocument.port.commands.map { it.direction })
        assertTrue(oldDocument.port.closed)
        newDocument.controller.stop()
        newDocument.port.commands[0].done()
        newDocument.port.commands[1].done()
        newDocument.port.commands[2].done()
    }

    @Test fun outOfOrderStopCallbacksCannotClearLatestStopFailure() {
        val f = Fixture()
        f.start()
        f.controller.stop()
        f.port.commands[0].done()
        f.port.commands[2].failed(IllegalStateException("STOP rejected"))
        f.port.commands[1].done()
        assertEquals(BackrestAck.FAILED, f.controller.snapshot().stopAck)
        assertTrue(f.controller.snapshot().busy)
        assertFalse(f.start())
    }

    @Test fun backgroundEpochAndNativeSafetyLossStopWithoutAuthorization() {
        for (mode in 0..6) {
            val f = Fixture()
            f.start()
            when (mode) {
                0 -> f.controller.safety(f.safe(), false, 1)
                1 -> f.controller.safety(f.safe(), true, 2)
                2 -> f.controller.safety(f.safe().copy(generation = 2), true, 1)
                3 -> f.controller.safety(f.safe().copy(parking = ParkingObservation(Parking.NOT_PARKED, f.now)), true, 1)
                4 -> f.controller.safety(f.safe().copy(ux = UxObservation(true, 1, f.now)), true, 1)
                5 -> { f.port.permitted = false; f.controller.tick() }
                6 -> f.controller.safety(VehicleSafety(), true, 1)
            }
            assertEquals(listOf(BackrestDirection.FORWARD, null), f.port.commands.map { it.direction })
        }
    }

    @Test fun staleSafetyCutsPulseAtNativeFreshnessBoundary() {
        val f = Fixture()
        f.now = 3_000
        f.port.listener?.invoke(51)
        assertTrue(f.start())
        f.advance(100)
        assertEquals(1, f.port.commands.size)
        f.advance(1)
        assertEquals(2, f.port.commands.size)
    }

    @Test fun missingParkUxPermissionsOrWritableCapabilityBlocksStart() {
        for (mode in 0..4) {
            val f = Fixture()
            when (mode) {
                0 -> f.controller.safety(VehicleSafety(), true, 1)
                1 -> f.controller.safety(f.safe().copy(ux = UxObservation()), true, 1)
                2 -> { f.now += 3001 }
                3 -> f.port.permitted = false
                4 -> { f.port.writable = false; f.controller.inspect(); f.port.readDone?.invoke(50) }
            }
            assertFalse(f.start())
            assertTrue(f.port.commands.isEmpty())
        }
    }

    @Test fun closeRetainsHandleThroughLateRequestAndStopThenReleases() {
        val f = Fixture()
        f.start()
        f.controller.close()
        assertTrue(f.port.detached)
        assertFalse(f.port.closed)
        f.port.commands[1].done()
        f.port.commands[0].done()
        assertFalse(f.port.closed)
        f.port.commands[2].done()
        assertTrue(f.port.closed)
        assertTrue(f.controller.snapshot().closed)
        assertFalse(f.start())
        f.advance(1000)
        assertEquals(3, f.port.commands.size)
    }

    @Test fun staleGetCannotOverwriteListenerOrAuthorizeAStart() {
        val f = Fixture()
        f.port.autoRead = false
        f.controller.inspect()
        f.port.listener?.invoke(62)
        f.port.readDone?.invoke(20)
        assertEquals(62, f.controller.snapshot().positions["seat"]?.raw)
        f.controller.inspect()
        f.now += 3001
        f.port.readDone?.invoke(50)
        f.controller.safety(f.safe(), true, 1)
        assertTrue(f.start())
        f.now += 3001
        f.controller.safety(f.safe(), true, 1)
        f.port.readDone?.invoke(50)
        assertTrue(f.port.commands.isEmpty())
        assertFalse(f.controller.snapshot().busy)
    }

    @Test fun invalidObservationAndFailureStopActivePulse() {
        for (raw in listOf(null, -1, 101)) {
            val f = Fixture()
            f.start()
            f.port.listener?.invoke(raw)
            assertEquals(2, f.port.commands.size)
        }
        val f = Fixture()
        f.start()
        f.port.listenerFailed?.invoke(IllegalStateException("Disconnected"))
        assertEquals(2, f.port.commands.size)
    }

    @Test fun movementFailureIsNotRetriedAndRequiresStop() {
        val f = Fixture()
        f.start()
        f.port.commands[0].failed(IllegalStateException("Rejected"))
        assertEquals(listOf(BackrestDirection.FORWARD, null), f.port.commands.map { it.direction })
        assertEquals(BackrestAck.FAILED, f.controller.snapshot().requestAck)
        assertNull(f.controller.snapshot().rawPositionDelta)
    }

    @Test fun failedListenerCannotBeReauthorizedBySuccessfulGetOrLateUpdate() {
        val f = Fixture()
        f.controller.inspect()
        f.port.listenerFailed?.invoke(IllegalStateException("Listener failed"))
        f.port.readDone?.invoke(50)
        f.port.listener?.invoke(51)
        assertFalse(f.start())
        assertTrue(f.port.commands.isEmpty())
    }

    @Test fun failedStopDoesNotRetryOnTicksButCloseStillAttemptsCancellation() {
        val f = Fixture()
        f.start()
        f.port.commands[0].done()
        f.controller.stop()
        f.port.commands[1].failed(IllegalStateException("STOP rejected"))
        repeat(5) { f.controller.tick() }
        assertEquals(2, f.port.commands.size)
        f.controller.close()
        assertEquals(listOf(BackrestDirection.FORWARD, null, null), f.port.commands.map { it.direction })
        f.port.commands[2].done()
        assertTrue(f.port.closed)
    }

    @Test fun delayedHumanClickObtainsFreshGetThenArmsPulseBeforeMovement() {
        val f = Fixture()
        f.now += 30_000
        f.controller.safety(f.safe(), true, 1)
        f.port.autoRead = false
        assertTrue(f.start())
        assertTrue(f.controller.snapshot().preparing)
        assertTrue(f.controller.snapshot().busy)
        assertTrue(f.port.commands.isEmpty())
        f.port.readDone?.invoke(63)
        assertFalse(f.controller.snapshot().preparing)
        assertTrue(f.scheduledBeforeMove)
        assertEquals(1, f.port.commands.size)
        assertEquals(63, f.controller.snapshot().positions["seat"]?.raw)
    }

    @Test fun pendingClickIsNotResurrectedAfterEpochSafetyOrLifecycleLoss() {
        for (mode in 0..6) {
            val f = Fixture()
            f.port.autoRead = false
            assertTrue(f.start())
            val delayed = requireNotNull(f.port.readDone)
            when (mode) {
                0 -> {
                    f.controller.safety(f.safe(), true, 2)
                    f.controller.safety(f.safe(), true, 1)
                }
                1 -> {
                    f.controller.safety(f.safe().copy(parking = ParkingObservation(Parking.NOT_PARKED, f.now)), true, 1)
                    f.controller.safety(f.safe(), true, 1)
                }
                2 -> {
                    f.controller.safety(f.safe().copy(ux = UxObservation(true, 1, f.now)), true, 1)
                    f.controller.safety(f.safe(), true, 1)
                }
                3 -> {
                    f.controller.safety(f.safe(), false, 1)
                    f.controller.safety(f.safe(), true, 1)
                }
                4 -> f.controller.stop()
                5 -> f.controller.close()
                6 -> {
                    f.controller.safety(f.safe().copy(generation = 2), true, 1)
                    f.controller.safety(f.safe(), true, 1)
                }
            }
            delayed(60)
            assertTrue(f.port.commands.isEmpty())
            assertFalse(f.controller.snapshot().busy)
        }
    }

    @Test fun competingPendingStartsAreRejectedAndCancelledGetCannotReplaceNewClick() {
        val f = Fixture()
        f.port.autoRead = false
        assertTrue(f.start())
        val old = requireNotNull(f.port.readDone)
        assertFalse(f.controller.start("seat", BackrestDirection.BACKWARD))
        f.controller.stop()
        assertTrue(f.controller.start("seat", BackrestDirection.BACKWARD))
        val current = requireNotNull(f.port.readDone)
        old(30)
        assertTrue(f.port.commands.isEmpty())
        assertTrue(f.controller.snapshot().preparing)
        current(60)
        assertEquals(listOf(BackrestDirection.BACKWARD), f.port.commands.map { it.direction })
    }

    @Test fun preflightCallbackRechecksNativePermissionsSafetyAndRawValue() {
        for (mode in 0..3) {
            val f = Fixture()
            f.port.autoRead = false
            assertTrue(f.start())
            when (mode) {
                0 -> f.port.permitted = false
                1 -> f.now += 3001
                2 -> f.port.listenerFailed?.invoke(IllegalStateException("Listener lost"))
                3 -> Unit
            }
            f.port.readDone?.invoke(if (mode == 3) 101 else 50)
            assertTrue(f.port.commands.isEmpty())
            assertFalse(f.controller.snapshot().busy)
        }
    }
}
