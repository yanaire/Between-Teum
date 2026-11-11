package dev.pleos.starter.teum.ui

import android.os.SystemClock
import android.view.ViewGroup
import dev.pleos.starter.R
import dev.pleos.starter.teum.records.RecordsRepository
import dev.pleos.starter.teum.records.SharedPreferencesRecordStore
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.vehicle.*

internal class NativeCoordinatorClock : SessionClock {
    var fixed: ClockReading? = null
    override fun reading() = fixed ?: ClockReading(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    override fun startReading(): ClockReading {
        fixed?.let { return it }
        val elapsed = SystemClock.elapsedRealtime()
        return ClockReading(System.currentTimeMillis(), elapsed)
    }
}

internal class NativeDeferredPort : VehicleSettingsPort {
    val deferred = mutableListOf<() -> Unit>()
    val sent = mutableListOf<Double>()
    var inspections = 0
    var current = 22.0
    var writable = true
    override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
        inspections++
        result(Result.success(SettingInspection(
            SettingCapability(true, writable, 16.0, 30.0, 0.5, "test"), current
        )))
    }
    override fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit) =
        AutoCloseable {}
    override fun write(setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit) {
        deferred += {
            if (permit()) {
                sent += value
                current = value
                result(Result.success(Unit))
            } else result(Result.failure(IllegalStateException("cancelled")))
        }
    }
    override fun close() = Unit
}

/** Tests drive the production coordinator and renderer in their dedicated debug host. */
internal class NativeCoordinatorFixture(activity: NativeRegressionActivity) {
    val clock = NativeCoordinatorClock()
    val markers = SessionSnapshotStore(activity)
    val port = NativeDeferredPort()
    val owner = TeumSessionCoordinator(
        RecordsRepository(SharedPreferencesRecordStore(activity)), markers, clock
    )
    val root = activity.findViewById<ViewGroup>(R.id.teum_root)
    val controller: TeumUiController
    val state get() = owner.state
    init {
        controller = activity.installCoordinator(owner)
        owner.onStart(port)
        freshSafety()
    }
    fun send(action: TeumSessionAction) = owner.dispatch(action)
    fun setup(situation: String = "lunch-break") = send(TeumSessionAction.OpenSetup(situation))
    fun preview() = send(TeumSessionAction.PreviewFeature(VehicleFeature.HVAC_TEMPERATURE, 24.0))
    fun consent() = send(TeumSessionAction.ApplyConsent(VehicleFeature.HVAC_TEMPERATURE, true))
    fun start(): ClockReading {
        clock.fixed = clock.reading()
        freshSafety()
        send(TeumSessionAction.Start)
        return clock.reading()
    }
    fun tick(now: ClockReading) {
        clock.fixed = now
        freshSafety()
        send(TeumSessionAction.Tick)
    }
    fun freshSafety() {
        val now = clock.reading().elapsedMillis
        owner.onSafety(VehicleSafety(true, ParkingObservation(Parking.PARKED, now, "test"),
            UxObservation(false, 0, now), 1))
    }
    fun approveRecord() {
        send(TeumSessionAction.WakeAcknowledgement(true))
        send(TeumSessionAction.RestoreConsent(true))
        send(TeumSessionAction.ManualRestoreAck(true))
        send(TeumSessionAction.ConfirmRestore)
    }
}
