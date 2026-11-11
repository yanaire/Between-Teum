package dev.pleos.starter.teum.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission

data class PedalObservation(
    val pressed: Boolean?,
    val observedAtElapsedMillis: Long,
    val safetyGeneration: Long,
    val revision: Long,
    val error: String?,
    val cleanupErrors: List<String>
)

/** Correlates requests across foreground/service and same-generation safety loss. */
internal class BrakeReadEpoch {
    private var epoch = 0L
    fun invalidate(): Long = ++epoch
    fun current(): Long = epoch
    fun accepts(token: Long, requestedAt: Long, now: Long): Boolean =
        token == epoch && VehicleSafety.fresh(requestedAt, now)
}

/** Main-thread SDK owner; change notifications never freshen a cached pressed value. */
class PleosBrakeSource(
    context: Context,
    private val changed: (PedalObservation) -> Unit
) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val requests = BrakeReadEpoch()
    private var lifecycle = 0L
    private var sdk: Vehicle? = null
    private var listener: BrakePedalStatusListener? = null
    private var generation: Long? = null
    private var pending = false
    private var readable = false
    private var closed = false
    private val cleanupErrors = mutableListOf<String>()
    @Volatile var revision = 0L
        private set

    fun observeSafety(safety: VehicleSafety) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        val target = safety.generation.takeIf {
            safety.blockedReason(SystemClock.elapsedRealtime()) == null
        }
        if (target != generation) {
            dispose()
            generation = target
            publish(null, -1, null)
            if (target != null) start()
        }
    }

    @RequiresPermission(value = PERMISSION, conditional = true)
    private fun start() {
        val owner = lifecycle
        try {
            if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Missing $PERMISSION")
            val service = Vehicle(context)
            sdk = service
            service.initialize()
            val brake = service.getBrake()
            brake.checkBrakePedalStatusCapabilities({ config ->
                main.post {
                    if (closed || owner != lifecycle) return@post
                    if (!granted()) { fail(SecurityException("Read permission revoked")); return@post }
                    if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) {
                        publish(null, -1, "Brake access ${config.accessType}")
                        return@post
                    }
                    readable = true
                    val callback = object : BrakePedalStatusListener {
                        override fun onBrakePedalStatusUpdated(status: Boolean?) {
                            main.post {
                                if (closed || owner != lifecycle) return@post
                                // Any change supersedes the outstanding current read.
                                requests.invalidate()
                                pending = false
                                publish(null, -1, if (status == null) "Brake unavailable" else null)
                                refresh()
                            }
                        }
                        override fun onFailed(exception: Exception) {
                            main.post { if (!closed && owner == lifecycle) fail(exception) }
                        }
                    }
                    listener = callback
                    try {
                        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
                            throw SecurityException("Read permission revoked")
                        brake.registerBrakePedalStatus(callback)
                        refresh()
                    } catch (error: Exception) { fail(error) }
                }
            }, { error -> main.post { if (!closed && owner == lifecycle) fail(error) } })
        } catch (error: Exception) { fail(error) }
    }

    @RequiresPermission(value = PERMISSION, conditional = true)
    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || !readable || pending) return
        val service = sdk ?: return
        val token = requests.current()
        val started = SystemClock.elapsedRealtime()
        pending = true
        try {
            if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Read permission revoked")
            service.getBrake().getCurrentBrakePedalStatus({ value ->
                main.post {
                    if (closed || token != requests.current()) return@post
                    pending = false
                    if (!granted()) { fail(SecurityException("Read permission revoked")); return@post }
                    if (requests.accepts(token, started, SystemClock.elapsedRealtime()))
                        publish(value, started, if (value == null) "Brake unavailable" else null)
                    else publish(null, -1, "Brake current read stale")
                }
            }, { error ->
                main.post { if (!closed && token == requests.current()) { pending = false; fail(error) } }
            })
        } catch (error: Exception) { pending = false; fail(error) }
    }

    private fun granted() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    private fun publish(value: Boolean?, at: Long, error: String?) {
        revision++
        changed(PedalObservation(value, at, generation ?: -1, revision, error, cleanupErrors.toList()))
    }

    private fun fail(error: Exception) {
        dispose()
        publish(null, -1, "${error.javaClass.simpleName}: ${error.message}")
    }

    @RequiresPermission(value = PERMISSION, conditional = true)
    private fun dispose() {
        lifecycle++
        requests.invalidate()
        readable = false
        pending = false
        val service = sdk
        val callback = listener
        sdk = null
        listener = null
        try {
            if (service != null && callback != null) service.getBrake().unregisterBrakePedalStatus(callback)
        } catch (error: Exception) {
            cleanupErrors += "Brake unregister ${error.javaClass.simpleName}: ${error.message}"
            publish(null, -1, cleanupErrors.last())
        }
        finally {
            try { service?.release() }
            catch (error: Exception) {
                cleanupErrors += "Brake release ${error.javaClass.simpleName}: ${error.message}"
                publish(null, -1, cleanupErrors.last())
            }
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true
        dispose()
        generation = null
        publish(null, -1, null)
    }

    companion object { const val PERMISSION = "pleos.car.permission.CAR_DRIVE" }
}
