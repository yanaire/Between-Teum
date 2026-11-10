package dev.pleos.starter.teum.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Own on the main thread, start in onStart and close in onStop. Every publication
 * is on that thread. A successful current read renews an unchanged ON_CHANGE
 * value; its VHAL change timestamp alone is not a freshness/connection heartbeat.
 */
class AaosSafetySource(
    private val context: Context,
    private val onObservation: (VehicleSafety) -> Unit
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var propertyListener: CarPropertyManager.CarPropertyEventCallback? = null
    private var uxListener: CarUxRestrictionsManager.OnUxRestrictionsChangedListener? = null
    private var active = false
    private var epoch = 0L
    private var state = VehicleSafety()
    private var lastGearTimestamp = -1L

    private val refresh = object : Runnable {
        override fun run() {
            if (!active) return
            if (car?.isConnected == true) {
                readGear()
                readUx()
            } else {
                publish(VehicleSafety(generation = epoch))
            }
            if (active) handler.postDelayed(this, 1_000)
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active) return
        active = true
        val started = ++epoch
        publish(VehicleSafety(generation = epoch))
        if (!active || started != epoch) return
        try {
            val created = Car.createCar(context.applicationContext, handler,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!active || started != epoch) return@createCar
                detachListeners()
                if (!ready) {
                    active = false
                    handler.removeCallbacks(refresh)
                    connectedCar.disconnect()
                    car = null
                    publish(VehicleSafety(generation = ++epoch))
                    return@createCar
                }
                car = connectedCar
                lastGearTimestamp = -1
                publish(VehicleSafety(connected = true, generation = epoch))
                if (!active || started != epoch) return@createCar
                attachProperties(connectedCar, started)
                if (!active || started != epoch) return@createCar
                attachRestrictions(connectedCar, started)
                readGear()
                readUx()
            }
            if (active && started == epoch) {
                car = created
                handler.post(refresh)
            } else {
                created?.disconnect()
            }
        } catch (e: RuntimeException) {
            active = false
            detachListeners()
            car?.disconnect()
            car = null
            publish(VehicleSafety(parking = ParkingObservation(reason = error(e)), generation = epoch))
        }
    }

    private fun attachProperties(connectedCar: Car, token: Long) {
        if (context.checkSelfPermission(Car.PERMISSION_POWERTRAIN) != PackageManager.PERMISSION_GRANTED) {
            publish(state.copy(parking = ParkingObservation(reason = "Missing ${Car.PERMISSION_POWERTRAIN}")))
            return
        }
        try {
            val manager = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                ?: throw IllegalStateException("Property manager unavailable")
            properties = manager
            val config = manager.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION)
            if (config == null || !config.configArray.contains(VehicleGear.GEAR_PARK)) {
                throw IllegalStateException("GEAR_SELECTION with Park unsupported")
            }
            val listener = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (active && epoch == token && car?.isConnected == true &&
                        value.propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                        acceptGear(value)
                    }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    if (active && epoch == token && propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                        publish(state.copy(parking = ParkingObservation(reason = "Gear property error")))
                    }
                }
            }
            propertyListener = listener
            // Available on target API 34. API 35 subscribePropertyEvents is not assumed at runtime.
            if (!manager.registerCallback(listener, VehiclePropertyIds.GEAR_SELECTION,
                    CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                throw IllegalStateException("Gear subscription rejected")
            }
        } catch (e: RuntimeException) {
            properties?.let { manager -> propertyListener?.let { listener ->
                try { manager.unregisterCallback(listener) } catch (cleanup: RuntimeException) {
                    android.util.Log.w("TeumVehicle", "Gear cleanup failed", cleanup)
                }
            } }
            properties = null
            propertyListener = null
            publish(state.copy(parking = ParkingObservation(reason = error(e))))
        }
    }

    private fun attachRestrictions(connectedCar: Car, token: Long) {
        try {
            val manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                ?: throw IllegalStateException("UX restrictions manager unavailable")
            restrictions = manager
            val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
                if (active && epoch == token && car?.isConnected == true) {
                    // The public API has no event timestamp. Obtain a current read instead of
                    // renewing trust from a potentially queued unrestricted callback.
                    readUx()
                }
            }
            uxListener = listener
            manager.registerListener(listener)
        } catch (e: RuntimeException) {
            try { restrictions?.unregisterListener() }
            catch (cleanup: RuntimeException) { android.util.Log.w("TeumVehicle", "UX cleanup failed", cleanup) }
            restrictions = null
            uxListener = null
            publish(state.copy(ux = UxObservation(reason = error(e))))
        }
    }

    private fun readGear() {
        val manager = properties ?: return
        try {
            acceptGear(manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0), currentRead = true)
        } catch (e: RuntimeException) {
            publish(state.copy(parking = ParkingObservation(reason = error(e))))
        }
    }

    private fun acceptGear(value: CarPropertyValue<*>?, currentRead: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (value == null || value.status != CarPropertyValue.STATUS_AVAILABLE ||
            value.areaId != 0 || value.timestamp < 0 ||
            value.timestamp < lastGearTimestamp || value.timestamp > SystemClock.elapsedRealtimeNanos() ||
            (!currentRead && !VehicleSafety.fresh(value.timestamp / 1_000_000, now))) {
            publish(state.copy(parking = ParkingObservation(reason = "Unavailable or out-of-order gear")))
            return
        }
        lastGearTimestamp = value.timestamp
        val gear = value.value as? Int
        val parking = when (gear) {
            VehicleGear.GEAR_PARK -> Parking.PARKED
            VehicleGear.GEAR_NEUTRAL, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_DRIVE,
            VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD,
            VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH,
            VehicleGear.GEAR_SEVENTH, VehicleGear.GEAR_EIGHTH, VehicleGear.GEAR_NINTH -> Parking.NOT_PARKED
            else -> Parking.UNKNOWN
        }
        publish(state.copy(parking = ParkingObservation(parking, now,
            if (parking == Parking.PARKED) "GEAR_SELECTION is Park" else "Gear is not verified Park")))
    }

    private fun readUx() {
        val manager = restrictions ?: return
        try { acceptUx(manager.currentCarUxRestrictions) }
        catch (e: RuntimeException) { publish(state.copy(ux = UxObservation(reason = error(e)))) }
    }

    private fun acceptUx(value: CarUxRestrictions?) {
        publish(state.copy(ux = if (value == null) UxObservation() else UxObservation(
            value.isRequiresDistractionOptimization, value.activeRestrictions,
            SystemClock.elapsedRealtime(), "CarUxRestrictionsManager")))
    }

    private fun publish(next: VehicleSafety) {
        state = next
        onObservation(next)
    }

    private fun detachListeners() {
        try { properties?.let { manager -> propertyListener?.let { manager.unregisterCallback(it) } } }
        catch (e: RuntimeException) { android.util.Log.w("TeumVehicle", "Gear unregister failed", e) }
        try { if (uxListener != null) restrictions?.unregisterListener() }
        catch (e: RuntimeException) { android.util.Log.w("TeumVehicle", "UX unregister failed", e) }
        properties = null
        restrictions = null
        propertyListener = null
        uxListener = null
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active = false
        ++epoch
        handler.removeCallbacksAndMessages(null)
        detachListeners()
        try { car?.disconnect() }
        finally {
            car = null
            publish(VehicleSafety(generation = epoch))
        }
    }

    private fun error(e: RuntimeException) = "${e.javaClass.simpleName}: ${e.message}"
}
