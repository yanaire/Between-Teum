package dev.pleos.starter.teum.ui

import dev.pleos.starter.teum.content.SituationIds
import dev.pleos.starter.teum.records.ActivityOutcome
import dev.pleos.starter.teum.session.ScheduleKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordOutcomeTest {
    private fun draft(
        elapsed: Long,
        allTimed: Boolean,
        recovered: Boolean = false,
        safety: Boolean = false
    ): RecordDraft {
        val segments = buildSegments(SituationIds.LUNCH_BREAK, ScheduleKind.SHORT, 60, 180, 180)
        return RecordDraft(
            situationId = SituationIds.LUNCH_BREAK,
            segments = segments,
            elapsedSeconds = elapsed,
            plannedReturnWallMillis = 1_700_000_600_000,
            actualFinishWallMillis = 1_700_000_100_000,
            safetyInterrupted = safety,
            recovered = recovered,
            allTimedPassed = allTimed,
            restoredBeforeSave = !recovered
        )
    }

    @Test
    fun earlyFinishStaysInterruptedEvenAfterRestoreScreenWait() {
        val early = draft(elapsed = 1, allTimed = false)
        assertEquals(ActivityOutcome.INTERRUPTED, draftOutcome(early))
        assertEquals(emptyList<Int>(), early.actualCompletedIndexes())
    }

    @Test
    fun allTimedSegmentsPassingIsCompletedWithoutAClaim() {
        val complete = draft(elapsed = 420, allTimed = true)
        assertEquals(ActivityOutcome.COMPLETED, draftOutcome(complete))
        assertEquals(listOf(0, 1, 2), complete.actualCompletedIndexes())
    }

    @Test
    fun recoveredInterruptionIsNeverCompleted() {
        val recovered = draft(elapsed = 420, allTimed = true, recovered = true)
        assertEquals(ActivityOutcome.INTERRUPTED, draftOutcome(recovered))
    }

    @Test
    fun safetyInterruptionIsNeverCompleted() {
        val safety = draft(elapsed = 1, allTimed = false, safety = true)
        assertEquals(
            ActivityOutcome.SAFETY_INTERRUPTED,
            draftOutcome(safety)
        )
    }
}
