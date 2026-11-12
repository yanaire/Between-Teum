package dev.pleos.starter.teum.vehicle

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class NativeSetterExclusionTest {
    private fun setting(tag: String) = VehicleSetting(VehicleFeature.WINDOW_POSITION, tag)

    @Test fun duplicateRejectedWhileFirstSetterLive() {
        val lifecycle = PortLifecycle()
        val setting = setting("EXCL_DUP_A")
        val first = NativeSetterExclusion.tryBegin(setting, lifecycle)
        assertNotNull(first)
        try {
            assertNull(NativeSetterExclusion.tryBegin(setting, lifecycle))
        } finally {
            NativeSetterExclusion.settle(setting, first)
        }
        val second = NativeSetterExclusion.tryBegin(setting, lifecycle)
        assertNotNull(second)
        NativeSetterExclusion.settle(setting, second)
    }

    @Test fun logicalCloseWithoutNativeSettleKeepsExclusion() {
        val lifecycle = PortLifecycle()
        var releases = 0
        lifecycle.onRelease = { releases++ }
        val setting = setting("EXCL_TIMEOUT_A")
        val first = NativeSetterExclusion.tryBegin(setting, lifecycle)
        assertNotNull(first)
        try {
            lifecycle.close()
            assertEquals(0, releases)
            assertEquals(1, lifecycle.nativeInflightCount())
            assertNull(NativeSetterExclusion.tryBegin(setting, lifecycle))
            assertEquals(0, releases)
        } finally {
            NativeSetterExclusion.settle(setting, first)
        }
    }

    @Test fun secondSetterAdmittedOnlyAfterFirstRealSettle() {
        val lifecycle = PortLifecycle()
        val setting = setting("EXCL_ORDER_A")
        val first = NativeSetterExclusion.tryBegin(setting, lifecycle)
        assertNotNull(first)
        assertNull(NativeSetterExclusion.tryBegin(setting, lifecycle))
        NativeSetterExclusion.settle(setting, first)
        val second = NativeSetterExclusion.tryBegin(setting, lifecycle)
        assertNotNull(second)
        NativeSetterExclusion.settle(setting, second)
    }

    @Test fun exclusionSpansPortsUntilRealSettle() {
        val firstPort = PortLifecycle()
        var firstReleases = 0
        firstPort.onRelease = { firstReleases++ }
        val secondPort = PortLifecycle()
        val setting = setting("EXCL_XPORT_A")
        val first = NativeSetterExclusion.tryBegin(setting, firstPort)
        assertNotNull(first)
        try {
            assertNull(NativeSetterExclusion.tryBegin(setting, secondPort))
            firstPort.close()
            assertEquals(0, firstReleases)
            assertNull(NativeSetterExclusion.tryBegin(setting, secondPort))
        } finally {
            NativeSetterExclusion.settle(setting, first)
        }
        val second = NativeSetterExclusion.tryBegin(setting, secondPort)
        assertNotNull(second)
        NativeSetterExclusion.settle(setting, second)
    }

    @Test fun concurrentDuplicatesAdmitExactlyOne() {
        val lifecycle = PortLifecycle()
        val setting = setting("EXCL_RACE_A")
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val admitted = AtomicInteger(0)
        val winners = ConcurrentLinkedQueue<NativeGuard>()
        repeat(8) {
            thread {
                try {
                    start.await()
                    val guard = NativeSetterExclusion.tryBegin(setting, lifecycle)
                    if (guard != null) {
                        admitted.incrementAndGet()
                        winners.add(guard)
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await()
        try {
            assertEquals(1, admitted.get())
        } finally {
            winners.forEach { NativeSetterExclusion.settle(setting, it) }
        }
    }

    @Test fun differentSettingsAreIndependent() {
        val lifecycle = PortLifecycle()
        val first = NativeSetterExclusion.tryBegin(setting("EXCL_IND_A"), lifecycle)
        val second = NativeSetterExclusion.tryBegin(setting("EXCL_IND_B"), lifecycle)
        assertNotNull(first)
        assertNotNull(second)
        NativeSetterExclusion.settle(setting("EXCL_IND_A"), first)
        NativeSetterExclusion.settle(setting("EXCL_IND_B"), second)
    }
}
