package dev.pleos.starter.teum.ui

import android.os.SystemClock
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pleos.starter.MainActivity
import dev.pleos.starter.R
import dev.pleos.starter.teum.vehicle.*
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.session.Schedule
import dev.pleos.starter.teum.session.SessionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerVehicleRegressionTest {
    @Before @After fun clearState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("teum_records_private", "teum_session_v1", "teum_ui_v1").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private class DeferredPort : VehicleSettingsPort {
        val deferred = mutableListOf<() -> Unit>()
        val sent = mutableListOf<Double>()
        var inspections = 0
        var current = 22.0
        override fun inspect(setting: VehicleSetting, result: (Result<SettingInspection>) -> Unit) {
            inspections++
            result(Result.success(SettingInspection(
                SettingCapability(true, true, 16.0, 30.0, 0.5, "test"), current
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
        override fun close() {}
    }

    private fun field(obj: Any, name: String): java.lang.reflect.Field =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun call(obj: Any, name: String, types: Array<Class<*>>, vararg args: Any) {
        obj.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(obj, *args)
    }

    private fun setup(activity: MainActivity, port: DeferredPort, situation: String = "lunch-break"): TeumUiController {
        val controller = field(activity, "ui").get(activity) as TeumUiController
        (field(controller, "adapter").get(controller) as? SafeVehicleAdapter)?.close()
        val now = SystemClock.elapsedRealtime()
        val safety = VehicleSafety(true, ParkingObservation(Parking.PARKED, now, "test"),
            UxObservation(false, 0, now), 1)
        val adapter = SafeVehicleAdapter(port, SystemClock::elapsedRealtime) {
            call(controller, "onCommand", arrayOf(VehicleCommand::class.java), it)
        }
        field(controller, "adapter").set(controller, adapter)
        call(controller, "onSafety", arrayOf(VehicleSafety::class.java), safety)
        activity.findViewById<ViewGroup>(R.id.teum_root)
            .findViewWithTag<View>("situation_$situation").performClick()
        return controller
    }

    private fun preview(controller: TeumUiController) =
        call(controller, "previewFeature", arrayOf(VehicleFeature::class.java, Double::class.javaPrimitiveType!!),
            VehicleFeature.HVAC_TEMPERATURE, 24.0)

    private fun timingSession(controller: TeumUiController): Pair<Any, ClockReading> {
        call(controller, "startSession", emptyArray())
        call(controller, "stopTimer", emptyArray())
        val session = requireNotNull(field(controller, "active").get(controller))
        val original = field(session, "schedule").get(session) as Schedule
        val totalMillis = (original.prepareSeconds + original.activitySeconds + original.finishSeconds) * 1000
        val start = ClockReading(System.currentTimeMillis() - totalMillis - 10_000, 12_345)
        val schedule = original.copy(startWallMillis = start.wallMillis,
            deadlineWallMillis = start.wallMillis + totalMillis + original.travelSeconds * 1000)
        field(session, "schedule").set(session, schedule)
        field(session, "startedElapsedMillis").setLong(session, start.elapsedMillis)
        field(session, "startedWallMillis").setLong(session, start.wallMillis)
        field(session, "engine").set(session, SessionEngine.start(schedule, start, true))
        return session to start
    }

    private fun timingTick(controller: TeumUiController, now: ClockReading) {
        field(controller, "safety").set(controller, VehicleSafety(true,
            ParkingObservation(Parking.PARKED, now.elapsedMillis, "test"),
            UxObservation(false, 0, now.elapsedMillis), 1))
        call(controller, "tick", arrayOf(ClockReading::class.java), now)
    }

    @Test fun readOnlyInspectionOffersManualBadgeWithoutWriteControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = setup(activity, DeferredPort())
                val constructor = Class.forName("dev.pleos.starter.teum.ui.FeatureUi\$Ready")
                    .declaredConstructors.single().apply { isAccessible = true }
                val readOnly = constructor.newInstance(22.0,
                    SettingCapability(true, false, 16.0, 30.0, 0.5, "read-only"))
                val statuses = field(controller, "featureStatus").get(controller)
                for (feature in listOf(VehicleFeature.HVAC_TEMPERATURE, VehicleFeature.AMBIENT_BRIGHTNESS)) {
                    statuses.javaClass.getMethod("put", Any::class.java, Any::class.java)
                        .invoke(statuses, feature, readOnly)
                }
                call(controller, "render", emptyArray())
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.teum_root)
                for (feature in listOf("hvac", "ambient")) {
                    assertEquals("수동", root.findViewWithTag<TextView>("${feature}_pill").text.toString())
                    assertEquals(null, root.findViewWithTag<View>("${feature}_plus"))
                    assertEquals(null, root.findViewWithTag<View>("${feature}_preview"))
                }
            }
        }
    }

    @Test fun changingToShortInvalidatesFullPreviewAndRejectsNewPreview() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val port = DeferredPort()
                val controller = setup(activity, port)
                preview(controller)
                assertTrue(port.inspections > 0)
                activity.findViewById<ViewGroup>(R.id.teum_root).findViewWithTag<View>("quick_10").performClick()
                val count = port.inspections
                preview(controller)
                assertEquals(count, port.inspections)
                activity.findViewById<ViewGroup>(R.id.teum_root).findViewWithTag<View>("short_confirm").performClick()
                call(controller, "startSession", emptyArray())
                assertTrue(port.deferred.isEmpty())
                assertTrue(port.sent.isEmpty())
            }
        }
    }

    @Test fun earlyFinishRevokesADeferredRealAdapterWrite() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val port = DeferredPort()
                val controller = setup(activity, port)
                preview(controller)
                val consent = field(controller, "applyConsent").get(controller) as MutableMap<*, *>
                consent.javaClass.getMethod("put", Any::class.java, Any::class.java)
                    .invoke(consent, VehicleFeature.HVAC_TEMPERATURE, true)
                call(controller, "startSession", emptyArray())
                assertEquals(1, port.deferred.size)
                call(controller, "finishNow", emptyArray())
                port.deferred.single().invoke()
                assertTrue(port.sent.isEmpty())
            }
        }
    }

    @Test fun lunchFeatureRestoreRequiresWakeConsentAndFreshSafety() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val port = DeferredPort()
                val controller = setup(activity, port)
                preview(controller)
                val consent = field(controller, "applyConsent").get(controller) as MutableMap<*, *>
                consent.javaClass.getMethod("put", Any::class.java, Any::class.java)
                    .invoke(consent, VehicleFeature.HVAC_TEMPERATURE, true)
                call(controller, "startSession", emptyArray())
                port.deferred.removeAt(0).invoke()
                assertEquals(listOf(24.0), port.sent)
                call(controller, "finishNow", emptyArray())
                val feature = VehicleFeature.HVAC_TEMPERATURE
                val type = arrayOf<Class<*>>(VehicleFeature::class.java)
                val inspections = port.inspections
                call(controller, "previewRestore", type, feature)
                assertEquals(inspections, port.inspections)
                field(controller, "awakeAcknowledged").setBoolean(controller, true)
                call(controller, "previewRestore", type, feature)
                assertEquals(inspections, port.inspections)
                field(controller, "restoreConsent").setBoolean(controller, true)
                call(controller, "previewRestore", type, feature)
                assertEquals(inspections + 1, port.inspections)
                val origin = field(controller, "restoreOriginAck").get(controller) as MutableMap<*, *>
                origin.javaClass.getMethod("put", Any::class.java, Any::class.java).invoke(origin, feature, true)
                field(controller, "restoreConsent").setBoolean(controller, false)
                call(controller, "confirmRestoreFeature", type, feature)
                assertTrue(port.deferred.isEmpty())
                field(controller, "restoreConsent").setBoolean(controller, true)
                field(controller, "awakeAcknowledged").setBoolean(controller, false)
                call(controller, "confirmRestoreFeature", type, feature)
                assertTrue(port.deferred.isEmpty())
                field(controller, "awakeAcknowledged").setBoolean(controller, true)
                call(controller, "onSafety", arrayOf(VehicleSafety::class.java), VehicleSafety())
                call(controller, "confirmRestoreFeature", type, feature)
                assertTrue(port.deferred.isEmpty())
                assertEquals(listOf(24.0), port.sent)
            }
        }
    }

    @Test fun wallDeadlineRunsFinishWithoutInventingActivityProgress() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = setup(activity, DeferredPort())
                call(controller, "startSession", emptyArray())
                val session = requireNotNull(field(controller, "active").get(controller))
                val original = field(session, "schedule").get(session) as Schedule
                val wall = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val schedule = original.copy(startWallMillis = wall,
                    deadlineWallMillis = wall + (original.finishSeconds + original.travelSeconds) * 1000)
                field(session, "schedule").set(session, schedule)
                field(session, "engine").set(session, SessionEngine.start(
                    schedule, ClockReading(wall, elapsed), shortConfirmed = true
                ))
                call(controller, "tick", emptyArray())
                val segments = (field(session, "segments").get(session) as List<*>)
                    .filterIsInstance<Segment>()
                val title = activity.findViewById<ViewGroup>(R.id.teum_root)
                    .findViewWithTag<TextView>("session_step_title")
                assertEquals(segments.last().title, title.text.toString())
                val progress = field(session, "elapsedMillis").getLong(session)
                field(session, "deadlineFinishStartedMillis").set(session, progress - schedule.finishSeconds * 1000)
                call(controller, "tick", emptyArray())
                field(controller, "awakeAcknowledged").setBoolean(controller, true)
                field(controller, "restoreConsent").setBoolean(controller, true)
                call(controller, "confirmRestore", emptyArray())
                val draft = field(controller, "draft").get(controller) as RecordDraft
                assertEquals(listOf(segments.last().index), draft.actualCompletedIndexes())
                assertTrue(!draft.allTimedPassed)
            }
        }
    }

    @Test fun fractionalDeadlineFinishKeepsFullReserveAndPartialIndexes() {
        for (meta in TEUM_SITUATIONS) {
            clearState()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                val controller = setup(activity, DeferredPort(), meta.id)
                val (session, start) = timingSession(controller)
                val schedule = field(session, "schedule").get(session) as Schedule
                val activityEnd = (schedule.prepareSeconds + schedule.activitySeconds) * 1000
                val jumpAt = 100_999L
                timingTick(controller, ClockReading(start.wallMillis + activityEnd,
                    start.elapsedMillis + jumpAt))
                val segments = (field(session, "segments").get(session) as List<*>)
                    .filterIsInstance<Segment>()
                val title = activity.findViewById<ViewGroup>(R.id.teum_root)
                    .findViewWithTag<TextView>("session_step_title")
                assertEquals(segments.last().title, title.text.toString())
                val progress = field(session, "progressAtDeadline").get(session) as Long
                assertEquals(100L, progress)
                val finishEnd = jumpAt + schedule.finishSeconds * 1000
                // Wall keeps advancing, but cannot compress the reserved monotonic finish.
                timingTick(controller, ClockReading(schedule.deadlineWallMillis + 60_000,
                    start.elapsedMillis + finishEnd - 1))
                assertEquals(false, field(session, "finished").getBoolean(session))
                assertEquals(false, requireNotNull(SessionSnapshotStore(activity).load()).deadlineFinishCompleted)
                timingTick(controller, ClockReading(schedule.deadlineWallMillis + 60_001,
                    start.elapsedMillis + finishEnd))
                assertEquals(true, field(session, "finished").getBoolean(session))
                assertEquals(true, requireNotNull(SessionSnapshotStore(activity).load()).deadlineFinishCompleted)
                call(controller, "finalizeRecord", arrayOf(session.javaClass), session)
                val draft = field(controller, "draft").get(controller) as RecordDraft
                assertEquals((completedIndexes(segments, 100) + segments.last().index).distinct().sorted(),
                    draft.actualCompletedIndexes())
                assertTrue(!draft.allTimedPassed)
                }
            }
        }
    }

    @Test fun fractionalNormalTicksCompleteEveryActivityStep() {
        for (meta in TEUM_SITUATIONS) {
            clearState()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val controller = setup(activity, DeferredPort(), meta.id)
                    val (session, start) = timingSession(controller)
                    val schedule = field(session, "schedule").get(session) as Schedule
                    val activityEnd = (schedule.prepareSeconds + schedule.activitySeconds) * 1000
                    val total = activityEnd + schedule.finishSeconds * 1000
                    for (offset in listOf(activityEnd - 999, activityEnd - 1, activityEnd + 1, total - 1)) {
                        timingTick(controller, ClockReading(start.wallMillis + offset - 1, start.elapsedMillis + offset))
                        assertEquals(null, field(session, "progressAtDeadline").get(session))
                        assertEquals(false, field(session, "finished").getBoolean(session))
                    }
                    // A late tick must retain the last activity step and the complete finish.
                    timingTick(controller, ClockReading(start.wallMillis + total + 2501,
                        start.elapsedMillis + total + 2501))
                    assertEquals(true, field(session, "finished").getBoolean(session))
                    call(controller, "finalizeRecord", arrayOf(session.javaClass), session)
                    val draft = field(controller, "draft").get(controller) as RecordDraft
                    assertEquals(draft.allIndexes(), draft.actualCompletedIndexes())
                    assertTrue(draft.allTimedPassed)
                }
            }
        }
    }

    @Test fun stoppingForegroundCancelsPendingWithoutMisclassifyingSourceClose() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val port = DeferredPort()
                val controller = setup(activity, port)
                preview(controller)
                val consent = field(controller, "applyConsent").get(controller) as MutableMap<*, *>
                consent.javaClass.getMethod("put", Any::class.java, Any::class.java)
                    .invoke(consent, VehicleFeature.HVAC_TEMPERATURE, true)
                call(controller, "startSession", emptyArray())
                assertEquals(1, port.deferred.size)
                controller.onStop()
                port.deferred.single().invoke()
                assertTrue(port.sent.isEmpty())
                val snapshot = requireNotNull(SessionSnapshotStore(activity).load())
                assertTrue(!snapshot.restricted)
                assertTrue(!snapshot.restoredAndReadyToRecord)
            }
        }
    }
}
