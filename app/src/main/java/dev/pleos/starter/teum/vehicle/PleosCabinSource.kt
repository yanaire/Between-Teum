package dev.pleos.starter.teum.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission

/**
 * Main-thread read-only owner. Every SDK callback returns through its generation
 * and permission gate; the controller also gates delivery on its worker.
 */
class PleosCabinSource(
    context: Context,
    private val changed: (Long, Map<String, CabinObservation>, List<String>) -> Unit
) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = CabinCallbackEpoch()
    private var sdk: Vehicle? = null
    private var active = false
    private var grants = emptyMap<String, Boolean>()
    private var observations = emptyMap<String, CabinObservation>()
    private val registrations = mutableListOf<AutoCloseable>()
    private var cleanupErrors = emptyList<String>()
    @Volatile var revision = 0L
        private set

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        close()
        active = true
        grants = permissions.mapValues { granted(it.value) }
        observations = grants.mapValues { (_, allowed) ->
            CabinObservation(if (allowed) CabinReadStatus.PENDING else CabinReadStatus.DENIED,
                permissionGranted = allowed)
        }
        emit()
        if (grants.values.none { it }) return
        val epoch = callbacks.current()
        try {
            val service = Vehicle(context)
            sdk = service
            service.initialize()
            if (grants["seat"] == true) observeSeat(service, epoch)
            if (grants["windows"] == true) observeWindows(service, epoch)
        } catch (error: Exception) {
            grants.filterValues { it }.keys.forEach { failed(it, error) }
            dispose()
            emit()
        }
    }

    /** Permission transitions are the only automatic restart, never SDK errors. */
    fun refresh() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active && permissions.mapValues { granted(it.value) } != grants) start()
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun receive(epoch: Long, key: String, action: () -> Unit) {
        main.post {
            if (!active || !callbacks.accepts(epoch) || sdk == null) return@post
            if (!granted(permissions.getValue(key))) {
                refresh()
                return@post
            }
            action()
            emit()
        }
    }

    private fun emit() {
        revision++
        changed(revision, observations.toMap(), cleanupErrors.toList())
    }

    private fun failed(key: String, error: Exception) {
        observations = observations + (key to CabinObservation(
            if (error is SecurityException || !granted(permissions.getValue(key)))
                CabinReadStatus.DENIED else CabinReadStatus.FAILED,
            error = "${error.javaClass.simpleName}: ${error.message}",
            permissionGranted = granted(permissions.getValue(key))
        ))
    }

    private fun capability(key: String, access: AccessType, areas: Map<String, CabinAreaObservation>): Boolean {
        val status = when {
            areas.isEmpty() || access == AccessType.NONE -> CabinReadStatus.UNSUPPORTED
            access == AccessType.WRITE -> CabinReadStatus.UNREADABLE
            else -> CabinReadStatus.READABLE
        }
        observations = observations + (key to CabinObservation(status, access.name, areas, permissionGranted = true))
        return status == CabinReadStatus.READABLE
    }

    private fun position(key: String, area: String, value: Int?, error: Exception? = null) {
        if (error is SecurityException || !granted(permissions.getValue(key))) {
            failed(key, error ?: SecurityException("Read permission revoked"))
            return
        }
        val feature = observations.getValue(key)
        if (feature.status != CabinReadStatus.READABLE) return
        val previous = feature.areas.getValue(area)
        val updated = previous.copy(
            rawPosition = value,
            status = when {
                error != null -> CabinPositionStatus.FAILED
                value == null -> CabinPositionStatus.MISSING
                else -> CabinPositionStatus.OBSERVED
            },
            error = error?.let { "${it.javaClass.simpleName}: ${it.message}" }
        )
        observations = observations + (key to feature.copy(areas = feature.areas + (area to updated)))
    }

    @RequiresPermission(value = SEATS, conditional = true)
    private fun observeSeat(service: Vehicle, epoch: Long) {
        val seat = service.getSeat()
        try {
            if (context.checkSelfPermission(SEATS) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Missing $SEATS")
            seat.checkSeatBackrestAnglePositionCapabilities({ config, bounds ->
                receive(epoch, "seat") {
                    val areas = bounds.mapKeys { (area, _) -> area.values.joinToString("+") { it.name } }
                        .mapValues { (id, range) -> CabinAreaObservation(id.split("+"), range.minValue, range.maxValue) }
                    if (capability("seat", config.accessType, areas)) bounds.keys.forEach { area ->
                        val id = area.values.joinToString("+") { it.name }
                        var updates = 0L
                        val listener = object : SeatBackrestAnglePositionListener {
                            override fun onSeatBackrestAnglePositionUpdated(value: Int?) {
                                receive(epoch, "seat") { updates++; position("seat", id, value) }
                            }
                            override fun onFailed(exception: Exception) {
                                receive(epoch, "seat") { updates++; position("seat", id, null, exception) }
                            }
                        }
                        try {
                            if (context.checkSelfPermission(SEATS) != PackageManager.PERMISSION_GRANTED)
                                throw SecurityException("Missing $SEATS")
                            // Retain disposal even if registration throws after partial IPC work.
                            registrations += AutoCloseable { seat.unregisterSeatBackrestAnglePosition(area, listener) }
                            seat.registerSeatBackrestAnglePosition(area, listener)
                            seat.getCurrentSeatBackrestAnglePosition(area, { value ->
                                receive(epoch, "seat") { if (updates == 0L) position("seat", id, value) }
                            }, { error ->
                                receive(epoch, "seat") { if (updates == 0L) position("seat", id, null, error) }
                            })
                        } catch (error: Exception) { position("seat", id, null, error) }
                    }
                }
            }, { error -> receive(epoch, "seat") { failed("seat", error) } })
        } catch (error: Exception) { failed("seat", error); emit() }
    }

    @RequiresPermission(value = WINDOWS, conditional = true)
    private fun observeWindows(service: Vehicle, epoch: Long) {
        val windows = service.getWindow()
        try {
            if (context.checkSelfPermission(WINDOWS) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Missing $WINDOWS")
            windows.checkWindowPositionCapabilities({ config, bounds ->
                receive(epoch, "windows") {
                    val areas = bounds.mapKeys { (area, _) -> area.values.joinToString("+") { it.name } }
                        .mapValues { (id, range) -> CabinAreaObservation(id.split("+"), range.minValue, range.maxValue) }
                    if (capability("windows", config.accessType, areas)) bounds.keys.forEach { area ->
                        val id = area.values.joinToString("+") { it.name }
                        var updates = 0L
                        val listener = object : WindowPositionListener {
                            override fun onWindowPositionUpdated(value: Int?) {
                                receive(epoch, "windows") { updates++; position("windows", id, value) }
                            }
                            override fun onFailed(exception: Exception) {
                                receive(epoch, "windows") { updates++; position("windows", id, null, exception) }
                            }
                        }
                        try {
                            if (context.checkSelfPermission(WINDOWS) != PackageManager.PERMISSION_GRANTED)
                                throw SecurityException("Missing $WINDOWS")
                            registrations += AutoCloseable { windows.unregisterWindowPosition(area, listener) }
                            windows.registerWindowPosition(area, listener)
                            windows.getCurrentWindowPosition(area, { value ->
                                receive(epoch, "windows") { if (updates == 0L) position("windows", id, value) }
                            }, { error ->
                                receive(epoch, "windows") { if (updates == 0L) position("windows", id, null, error) }
                            })
                        } catch (error: Exception) { position("windows", id, null, error) }
                    }
                }
            }, { error -> receive(epoch, "windows") { failed("windows", error) } })
        } catch (error: Exception) { failed("windows", error); emit() }
    }

    private fun dispose() {
        callbacks.invalidate()
        registrations.toList().forEach { registration ->
            try { registration.close() }
            catch (error: Exception) {
                cleanupErrors = cleanupErrors + "Unregister ${error.javaClass.simpleName}: ${error.message}"
            }
        }
        registrations.clear()
        val service = sdk
        sdk = null
        try { service?.release() }
        catch (error: Exception) {
            cleanupErrors = cleanupErrors + "Release ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active = false
        dispose()
        observations = permissions.mapValues { CabinObservation() }
        emit()
    }

    companion object {
        const val SEATS = "pleos.car.permission.CAR_SEATS"
        const val WINDOWS = "pleos.car.permission.CAR_WINDOWS"
        private val permissions = mapOf("seat" to SEATS, "windows" to WINDOWS)
    }
}
