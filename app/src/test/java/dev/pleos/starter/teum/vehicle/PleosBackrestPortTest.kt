package dev.pleos.starter.teum.vehicle

import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** JVM fake only at the unavailable native IPC boundary; executes the real SDK adapter. */
@Suppress("UNCHECKED_CAST")
class PleosBackrestPortTest {
    private class Fixture(
        val readArea: SeatArea = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT),
        val moveArea: SeatArea = SeatArea(*readArea.values),
        var readAccess: AccessType = AccessType.READ,
        var moveAccess: AccessType = AccessType.WRITE
    ) {
        var permitted = true
        var releases = 0
        var listener: SeatBackrestAnglePositionListener? = null
        var registeredArea: SeatArea? = null
        var unregisteredArea: SeatArea? = null
        var unregisteredListener: SeatBackrestAnglePositionListener? = null
        var getterArea: SeatArea? = null
        var getterDone: ((Int?) -> Unit)? = null
        var moveReturnedArea = moveArea
        var mismatch = false
        val commands = mutableListOf<Pair<SeatArea, MoveControl>>()
        val pending = mutableListOf<() -> Unit>()
        val sdk = Proxy.newProxyInstance(Seat::class.java.classLoader, arrayOf(Seat::class.java)) { _, method, arguments ->
            val args = arguments ?: emptyArray()
            when (method.name) {
                "checkSeatBackrestAnglePositionCapabilities" ->
                    (args[0] as (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit)(
                        CapabilityConfig(readAccess), mapOf(readArea to AreaBounds(10, 90)))
                "checkMoveSeatBackrestAngleCapabilities" ->
                    (args[0] as (CapabilityConfig, List<SeatArea>) -> Unit)(
                        CapabilityConfig(moveAccess), listOf(moveArea))
                "getCurrentSeatBackrestAnglePosition" -> {
                    getterArea = args[0] as SeatArea
                    getterDone = args[1] as (Int?) -> Unit
                }
                "registerSeatBackrestAnglePosition" -> {
                    registeredArea = args[0] as SeatArea
                    listener = args[1] as SeatBackrestAnglePositionListener
                }
                "unregisterSeatBackrestAnglePosition" -> {
                    unregisteredArea = args[0] as SeatArea
                    unregisteredListener = args[1] as SeatBackrestAnglePositionListener
                }
                "moveSeatBackrestAngle" -> {
                    val area = args[0] as SeatArea
                    val command = args[1] as MoveControl
                    commands += area to command
                    val done = args[2] as (SeatArea, MoveControl?) -> Unit
                    pending += { done(moveReturnedArea, if (mismatch) null else command) }
                }
                else -> error("Unexpected SDK method ${method.name}")
            }
            null
        } as Seat
        val port = PleosBackrestPort({ sdk }, { permitted }, { it() }, { releases++ })
        fun discover(): List<BackrestArea> {
            var result = emptyList<BackrestArea>()
            port.inspect({ result = it }, { throw it })
            return result
        }
    }

    @Test fun exactCompositeCapabilityObjectsReachGetterListenerAndDirectionalSetter() {
        val f = Fixture()
        val area = f.discover().single()
        var observed: Int? = null
        var read: Int? = null
        val subscription = f.port.observe(area.id, { observed = it }, { throw it })
        f.port.read(area.id, { read = it }, { throw it })
        f.getterDone?.invoke(31)
        f.listener?.onSeatBackrestAnglePositionUpdated(32)
        for (direction in listOf(BackrestDirection.FORWARD, BackrestDirection.BACKWARD, null))
            f.port.move(area.id, direction, {}, { throw it })
        subscription.close()
        assertSame(f.readArea, f.getterArea)
        assertSame(f.readArea, f.registeredArea)
        assertSame(f.readArea, f.unregisteredArea)
        assertSame(f.listener, f.unregisteredListener)
        assertTrue(f.commands.all { it.first === f.moveArea })
        assertEquals(listOf(MoveControl.FORWARD, MoveControl.BACKWARD, MoveControl.STOP), f.commands.map { it.second })
        assertEquals(31, read)
        assertEquals(32, observed)
        assertEquals(10, area.minRaw)
        assertEquals(90, area.maxRaw)
    }

    @Test fun globalAreaIsKeptWholeAndUnmatchedMovementAreaIsNotGuessed() {
        val global = Fixture(SeatArea())
        val discovered = global.discover().single()
        global.port.move(discovered.id, BackrestDirection.FORWARD, {}, { throw it })
        assertSame(global.moveArea, global.commands.single().first)
        val mismatch = Fixture(moveArea = SeatArea(SeatValue.ROW_2_LEFT))
        assertFalse(mismatch.discover().single().writable)
        var rejected = false
        mismatch.port.move(mismatch.discover().single().id, BackrestDirection.FORWARD, {}, { rejected = true })
        assertTrue(rejected)
        assertTrue(mismatch.commands.isEmpty())
    }

    @Test fun accessIntersectionRequiresPositionReadAndMovementWrite() {
        for (read in AccessType.entries) for (move in AccessType.entries) {
            val f = Fixture(readAccess = read, moveAccess = move)
            val areas = f.discover()
            if (read == AccessType.READ || read == AccessType.READ_WRITE) {
                assertEquals(move == AccessType.WRITE || move == AccessType.READ_WRITE, areas.single().writable)
            } else assertTrue(areas.isEmpty())
        }
    }

    @Test fun revokedAuthorizationBlocksMovementButStillAttemptsStop() {
        val f = Fixture()
        val area = f.discover().single()
        f.permitted = false
        var failures = 0
        f.port.move(area.id, BackrestDirection.FORWARD, {}, { failures++ })
        f.port.move(area.id, null, {}, { throw it })
        assertEquals(1, failures)
        assertEquals(listOf(MoveControl.STOP), f.commands.map { it.second })
    }

    @Test fun pendingCallbacksRetainVehicleAndDuplicateCallbackCannotReleaseTwice() {
        val f = Fixture()
        val area = f.discover().single()
        f.port.move(area.id, null, {}, { throw it })
        f.port.close()
        assertEquals(0, f.releases)
        f.pending.single()()
        f.pending.single()()
        assertEquals(1, f.releases)
    }

    @Test fun mismatchedAcknowledgmentFailsWithoutClaimingCompletion() {
        val f = Fixture()
        val area = f.discover().single()
        var acknowledgments = 0
        var failures = 0
        f.port.move(area.id, BackrestDirection.FORWARD, { acknowledgments++ }, { failures++ })
        f.mismatch = true
        f.pending.single()()
        assertEquals(0, acknowledgments)
        assertEquals(1, failures)
    }
}
