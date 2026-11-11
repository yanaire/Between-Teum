package dev.pleos.starter.teum.ui

import dev.pleos.starter.teum.records.InMemoryRecordStore
import dev.pleos.starter.teum.records.RecordsRepository
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.vehicle.*

internal class CoordinatorClock : SessionClock {
    var now = ClockReading(1_700_000_000_000, 12_345)
    override fun reading() = now
}

internal class MemorySessionMarkers : SessionMarkers {
    var raw: String? = null
    override fun load() = SessionSnapshotCodec.decode(raw)
    override fun save(snapshot: SessionSnapshot) { raw = SessionSnapshotCodec.encode(snapshot) }
    override fun clear() { raw = null }
}

internal class CoordinatorPort : VehicleSettingsPort {
    val deferred = mutableListOf<() -> Unit>()
    val sent = mutableListOf<Double>()
    var inspections = 0
    var current = 22.0
    var writable = true
    var closed = false
    private val observers = mutableListOf<(Double?, ChangeOrigin) -> Unit>()
    override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
        inspections++
        result(Result.success(SettingInspection(
            SettingCapability(true, writable, 16.0, 30.0, 0.5, "test"), current
        )))
    }
    override fun subscribe(setting: VehicleSetting, changed: (Double?, ChangeOrigin) -> Unit): AutoCloseable {
        observers += changed
        return AutoCloseable { observers.remove(changed) }
    }
    override fun write(setting: VehicleSetting, value: Double, permit: () -> Boolean, result: (Result<Unit>) -> Unit) {
        deferred += {
            if (permit()) {
                sent += value
                current = value
                result(Result.success(Unit))
            } else result(Result.failure(IllegalStateException("cancelled")))
        }
    }
    fun manualChange(value: Double) {
        current = value
        observers.toList().forEach { it(value, ChangeOrigin.MANUAL) }
    }
    override fun close() { closed = true }
}

internal class CoordinatorFixture(val situation: String = "lunch-break") : AutoCloseable {
    val clock = CoordinatorClock()
    val markers = MemorySessionMarkers()
    val repo = RecordsRepository(InMemoryRecordStore())
    val port = CoordinatorPort()
    val effects = mutableListOf<TeumSessionEffect>()
    val owner = TeumSessionCoordinator(repo, markers, clock).also { it.onEffect = effects::add }
    val state get() = owner.state
    init {
        owner.onCreate()
        owner.onStart(port)
        freshSafety()
        send(TeumSessionAction.OpenSetup(situation))
    }
    fun send(action: TeumSessionAction) = owner.dispatch(action)
    fun freshSafety() {
        val now = clock.now.elapsedMillis
        owner.onSafety(VehicleSafety(true, ParkingObservation(Parking.PARKED, now, "test"),
            UxObservation(false, 0, now), 1))
    }
    fun previewAndConsent() {
        send(TeumSessionAction.PreviewFeature(VehicleFeature.HVAC_TEMPERATURE, 24.0))
        send(TeumSessionAction.ApplyConsent(VehicleFeature.HVAC_TEMPERATURE, true))
    }
    fun tick(now: ClockReading) {
        clock.now = now
        freshSafety()
        send(TeumSessionAction.Tick)
    }
    fun approveRecord() {
        freshSafety()
        send(TeumSessionAction.WakeAcknowledgement(true))
        send(TeumSessionAction.RestoreConsent(true))
        send(TeumSessionAction.ManualRestoreAck(true))
        send(TeumSessionAction.ConfirmRestore)
    }
    override fun close() = owner.onDestroy()
}
