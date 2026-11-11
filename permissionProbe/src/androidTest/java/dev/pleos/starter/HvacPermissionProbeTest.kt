package dev.pleos.starter

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HvacPermissionProbeTest {
    @SuppressLint("MissingPermission")
    @Test
    fun unpermittedTemperatureControlIsRejectedOnEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val qemu: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        val isEmulator = FileInputStream(qemu.fileDescriptor).bufferedReader().use { it.readText().trim() }
        qemu.close()
        assertEquals("Probe must never run on a physical vehicle", "1", isEmulator)
        val context = instrumentation.targetContext
        val permission = "pleos.car.permission.CONTROL_CAR_CLIMATE"
        assertEquals("Control permission must remain denied", PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
        val vehicle = Vehicle(context)
        val area = SeatArea(SeatValue.ROW_1_LEFT)
        val unit = TemperatureUnit.CELSIUS
        try {
            vehicle.initialize()
            val readDone = CountDownLatch(1)
            vehicle.getHVAC().getHvacTemperature(area, unit, {
                Log.i("TeumProbe", "read_before value=$it")
                readDone.countDown()
            }, {
                Log.i("TeumProbe", "read_before error=${it.javaClass.name}: ${it.message}")
                readDone.countDown()
            })
            if (!readDone.await(15, TimeUnit.SECONDS)) Log.w("TeumProbe", "read_before timeout")

            val gearDone = CountDownLatch(1)
            vehicle.getGear().getCurrentGearSelectionState({
                Log.i("TeumProbe", "gear value=$it")
                gearDone.countDown()
            }, {
                Log.i("TeumProbe", "gear error=${it.javaClass.name}: ${it.message}")
                gearDone.countDown()
            })
            if (!gearDone.await(15, TimeUnit.SECONDS)) Log.w("TeumProbe", "gear timeout")

            val done = CountDownLatch(1)
            var result = "timeout"
            try {
                vehicle.getHVAC().setHvacTemperature(area, 22f, unit, { _, value ->
                    result = "success value=$value"
                    Log.i("TeumProbe", "setter $result")
                    done.countDown()
                }, {
                    result = "error ${it.javaClass.name}: ${it.message}"
                    Log.i("TeumProbe", "setter $result")
                    done.countDown()
                })
                done.await(15, TimeUnit.SECONDS)
            } catch (e: SecurityException) {
                result = "error ${e.javaClass.name}: ${e.message}"
                Log.i("TeumProbe", "setter $result")
            }
            val afterDone = CountDownLatch(1)
            vehicle.getHVAC().getHvacTemperature(area, unit, {
                Log.i("TeumProbe", "read_after value=$it")
                afterDone.countDown()
            }, {
                Log.i("TeumProbe", "read_after error=${it.javaClass.name}: ${it.message}")
                afterDone.countDown()
            })
            if (!afterDone.await(15, TimeUnit.SECONDS)) Log.w("TeumProbe", "read_after timeout")
            assertTrue("Expected a permission-specific rejection, observed: $result",
                result.contains("SecurityException") || result.contains("permission", ignoreCase = true))
        } finally {
            vehicle.release()
        }
    }
}
