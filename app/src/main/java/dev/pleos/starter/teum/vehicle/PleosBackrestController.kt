package dev.pleos.starter.teum.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

private val processBackrestGate = BackrestCommandGate()

/** Main-thread entrypoints; safety must come from the parent's native AAOS source. */
class PleosBackrestController(context: Context) : BackrestController(
    PleosBackrestPort(context.applicationContext),
    BackrestEnvironment(
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        Build.HARDWARE, Build.PRODUCT, Build.FINGERPRINT
    ),
    SystemClock::elapsedRealtime,
    BackrestScheduler { delay, action ->
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable { action() }
        check(handler.postDelayed(task, delay)) { "Native backrest deadline rejected" }
        AutoCloseable { handler.removeCallbacks(task) }
    },
    processBackrestGate
)

/** Actual Vehicle 2.3.2 calls. No reconstructed areas, absolute setter or degree conversion. */
internal class PleosBackrestPort(
    private val seat: () -> Seat,
    private val permissionCheck: () -> Boolean,
    private val dispatch: (() -> Unit) -> Unit,
    private val release: () -> Unit,
    private val initializationError: Exception? = null
) : BackrestPort {
    constructor(context: Context) : this(PleosBackrestNative(context))
    private constructor(native: PleosBackrestNative) : this(
        native.vehicle::getSeat, native::permissionsGranted, native::dispatch,
        native.vehicle::release, native.initializationError
    )
    private val bindings = mutableMapOf<String, SeatArea>()
    private var pending = 0
    private var closed = false
    private var released = false
    private var attached = 0
    override fun permissionsGranted() = permissionCheck()

    /** Retain the native Vehicle through real callbacks, including STOP during close. */
    private fun <T> request(
        done: (T) -> Unit,
        failed: (Exception) -> Unit,
        issue: ((T) -> Unit, (Exception) -> Unit) -> Unit
    ) {
        if (closed) { failed(IllegalStateException("Backrest port closed")); return }
        initializationError?.let { failed(it); return }
        pending++
        var settled = false
        fun settle(deliver: () -> Unit) {
            dispatch {
                if (!settled) {
                    settled = true
                    try {
                        deliver()
                    } finally {
                        pending--
                        releaseIfClosed()
                    }
                }
            }
        }
        try { issue({ value -> settle { done(value) } }, { e -> settle { failed(e) } }) }
        catch (e: RuntimeException) { settle { failed(e) } }
    }

    override fun inspect(done: (List<BackrestArea>) -> Unit, failed: (Exception) -> Unit) {
        if (!permissionsGranted()) { failed(SecurityException("Seat permissions missing")); return }
        request<Pair<AccessType, Map<SeatArea, Pair<Int, Int>>>>({ (readAccess, positions) ->
            if (closed) return@request
            request<Pair<AccessType, List<SeatArea>>>({ (moveAccess, movementAreas) ->
                bindings.clear()
                movementBindings.clear()
                val readable = readAccess == AccessType.READ || readAccess == AccessType.READ_WRITE
                val writable = moveAccess == AccessType.WRITE || moveAccess == AccessType.READ_WRITE
                val result = if (!readable) emptyList() else positions.entries.mapIndexed { index, (area, range) ->
                    // Preserve the exact movement capability object separately from read area.
                    val moveArea = movementAreas.singleOrNull { it == area }
                    val id = "$index:" + area.values.joinToString("+") { it.name }
                    bindings[id] = area
                    if (moveArea != null && writable) movementBindings[id] = moveArea
                    BackrestArea(id, range.first, range.second, writable && moveArea != null)
                }
                done(result)
            }, failed) { ok, bad ->
                seat().checkMoveSeatBackrestAngleCapabilities({ config, areas ->
                    ok(config.accessType to areas)
                }, bad)
            }
        }, failed) { ok, bad ->
            seat().checkSeatBackrestAnglePositionCapabilities({ config, ranges ->
                ok(config.accessType to ranges.mapValues { (_, range) -> range.minValue to range.maxValue })
            }, bad)
        }
    }

    private val movementBindings = mutableMapOf<String, SeatArea>()

    override fun read(areaId: String, position: (Int?) -> Unit, failed: (Exception) -> Unit) {
        val area = bindings[areaId] ?: run { failed(IllegalArgumentException("Undiscovered area")); return }
        request(position, failed) { ok, bad ->
            seat().getCurrentSeatBackrestAnglePosition(area, ok, bad)
        }
    }

    override fun observe(areaId: String, position: (Int?) -> Unit, failed: (Exception) -> Unit): AutoCloseable {
        val area = bindings[areaId] ?: run {
            failed(IllegalArgumentException("Undiscovered area"))
            return AutoCloseable {}
        }
        var listening = true
        val listener = object : SeatBackrestAnglePositionListener {
            override fun onSeatBackrestAnglePositionUpdated(positionValue: Int?) {
                dispatch { if (listening && !closed) position(positionValue) }
            }
            override fun onFailed(e: Exception) { dispatch { if (listening && !closed) failed(e) } }
        }
        try {
            seat().registerSeatBackrestAnglePosition(area, listener)
            attached++
        } catch (e: RuntimeException) {
            listening = false
            failed(e)
        }
        return AutoCloseable {
            if (listening) {
                listening = false
                try {
                    seat().unregisterSeatBackrestAnglePosition(area, listener)
                    attached--
                    releaseIfClosed()
                } catch (e: RuntimeException) {
                    // Unknown native subscription lifetime retains the handle.
                    failed(e)
                }
            }
        }
    }

    override fun move(areaId: String, direction: BackrestDirection?, done: () -> Unit, failed: (Exception) -> Unit) {
        val area = movementBindings[areaId] ?: run { failed(IllegalArgumentException("Undiscovered movement area")); return }
        // STOP deliberately bypasses permission/safety rechecks: attempt cancellation even after revocation.
        if (direction != null && !permissionsGranted()) { failed(SecurityException("Seat permissions missing")); return }
        val command = when (direction) {
            BackrestDirection.FORWARD -> MoveControl.FORWARD
            BackrestDirection.BACKWARD -> MoveControl.BACKWARD
            null -> MoveControl.STOP
        }
        request<Unit>({ done() }, failed) { ok, bad ->
            seat().moveSeatBackrestAngle(area, command, { returnedArea, returned ->
                if (returnedArea == area && returned == command) ok(Unit)
                else bad(IllegalStateException("Backrest command acknowledgment mismatch"))
            }, bad)
        }
    }

    override fun close() {
        closed = true
        releaseIfClosed()
    }

    private fun releaseIfClosed() {
        if (closed && pending == 0 && attached == 0 && !released) {
            released = true
            release()
        }
    }
}

private class PleosBackrestNative(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    val vehicle = Vehicle(context)
    val initializationError = try {
        vehicle.initialize()
        null
    } catch (e: RuntimeException) { e }

    fun permissionsGranted() =
        context.checkSelfPermission("pleos.car.permission.CAR_SEATS") == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_SEATS") == PackageManager.PERMISSION_GRANTED

    fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }
}
