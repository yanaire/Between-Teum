package dev.pleos.starter.teum.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pleos.starter.teum.session.ClockReading
import dev.pleos.starter.teum.vehicle.*
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

    @Test fun readOnlyInspectionOffersManualBadgeWithoutWriteControls() {
        ActivityScenario.launch(NativeRegressionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val f = NativeCoordinatorFixture(activity)
                f.port.writable = false
                f.setup()
                for (feature in listOf("hvac", "ambient")) {
                    assertEquals("수동", f.root.findViewWithTag<TextView>("${feature}_pill").text.toString())
                    assertEquals(null, f.root.findViewWithTag<View>("${feature}_plus"))
                    assertEquals(null, f.root.findViewWithTag<View>("${feature}_preview"))
                }
            }
        }
    }

    @Test fun changingToShortInvalidatesFullPreviewAndRejectsNewPreview() {
        ActivityScenario.launch(NativeRegressionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val f = NativeCoordinatorFixture(activity)
                f.setup()
                f.preview()
                assertTrue(f.port.inspections > 0)
                f.root.findViewWithTag<View>("quick_10").performClick()
                val count = f.port.inspections
                f.preview()
                assertEquals(count, f.port.inspections)
                f.root.findViewWithTag<View>("short_confirm").performClick()
                f.send(TeumSessionAction.Start)
                assertTrue(f.port.deferred.isEmpty())
                assertTrue(f.port.sent.isEmpty())
            }
        }
    }

    @Test fun earlyFinishRevokesADeferredRealAdapterWrite() {
        ActivityScenario.launch(NativeRegressionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val f = NativeCoordinatorFixture(activity)
                f.setup()
                f.preview()
                f.consent()
                f.start()
                assertEquals(1, f.port.deferred.size)
                f.send(TeumSessionAction.FinishNow)
                f.port.deferred.single().invoke()
                assertTrue(f.port.sent.isEmpty())
            }
        }
    }

    @Test fun lunchFeatureRestoreRequiresWakeConsentAndFreshSafety() {
        ActivityScenario.launch(NativeRegressionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val f = NativeCoordinatorFixture(activity)
                f.setup()
                f.preview()
                f.consent()
                f.start()
                f.port.deferred.removeAt(0).invoke()
                assertEquals(listOf(24.0), f.port.sent)
                f.send(TeumSessionAction.FinishNow)
                val feature = VehicleFeature.HVAC_TEMPERATURE
                val inspections = f.port.inspections
                f.send(TeumSessionAction.PreviewRestore(feature))
                assertEquals(inspections, f.port.inspections)
                f.send(TeumSessionAction.WakeAcknowledgement(true))
                f.send(TeumSessionAction.PreviewRestore(feature))
                assertEquals(inspections, f.port.inspections)
                f.send(TeumSessionAction.RestoreConsent(true))
                f.send(TeumSessionAction.PreviewRestore(feature))
                assertEquals(inspections + 1, f.port.inspections)
                f.send(TeumSessionAction.RestoreOriginAck(feature, true))
                f.send(TeumSessionAction.RestoreConsent(false))
                f.send(TeumSessionAction.ConfirmRestoreFeature(feature))
                assertTrue(f.port.deferred.isEmpty())
                f.send(TeumSessionAction.RestoreConsent(true))
                f.send(TeumSessionAction.PreviewRestore(feature))
                f.send(TeumSessionAction.WakeAcknowledgement(false))
                assertEquals(false, f.owner.restoreFeatureAllowed())
                f.send(TeumSessionAction.ConfirmRestoreFeature(feature))
                assertTrue(f.port.deferred.isEmpty())
                f.send(TeumSessionAction.WakeAcknowledgement(true))
                f.send(TeumSessionAction.PreviewRestore(feature))
                f.owner.onSafety(VehicleSafety())
                f.send(TeumSessionAction.ConfirmRestoreFeature(feature))
                assertTrue(f.port.deferred.isEmpty())
                assertEquals(listOf(24.0), f.port.sent)
            }
        }
    }

    @Test fun wallDeadlineRunsFinishWithoutInventingActivityProgress() {
        ActivityScenario.launch(NativeRegressionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val f = NativeCoordinatorFixture(activity)
                f.setup()
                val start = f.start()
                val session = requireNotNull(f.state.active)
                val schedule = session.schedule
                val activityEnd = (schedule.prepareSeconds + schedule.activitySeconds) * 1000
                f.tick(ClockReading(start.wallMillis + activityEnd, start.elapsedMillis))
                assertEquals(session.segments.last().title,
                    f.root.findViewWithTag<TextView>("session_step_title").text.toString())
                f.tick(ClockReading(start.wallMillis + activityEnd + schedule.finishSeconds * 1000,
                    start.elapsedMillis + schedule.finishSeconds * 1000))
                f.approveRecord()
                val draft = requireNotNull(f.state.draft)
                assertEquals(listOf(session.segments.last().index), draft.actualCompletedIndexes())
                assertTrue(!draft.allTimedPassed)
            }
        }
    }

    @Test fun fractionalDeadlineFinishKeepsFullReserveAndPartialIndexes() {
        for (meta in TEUM_SITUATIONS) {
            clearState()
            ActivityScenario.launch(NativeRegressionActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val f = NativeCoordinatorFixture(activity)
                    f.setup(meta.id)
                    val start = f.start()
                    val session = requireNotNull(f.state.active)
                    val schedule = session.schedule
                    val activityEnd = (schedule.prepareSeconds + schedule.activitySeconds) * 1000
                    val jumpAt = 100_999L
                    f.tick(ClockReading(start.wallMillis + activityEnd, start.elapsedMillis + jumpAt))
                    assertEquals(session.segments.last().title,
                        f.root.findViewWithTag<TextView>("session_step_title").text.toString())
                    assertEquals(100L, f.state.active?.progressAtDeadline)
                    val finishEnd = jumpAt + schedule.finishSeconds * 1000
                    f.tick(ClockReading(schedule.deadlineWallMillis + 60_000, start.elapsedMillis + finishEnd - 1))
                    assertEquals(false, f.state.active?.finished)
                    assertEquals(false, requireNotNull(f.markers.load()).deadlineFinishCompleted)
                    f.tick(ClockReading(schedule.deadlineWallMillis + 60_001, start.elapsedMillis + finishEnd))
                    assertEquals(true, f.state.active?.finished)
                    assertEquals(true, requireNotNull(f.markers.load()).deadlineFinishCompleted)
                    f.approveRecord()
                    val draft = requireNotNull(f.state.draft)
                    assertEquals((completedIndexes(session.segments, 100) + session.segments.last().index)
                        .distinct().sorted(), draft.actualCompletedIndexes())
                    assertTrue(!draft.allTimedPassed)
                }
            }
        }
    }

    @Test fun fractionalNormalTicksCompleteEveryActivityStep() {
        for (meta in TEUM_SITUATIONS) {
            clearState()
            ActivityScenario.launch(NativeRegressionActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val f = NativeCoordinatorFixture(activity)
                    f.setup(meta.id)
                    val start = f.start()
                    val schedule = requireNotNull(f.state.active).schedule
                    val activityEnd = (schedule.prepareSeconds + schedule.activitySeconds) * 1000
                    val total = activityEnd + schedule.finishSeconds * 1000
                    for (offset in listOf(activityEnd - 999, activityEnd - 1, activityEnd + 1, total - 1)) {
                        f.tick(ClockReading(start.wallMillis + offset - 1, start.elapsedMillis + offset))
                        assertEquals(null, f.state.active?.progressAtDeadline)
                        assertEquals(false, f.state.active?.finished)
                    }
                    f.tick(ClockReading(start.wallMillis + total + 2501, start.elapsedMillis + total + 2501))
                    assertEquals(true, f.state.active?.finished)
                    f.approveRecord()
                    val draft = requireNotNull(f.state.draft)
                    assertEquals(draft.allIndexes(), draft.actualCompletedIndexes())
                    assertTrue(draft.allTimedPassed)
                }
            }
        }
    }

    @Test fun stoppingForegroundCancelsPendingWithoutMisclassifyingSourceClose() {
        ActivityScenario.launch(NativeRegressionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val f = NativeCoordinatorFixture(activity)
                f.setup()
                f.preview()
                f.consent()
                f.start()
                assertEquals(1, f.port.deferred.size)
                f.controller.onStop()
                f.owner.onSafety(VehicleSafety())
                f.port.deferred.single().invoke()
                assertTrue(f.port.sent.isEmpty())
                val snapshot = requireNotNull(f.markers.load())
                assertTrue(!snapshot.restricted)
                assertTrue(!snapshot.restoredAndReadyToRecord)
            }
        }
    }
}
