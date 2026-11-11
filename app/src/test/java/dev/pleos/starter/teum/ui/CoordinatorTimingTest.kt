package dev.pleos.starter.teum.ui

import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.session.ClockReading
import org.junit.Assert.*
import org.junit.Test

class CoordinatorTimingTest {
    @Test fun fractionalDeadlineKeepsFullFinishReserveAndActualPartialIndexes() {
        for (meta in TEUM_SITUATIONS) {
            CoordinatorFixture(meta.id).use { f ->
                f.send(TeumSessionAction.Start)
                val start = f.clock.now
                val session = requireNotNull(f.state.active)
                val schedule = session.schedule
                val activityEnd = (schedule.prepareSeconds + schedule.activitySeconds) * 1000
                val jumpAt = 100_999L
                f.tick(ClockReading(start.wallMillis + activityEnd, start.elapsedMillis + jumpAt))
                assertEquals(session.segments.last().index, f.state.active?.stepIndex)
                assertEquals(100L, f.state.active?.progressAtDeadline)
                val finishEnd = jumpAt + schedule.finishSeconds * 1000
                f.tick(ClockReading(schedule.deadlineWallMillis + 60_000, start.elapsedMillis + finishEnd - 1))
                assertFalse(requireNotNull(f.state.active).finished)
                assertFalse(requireNotNull(f.markers.load()).deadlineFinishCompleted)
                f.tick(ClockReading(schedule.deadlineWallMillis + 60_001, start.elapsedMillis + finishEnd))
                assertTrue(requireNotNull(f.state.active).finished)
                assertTrue(requireNotNull(f.markers.load()).deadlineFinishCompleted)
                f.approveRecord()
                val draft = requireNotNull(f.state.draft)
                assertEquals((completedIndexes(session.segments, 100) + session.segments.last().index)
                    .distinct().sorted(), draft.actualCompletedIndexes())
                assertFalse(draft.allTimedPassed)
                assertEquals(ActivityOutcome.INTERRUPTED, draftOutcome(draft))
            }
        }
    }

    @Test fun fractionalNormalTicksCompleteEveryTimedStepWithoutDeadlineSubstitution() {
        for (meta in TEUM_SITUATIONS) {
            CoordinatorFixture(meta.id).use { f ->
                f.send(TeumSessionAction.Start)
                val start = f.clock.now
                val schedule = requireNotNull(f.state.active).schedule
                val activityEnd = (schedule.prepareSeconds + schedule.activitySeconds) * 1000
                val total = activityEnd + schedule.finishSeconds * 1000
                for (offset in listOf(activityEnd - 999, activityEnd - 1, activityEnd + 1, total - 1)) {
                    f.tick(ClockReading(start.wallMillis + offset - 1, start.elapsedMillis + offset))
                    assertNull(f.state.active?.progressAtDeadline)
                    assertFalse(requireNotNull(f.state.active).finished)
                }
                f.tick(ClockReading(start.wallMillis + total + 2501, start.elapsedMillis + total + 2501))
                assertTrue(requireNotNull(f.state.active).finished)
                f.approveRecord()
                val draft = requireNotNull(f.state.draft)
                assertEquals(draft.allIndexes(), draft.actualCompletedIndexes())
                assertTrue(draft.allTimedPassed)
                assertEquals(ActivityOutcome.COMPLETED, draftOutcome(draft))
            }
        }
    }

    @Test fun safetyInterruptionStopsWakeCuesAndCannotResumeOnFreshPark() {
        CoordinatorFixture().use { f ->
            f.send(TeumSessionAction.Start)
            val start = f.clock.now
            val session = requireNotNull(f.state.active)
            val wake = session.segments.indexOfFirst { it.stepId == "wake" || it.stepId == "wrap" }
            val elapsed = (segmentEnd(session.segments, wake) - 60) * 1000L
            val now = ClockReading(start.wallMillis + elapsed - 1, start.elapsedMillis + elapsed)
            f.tick(now)
            f.tick(now)
            assertEquals(1, f.state.cueCount)
            f.owner.onSafety(dev.pleos.starter.teum.vehicle.VehicleSafety())
            assertTrue(requireNotNull(f.state.active).finished)
            f.freshSafety()
            f.send(TeumSessionAction.Tick)
            assertEquals(Screen.RESTORE, f.state.screen)
            assertEquals(1, f.state.cueCount)
            f.send(TeumSessionAction.RecordRestricted)
            assertEquals(ActivityOutcome.SAFETY_INTERRUPTED, draftOutcome(requireNotNull(f.state.draft)))
        }
    }
}
