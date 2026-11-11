package dev.pleos.starter.teum.records

import dev.pleos.starter.teum.content.SituationIds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class JourneyStatisticsTest {
    private val utc = ZoneId.of("UTC")
    private val monday = LocalDate.parse("2026-11-09")
    private fun millis(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun record(id: String, finish: String, kind: JourneyKind = JourneyKind.MORNING): ActivityRecord =
        newActivityRecord(SituationIds.COMMUTE_MORNING, "Actual activity", 1, listOf(0), 100,
            ActivityOutcome.COMPLETED, nowEpochMs = millis("2026-11-13T12:00:00Z"),
            journeyFacts = JourneyFacts(kind = kind, finishedAtEpochMs = millis(finish))).copy(id = id)

    @Test
    fun emptyWeekHasSevenZeroBarsAndNoSeededStreak() {
        val week = JourneyStatistics.week(emptyList(), monday, utc)
        assertEquals((0L..6).map { monday.plusDays(it) }, week.days.map { it.date })
        assertEquals(List(7) { 0L }, week.days.map { it.elapsedSeconds })
        assertEquals(0, week.activityCount)
        assertEquals(0, week.streakDays)
    }

    @Test
    fun midnightBarsSplitMeasuredElapsedAndCompletionBelongsToFinishDay() {
        val saved = record("midnight", "2026-11-10T00:01:00Z").let {
            it.copy(elapsedSeconds = 90, journeyFacts = it.journeyFacts?.copy(startedAtEpochMs = millis("2026-11-09T23:59:00Z")))
        }
        val week = JourneyStatistics.week(listOf(saved), monday.plusDays(1), utc)
        assertEquals(listOf(45L, 45L, 0L, 0L, 0L, 0L, 0L), week.days.map { it.elapsedSeconds })
        assertEquals(90L, week.elapsedSeconds)
        assertEquals(listOf(0, 1, 0, 0, 0, 0, 0), week.days.map { it.activityCount })
        assertEquals(setOf(JourneyKind.MORNING), week.days[1].completedJourneys)
    }

    @Test
    fun suppliedZoneAndDuplicateNativeSavesDriveCountsAndStreak() {
        val first = record("first", "2026-11-09T16:30:00Z").let {
            it.copy(journeyFacts = it.journeyFacts?.copy(nativeActivityId = "same-native"))
        }
        val duplicate = first.copy(id = "second", updatedAtEpochMs = first.updatedAtEpochMs + 1)
        val next = record("next", "2026-11-10T16:30:00Z")
        val week = JourneyStatistics.week(listOf(first, duplicate, next, next), monday.plusDays(2), ZoneId.of("Asia/Seoul"))
        assertEquals(listOf(0L, 100L, 100L, 0L, 0L, 0L, 0L), week.days.map { it.elapsedSeconds })
        assertEquals(2, week.activityCount)
        assertEquals(2, week.streakDays)
    }

    @Test
    fun quickOrShortBreathingAndInterruptedAllStepsNeverCompleteMorning() {
        val quick = record("quick", "2026-11-09T10:00:00Z", JourneyKind.QUICK_BREATHING)
        val interrupted = record("interrupted", "2026-11-09T11:00:00Z").copy(
            outcome = ActivityOutcome.INTERRUPTED)
        val day = JourneyStatistics.week(listOf(quick, interrupted), monday, utc).days.first()
        assertEquals(setOf(JourneyKind.QUICK_BREATHING), day.completedJourneys)
        assertEquals(1, day.completedCount)
        assertEquals(200L, day.elapsedSeconds)
    }

    @Test
    fun legacySaveTimeIsLabeledAndUndatedRecordsNeverAcquireInventedDates() {
        val legacy = record("legacy", "2026-11-09T10:00:00Z").copy(
            journeyFacts = null, createdAtEpochMs = millis("2026-11-09T12:00:00Z"))
        val undated = legacy.copy(id = "undated", createdAtEpochMs = 0)
        val week = JourneyStatistics.week(listOf(legacy, undated), monday, utc)
        assertEquals(100L, week.days.first().savedTimeElapsedSeconds)
        assertEquals(RecordTimeBasis.SAVED_TIME, week.days.first().records.single().timeBasis)
        assertEquals(emptySet<JourneyKind>(), week.days.first().completedJourneys)
        assertEquals(listOf("undated"), week.undatedRecordIds)
        assertEquals(0, week.streakDays)
    }

    @Test
    fun daylightSavingAndWeekBoundaryPreserveMeasuredSeconds() {
        val zone = ZoneId.of("America/New_York")
        val finish = "2026-11-02T05:00:00Z"
        val saved = record("dst", finish).let {
            it.copy(elapsedSeconds = 250, journeyFacts = it.journeyFacts?.copy(
                startedAtEpochMs = millis("2026-11-01T04:00:00Z")))
        }
        val previous = JourneyStatistics.week(listOf(saved), LocalDate.parse("2026-11-01"), zone)
        val next = JourneyStatistics.week(listOf(saved), LocalDate.parse("2026-11-02"), zone)
        assertEquals(250L, previous.elapsedSeconds)
        assertEquals(0L, next.elapsedSeconds)
        assertEquals(0, previous.activityCount)
        assertEquals(1, next.activityCount)
    }
}
