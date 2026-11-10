package dev.pleos.starter.teum.ui

import dev.pleos.starter.teum.content.SituationIds
import dev.pleos.starter.teum.session.ScheduleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class SessionSnapshotCodecTest {
    private fun snapshot(elapsed: Long = 120) = SessionSnapshot(
        situationId = SituationIds.LUNCH_BREAK,
        kind = "FULL",
        startWallMillis = 1_700_000_000_000,
        deadlineWallMillis = 1_700_001_500_000,
        prepareSeconds = 60,
        activitySeconds = 1080,
        finishSeconds = 180,
        travelSeconds = 180,
        elapsedSeconds = elapsed,
        wallMillis = 1_700_000_120_000,
        stage = "INTERRUPTED",
        reason = "앱이 화면을 떠나 타이머가 중단되었습니다.",
        restricted = false,
        restoredAndReadyToRecord = false,
        awakeAcknowledged = false
    )

    @Test
    fun roundTripPreservesScheduleAndRecoveryFacts() {
        val original = snapshot()
        val decoded = SessionSnapshotCodec.decode(SessionSnapshotCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(1320, decoded!!.timedTotalSeconds)
    }

    @Test
    fun malformedOrNegativeMarkersAreRejected() {
        assertNull(SessionSnapshotCodec.decode(null))
        assertNull(SessionSnapshotCodec.decode("not json"))
        assertNull(SessionSnapshotCodec.decode("{\"version\":1}"))
        val negative = SessionSnapshotCodec.encode(snapshot(elapsed = -1))
        assertNull(SessionSnapshotCodec.decode(negative))
        val unknown = SessionSnapshotCodec.encode(snapshot().copy(situationId = "unknown"))
        assertNull(SessionSnapshotCodec.decode(unknown))
        val badKind = SessionSnapshotCodec.encode(snapshot().copy(kind = "PARTIAL"))
        assertNull(SessionSnapshotCodec.decode(badKind))
    }

    @Test
    fun lunchExternalMoveIsExcludedFromTimedSegmentsAndCompletionIndexes() {
        val segments = buildSegments(SituationIds.LUNCH_BREAK, ScheduleKind.FULL, 60, 1080, 180)
        assertEquals(3, segments.size)
        assertEquals(listOf(0, 1, 2), segments.map { it.index })
        assertEquals(1320, segments.sumOf { it.seconds })
        assertEquals(emptyList<Int>(), completedIndexes(segments, 59))
        assertEquals(listOf(0), completedIndexes(segments, 60))
        assertEquals(listOf(0, 1, 2), completedIndexes(segments, 1320))
        assertEquals(2, stepIndexAt(segments, 1320))
    }

    @Test
    fun fullSchedulesUseTheirContentSegmentIndexes() {
        val morning = buildSegments(SituationIds.COMMUTE_MORNING, ScheduleKind.FULL, 30, 240, 30)
        assertEquals(4, morning.size)
        assertEquals(300, morning.sumOf { it.seconds })
        val short = buildSegments(SituationIds.COMMUTE_MORNING, ScheduleKind.SHORT, 30, 60, 60)
        assertEquals(3, short.size)
        assertEquals(150, short.sumOf { it.seconds })
        assertNotNull(short.firstOrNull { it.stepId == "activity" })
    }

    @Test
    fun arrivalBeforeNowResolvesToTomorrow() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = calendar.timeInMillis
        val deadline = resolveDeadline(9, 0, now)
        assertEquals(23 * 60 * 60 * 1000L, deadline - now)
        val later = resolveDeadline(11, 0, now)
        assertEquals(60 * 60 * 1000L, later - now)
    }
}
