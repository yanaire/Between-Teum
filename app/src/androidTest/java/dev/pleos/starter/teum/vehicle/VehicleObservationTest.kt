package dev.pleos.starter.teum.vehicle

import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleObservationTest {
    @Test fun localAuditCountsPositiveEventsWithoutImplyingNativeExecution() {
        val before = org.json.JSONObject(SettingsWriteAudit.record(SettingsWriteAudit.Event.HEARTBEAT))
        // Recorder validity only. No port or native setter is invoked by this test.
        for (event in listOf(SettingsWriteAudit.Event.ENTRY, SettingsWriteAudit.Event.NATIVE_SETTER,
            SettingsWriteAudit.Event.ACK, SettingsWriteAudit.Event.INSPECTION)) {
            val after = org.json.JSONObject(SettingsWriteAudit.record(event))
            assertEquals(before.getLong(event.name) + 1, after.getLong(event.name))
            assertEquals(event.name, after.getString("event"))
            assertEquals(android.os.Process.myPid(), after.getInt("pid"))
        }
    }

    @Test fun deniedNativePortWriteIsAuditedBeforeAnySetterOrPermit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val response = AtomicReference<Result<Unit>>()
        var permitCalled = false
        lateinit var port: PleosSettingsPort
        val before = org.json.JSONObject(SettingsWriteAudit.record(SettingsWriteAudit.Event.HEARTBEAT))
        try {
            instrumentation.runOnMainSync {
                port = PleosSettingsPort(instrumentation.targetContext)
                port.write(VehicleSetting(VehicleFeature.HVAC_TEMPERATURE, "ROW_1_LEFT"), 24.0,
                    { permitCalled = true; true }) {
                    response.set(it)
                    done.countDown()
                }
            }
            assertTrue("Rejected write must complete", done.await(10, TimeUnit.SECONDS))
            assertTrue(response.get().exceptionOrNull() is SecurityException)
            assertFalse(permitCalled)
            val after = org.json.JSONObject(SettingsWriteAudit.record(SettingsWriteAudit.Event.HEARTBEAT))
            assertEquals(before.getLong("ENTRY") + 1, after.getLong("ENTRY"))
            assertEquals(before.getLong("REJECTED") + 1, after.getLong("REJECTED"))
            for (counter in listOf("NATIVE_SETTER", "ACK")) {
                assertEquals(before.getLong(counter), after.getLong(counter))
            }
        } finally {
            instrumentation.runOnMainSync { port.close() }
        }
    }

    @Test fun repeatedLifecycleInterruptionsRequireNewObservationsAndCloseRevokesTrust() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val observation = AtomicReference<VehicleSafety>()
        val ready = AtomicReference<CountDownLatch>()
        lateinit var source: AaosSafetySource
        instrumentation.runOnMainSync {
            source = AaosSafetySource(instrumentation.targetContext) {
                observation.set(it)
                if (it.ux.activeRestrictions != null) ready.get()?.countDown()
            }
        }
        try {
            var generation = -1L
            repeat(3) {
                val latch = CountDownLatch(1)
                ready.set(latch)
                instrumentation.runOnMainSync { source.start() }
                assertTrue("New UX manager read on lifecycle $it", latch.await(15, TimeUnit.SECONDS))
                assertTrue(observation.get().generation > generation)
                instrumentation.runOnMainSync { source.close() }
                instrumentation.waitForIdleSync()
                val stopped = observation.get()
                assertFalse(stopped.connected)
                assertEquals(Parking.UNKNOWN, stopped.parking.state)
                assertNull(stopped.ux.activeRestrictions)
                generation = stopped.generation
            }
        } finally {
            instrumentation.runOnMainSync { source.close() }
        }
    }

    @Test fun observerClosingDuringInitialPublicationCannotStartNativeConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var calls = 0
        lateinit var source: AaosSafetySource
        instrumentation.runOnMainSync {
            source = AaosSafetySource(instrumentation.targetContext) {
                calls++
                if (calls == 1) source.close()
                assertFalse(it.connected)
            }
            source.start()
        }
        instrumentation.waitForIdleSync()
        assertEquals(2, calls)
    }

    @Test fun actualRestrictionsAreIndependentOfGearPermissionAndCloseInvalidatesObservation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val gearGranted = context.checkSelfPermission("android.car.permission.CAR_POWERTRAIN") ==
            PackageManager.PERMISSION_GRANTED
        val observed = AtomicReference<VehicleSafety>()
        val initialRead = AtomicReference<VehicleSafety>()
        val gotUx = CountDownLatch(1)
        lateinit var source: AaosSafetySource
        try {
            instrumentation.runOnMainSync {
                source = AaosSafetySource(context) {
                    observed.set(it)
                    Log.i("TeumVehicleProbe", "safety=$it")
                    if (it.ux.activeRestrictions != null &&
                        (!gearGranted || it.parking.state == Parking.PARKED)) {
                        if (initialRead.compareAndSet(null, it)) gotUx.countDown()
                    }
                }
                source.start()
            }
            assertTrue("Actual UX manager initial read must complete", gotUx.await(15, TimeUnit.SECONDS))
            val state = initialRead.get()
            assertTrue(state.connected)
            if (!gearGranted) {
                assertEquals(Parking.UNKNOWN, state.parking.state)
                assertTrue(state.parking.reason.contains("CAR_POWERTRAIN"))
                assertNotNull(state.blockedReason(android.os.SystemClock.elapsedRealtime()))
            } else {
                // This emulator boundary check runs with the vehicle in Park.
                // Only actual GEAR_SELECTION readback can satisfy it.
                assertEquals(state.parking.reason, Parking.PARKED, state.parking.state)
                assertEquals(false, state.ux.requiresDistractionOptimization)
                assertEquals(0, state.ux.activeRestrictions)
                assertNull(state.blockedReason(android.os.SystemClock.elapsedRealtime()))
            }
        } finally {
            instrumentation.runOnMainSync { source.close() }
        }
        assertFalse(observed.get().connected)
        assertEquals(Parking.UNKNOWN, observed.get().parking.state)
        assertNull(observed.get().ux.activeRestrictions)
    }

    @Test fun nativeSettingsInspectPermissionsWithoutAnyControlPermissionOrSetter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val done = CountDownLatch(2)
        val results = mutableListOf<Pair<VehicleFeature, Result<SettingInspection>>>()
        lateinit var port: PleosSettingsPort
        try {
            instrumentation.runOnMainSync {
                port = PleosSettingsPort(context)
                for (feature in listOf(VehicleFeature.HVAC_TEMPERATURE, VehicleFeature.AMBIENT_BRIGHTNESS)) {
                    port.inspect(VehicleSetting(feature, "ROW_1_LEFT")) { result ->
                        result.exceptionOrNull()?.let {
                            Log.i("TeumVehicleProbe", "$feature denied=$it")
                        }
                        results.add(feature to result)
                        done.countDown()
                    }
                }
            }
            assertTrue("Both inspection callbacks must execute", done.await(10, TimeUnit.SECONDS))
            assertEquals(2, results.size)
            results.forEach { (feature, result) ->
                val permission = if (feature == VehicleFeature.HVAC_TEMPERATURE)
                    "pleos.car.permission.CAR_CLIMATE" else "pleos.car.permission.CAR_LIGHTS"
                if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    assertTrue(result.exceptionOrNull() is SecurityException)
                } else {
                    result.getOrNull()?.let { assertFalse(it.capability.canWrite) }
                    assertTrue(result.isFailure || result.getOrThrow().capability.canRead)
                }
            }
            assertEquals(PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_CLIMATE"))
            assertEquals(PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_LIGHTS"))
        } finally {
            instrumentation.runOnMainSync { port.close() }
        }
    }
}
