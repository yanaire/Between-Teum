package dev.pleos.starter.teum.records

import java.math.BigInteger
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class DayRecord(val record: ActivityRecord, val timeBasis: RecordTimeBasis)
data class DailyJourneyStats(
    val date: LocalDate,
    val elapsedSeconds: Long,
    val savedTimeElapsedSeconds: Long,
    val records: List<DayRecord>,
    val completedJourneys: Set<JourneyKind>
) {
    val activityCount: Int get() = records.size
    val completedCount: Int get() = records.count { it.record.isFullyCompleted() }
}

data class WeeklyJourneyStats(
    val days: List<DailyJourneyStats>,
    val streakDays: Int,
    val undatedRecordIds: List<String>
) {
    val elapsedSeconds: Long get() = days.fold(0L) { sum, day -> Math.addExact(sum, day.elapsedSeconds) }
    val activityCount: Int get() = days.sumOf { it.activityCount }
    val completedCount: Int get() = days.sumOf { it.completedCount }
}

object JourneyStatistics {
    /**
     * Monday-based week. Finish-date counts, interval-overlap duration bars.
     * Wall-clock overlap apportions the authoritative monotonic elapsedSeconds;
     * it never substitutes wall duration for measured elapsed duration.
     * Incomplete timestamp pairs use their known date without inventing a start.
     */
    fun week(records: List<ActivityRecord>, containing: LocalDate, zone: ZoneId): WeeklyJourneyStats {
        records.forEach { it.validate() }
        val unique = records.sortedByDescending { it.updatedAtEpochMs }.distinctBy { it.id }
            .distinctBy { it.journeyFacts?.nativeActivityId?.let { id -> "native:$id" } ?: "record:${it.id}" }
        val monday = containing.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun date(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        val days = (0L..6L).map { offset ->
            val day = monday.plusDays(offset)
            val entries = unique.filter { it.knownTimeEpochMs()?.let(::date) == day }
                .map { DayRecord(it, it.timeBasis()) }
            var elapsed = 0L
            var savedTime = 0L
            for (record in unique) {
                val seconds = secondsOnDay(record, day, zone)
                elapsed = Math.addExact(elapsed, seconds)
                if (record.timeBasis() == RecordTimeBasis.SAVED_TIME) savedTime = Math.addExact(savedTime, seconds)
            }
            DailyJourneyStats(day, elapsed, savedTime, entries,
                entries.filter { it.record.isFullyCompleted() && it.timeBasis == RecordTimeBasis.ACTIVITY_FINISH }
                    .mapNotNull { it.record.journeyFacts?.kind }.toSet())
        }
        val completedDays = unique.filter { it.isFullyCompleted() && it.journeyFacts?.finishedAtEpochMs != null }
            .map { date(requireNotNull(it.journeyFacts?.finishedAtEpochMs)) }.toSet()
        var cursor = if (containing in completedDays) containing else containing.minusDays(1)
        var streak = 0
        while (cursor in completedDays) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return WeeklyJourneyStats(days, streak, unique.filter { it.timeBasis() == RecordTimeBasis.UNDATED }.map { it.id })
    }

    private fun secondsOnDay(record: ActivityRecord, day: LocalDate, zone: ZoneId): Long {
        val known = record.knownTimeEpochMs() ?: return 0
        val start = record.journeyFacts?.startedAtEpochMs
        val finish = record.journeyFacts?.finishedAtEpochMs
        if (start == null || finish == null || start == finish) {
            return if (Instant.ofEpochMilli(known).atZone(zone).toLocalDate() == day) record.elapsedSeconds else 0
        }
        val lower = day.atStartOfDay(zone).toInstant().toEpochMilli().coerceIn(start, finish)
        val upper = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().coerceIn(start, finish)
        val duration = BigInteger.valueOf(record.elapsedSeconds)
        val span = BigInteger.valueOf(finish - start)
        // Clamped endpoints keep this quotient between zero and elapsedSeconds.
        fun cumulative(at: Long): Long =
            duration.multiply(BigInteger.valueOf(at - start)).divide(span).toLong()
        return cumulative(upper) - cumulative(lower)
    }
}
