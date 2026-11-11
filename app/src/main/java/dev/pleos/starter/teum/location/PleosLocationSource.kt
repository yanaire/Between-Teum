package dev.pleos.starter.teum.location

import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import java.time.Clock

/**
 * Foreground owner. Call start/refresh/close on the parent's serialized event owner.
 * SDK callbacks synchronize internally; deliver observer work onto the parent's owner.
 * No preference writes: capture() only returns validated coordinates to its caller.
 */
class PleosLocationSource(
    context: Context,
    private val clock: Clock,
    private val changed: (LocationState) -> Unit
) : AutoCloseable {
    private val context = context.applicationContext
    private var sdk: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private var generation = 0L
    private var latest: LocationFix? = null
    @Volatile var state: LocationState = LocationState.Stopped
        private set
    @Volatile var privacy: LocationPrivacy = LocationPrivacy.UNKNOWN
        private set

    fun permissionGranted(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        close()
        if (!permissionGranted()) {
            publish(LocationState.PermissionDenied)
            return
        }
        val service = FusedLocation(context)
        if (!service.isSupported()) {
            publish(LocationState.Unsupported)
            return
        }
        sdk = service
        val epoch = generation
        val callback = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) = receive(epoch, location)
            override fun onError(exception: Exception) = failure(epoch, exception)
        }
        listener = callback
        publish(LocationState.Waiting)
        try {
            service.initialize()
            service.registerFusedLocationCallback(callback)
            service.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    synchronized(this@PleosLocationSource) {
                        if (latest == null) receive(epoch, location)
                    }
                }
                override fun onError(exception: Exception) {
                    synchronized(this@PleosLocationSource) {
                        if (latest == null) failure(epoch, exception)
                    }
                }
            })
        } catch (exception: Exception) {
            failure(epoch, exception)
        }
    }

    /** Recheck on permission result, resume and periodic foreground freshness tick. */
    @Synchronized
    fun refresh(): LocationState {
        if (sdk == null) return state
        if (!permissionGranted()) {
            close()
            publish(LocationState.PermissionDenied)
        } else {
            latest?.let { publish(it.error(clock.millis())?.let(LocationState::Invalid)
                ?: LocationState.Ready(it)) }
        }
        return state
    }

    /** Explicit user capture only. Parent may save SavedPlace(label, returned coordinates). */
    @Synchronized
    fun capture(): dev.pleos.starter.teum.preferences.PlaceCoordinates? =
        (refresh() as? LocationState.Ready)?.fix?.coordinates()

    /** User-requested explanation only; this popup does not grant privacy access. */
    @Synchronized
    fun showAccessBlockedExplanation() {
        sdk?.showLocationAccessBlockedPopup()
    }

    @Synchronized
    private fun receive(epoch: Long, data: LocationData?) {
        if (epoch != generation || sdk == null) return
        if (!permissionGranted()) {
            refresh()
            return
        }
        val fix = data?.let {
            LocationFix(it.latitude, it.longitude, it.confidence, it.timestampMillis)
        }
        // A late last-known response must not replace a newer subscribed fix.
        val previous = latest
        if (fix != null && previous != null && previous.error(clock.millis()) == null &&
            fix.timestampMillis < previous.timestampMillis) return
        latest = fix
        if (fix == null) {
            publish(LocationState.Missing)
        } else {
            privacy = LocationPrivacy.DELIVERY_OBSERVED
            publish(fix.error(clock.millis())?.let(LocationState::Invalid) ?: LocationState.Ready(fix))
        }
    }

    @Synchronized
    private fun failure(epoch: Long, exception: Exception) {
        if (epoch != generation || sdk == null) return
        latest = null
        privacy = LocationPrivacy.ACCESS_ERROR
        publish(if (permissionGranted()) LocationState.Failed(exception)
            else LocationState.PermissionDenied)
    }

    private fun publish(value: LocationState) {
        state = value
        changed(value)
    }

    @Synchronized
    override fun close() {
        generation++
        val service = sdk
        val callback = listener
        sdk = null
        listener = null
        latest = null
        privacy = LocationPrivacy.UNKNOWN
        try {
            if (service != null && callback != null) service.unregisterFusedLocationCallback(callback)
        } catch (exception: Exception) {
            publish(LocationState.Failed(exception))
        } finally {
            try {
                service?.release()
            } catch (exception: Exception) {
                publish(LocationState.Failed(exception))
            } finally {
                publish(LocationState.Stopped)
            }
        }
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
