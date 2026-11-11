package dev.pleos.starter.teum.records

import dev.pleos.starter.teum.content.SituationIds
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecordRetentionTest {
    private val zone = ZoneId.of("UTC")
    private fun time(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun evening(): ActivityRecord = newActivityRecord(
        SituationIds.COMMUTE_EVENING, "Evening", 1, listOf(0), 59,
        ActivityOutcome.COMPLETED, note = "Unrelated note", nextAction = "Keep recall", recallAccepted = true,
        nowEpochMs = time("2026-11-09T22:00:00Z"),
        journeyFacts = JourneyFacts(JourneyKind.EVENING, finishedAtEpochMs = time("2026-11-09T21:00:00Z"),
            reflection = EveningReflection("Raw story", "Derived greeting"))
    )

    @Test
    fun reflectionOnlyAppearsOnNextLocalDayAndIsNotConsumedByReading() {
        val store = InMemoryRecordStore(listOf(evening()))
        var now = time("2026-11-09T23:59:59Z")
        val repo = RecordsRepository(store) { now }
        assertNull(repo.morningReflection(zone))
        now = time("2026-11-10T00:00:00Z")
        val expected = MorningReflection(evening().id, "Raw story", "Derived greeting")
        val first = repo.morningReflection(zone)
        assertEquals(expected.copy(recordId = store.loadAll().single().id), first)
        assertEquals(first, repo.morningReflection(zone))
        now = time("2026-11-11T00:00:00Z")
        assertNull(repo.morningReflection(zone))
    }

    @Test
    fun consumeDeletesRawStoryAndDerivedGreetingTogetherAndKeepsRecallConsent() {
        val record = evening()
        val store = InMemoryRecordStore(listOf(record), initialConsent = true)
        val repo = RecordsRepository(store) { time("2026-11-10T08:00:00Z") }
        val result = repo.consumeMorningReflection(record.id, zone, true)
        assertEquals(MorningReflection(record.id, "Raw story", "Derived greeting"), result)
        val recreated = RecordsRepository(store) { time("2026-11-10T09:00:00Z") }
        val saved = requireNotNull(recreated.get(record.id))
        assertEquals(EveningReflection(consumedAtEpochMs = time("2026-11-10T08:00:00Z")), saved.journeyFacts?.reflection)
        assertNull(recreated.morningReflection(zone))
        assertNull(recreated.consumeMorningReflection(record.id, zone, true))
        assertEquals("Unrelated note", saved.note)
        assertEquals(listOf(record.id), recreated.pendingRecalls().map { it.id })
        assertTrue(recreated.isHistoryConsent())
    }

    @Test
    fun disabledStoryDeletionRetainsTextButConsumptionIsStillOnce() {
        val record = evening()
        val store = InMemoryRecordStore(listOf(record))
        val repo = RecordsRepository(store) { time("2026-11-10T08:00:00Z") }
        repo.consumeMorningReflection(record.id, zone, false)
        assertEquals("Raw story", repo.get(record.id)?.journeyFacts?.reflection?.story)
        assertEquals("Derived greeting", repo.get(record.id)?.journeyFacts?.reflection?.greeting)
        assertNull(repo.morningReflection(zone))
        assertEquals(emptyList<String>(), repo.maintainHistory(zone, false).redactedReflectionIds)
        assertEquals(listOf(record.id), repo.maintainHistory(zone, true).redactedReflectionIds)
    }

    @Test
    fun expiryRedactsBothTextsAtEndOfNextDayWithoutTouchingOtherRecords() {
        val record = evening()
        val other = record.copy(id = "unrelated", journeyFacts = null)
        val store = InMemoryRecordStore(listOf(record, other))
        var now = time("2026-11-10T23:59:59Z")
        val repo = RecordsRepository(store) { now }
        assertEquals(emptyList<String>(), repo.maintainHistory(zone, true).redactedReflectionIds)
        now = time("2026-11-11T00:00:00Z")
        assertEquals(listOf(record.id), repo.maintainHistory(zone, true).redactedReflectionIds)
        assertEquals(EveningReflection(), repo.get(record.id)?.journeyFacts?.reflection)
        assertEquals(other, repo.get(other.id))
        assertEquals(emptyList<String>(), repo.maintainHistory(zone, true).redactedReflectionIds)
    }

    @Test
    fun oneCalendarYearRetentionUsesActualTimeAndPreservesUndatedLegacy() {
        val now = time("2026-11-10T08:00:00Z")
        val actualOld = evening().let {
            it.copy(id = "actual-old", createdAtEpochMs = now, journeyFacts = it.journeyFacts?.copy(
                finishedAtEpochMs = time("2025-11-10T08:00:00Z")))
        }
        val legacyOld = evening().copy(id = "legacy-old", journeyFacts = null,
            createdAtEpochMs = time("2025-11-10T07:59:59Z"))
        val undated = legacyOld.copy(id = "undated", createdAtEpochMs = 0)
        val current = legacyOld.copy(id = "current", createdAtEpochMs = time("2025-11-10T08:00:01Z"))
        val store = InMemoryRecordStore(listOf(actualOld, legacyOld, undated, current), true)
        val repo = RecordsRepository(store) { now }
        val result = repo.maintainHistory(zone, false)
        assertEquals(listOf("actual-old", "legacy-old"), result.deletedRecordIds)
        assertEquals(setOf("undated", "current"), RecordsRepository(store).listAll().map { it.id }.toSet())
        assertTrue(repo.isHistoryConsent())
    }

    @Test
    fun morningReflectionUsesSuppliedZoneAcrossMidnight() {
        val record = evening().let { it.copy(journeyFacts = it.journeyFacts?.copy(
            finishedAtEpochMs = time("2026-11-09T23:30:00Z"))) }
        val store = InMemoryRecordStore(listOf(record))
        val repo = RecordsRepository(store) { time("2026-11-10T08:00:00Z") }
        assertNotNull(repo.morningReflection(ZoneId.of("America/Los_Angeles")))
        assertNull(repo.morningReflection(ZoneId.of("Asia/Seoul")))
    }
}
