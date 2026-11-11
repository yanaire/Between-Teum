package dev.pleos.starter.teum.records

import java.time.Instant
import java.time.ZoneId

class RecordsRepository(
    private val store: RecordStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    fun save(record: ActivityRecord): ActivityRecord {
        record.validate()
        val current = store.loadAll().toMutableList()
        current.removeAll { it.id == record.id }
        current.add(record)
        store.persistAll(current.sortedByDescending { it.createdAtEpochMs })
        return record
    }

    fun listAll(): List<ActivityRecord> =
        store.loadAll().sortedByDescending { it.createdAtEpochMs }

    fun get(id: String): ActivityRecord? = store.loadAll().find { it.id == id }

    fun update(
        id: String,
        note: String,
        nextAction: String,
        recallAccepted: Boolean
    ): ActivityRecord? {
        val current = store.loadAll()
        val existing = current.find { it.id == id } ?: return null
        val updated = existing.copy(
            note = note,
            nextAction = nextAction,
            recallAccepted = recallAccepted,
            updatedAtEpochMs = nowEpochMs()
        )
        updated.validate()
        store.persistAll(current.map { if (it.id == id) updated else it })
        return updated
    }

    fun delete(id: String): Boolean {
        val current = store.loadAll()
        if (current.none { it.id == id }) return false
        store.persistAll(current.filterNot { it.id == id })
        return true
    }

    fun clearAll() {
        store.persistAll(emptyList())
    }

    fun isHistoryConsent(): Boolean = store.loadHistoryConsent()

    fun setHistoryConsent(consented: Boolean) {
        store.persistHistoryConsent(consented)
    }

    fun pendingRecalls(): List<ActivityRecord> =
        store.loadAll()
            .filter { it.recallAccepted && it.nextAction.isNotBlank() }
            .sortedByDescending { it.createdAtEpochMs }

    fun dismissRecall(id: String): ActivityRecord? {
        val current = store.loadAll()
        val existing = current.find { it.id == id } ?: return null
        if (!existing.recallAccepted) return existing
        val updated = existing.copy(recallAccepted = false, updatedAtEpochMs = nowEpochMs())
        store.persistAll(current.map { if (it.id == id) updated else it })
        return updated
    }

    fun suggestFromHistory(situationId: String): ActivityRecord? {
        if (!store.loadHistoryConsent()) return null
        return store.loadAll()
            .filter { it.situationId == situationId && it.outcome == ActivityOutcome.COMPLETED }
            .sortedByDescending { it.createdAtEpochMs }
            .firstOrNull()
    }

    /** Explicit lifecycle maintenance; never silently run as a read side effect. */
    fun maintainHistory(zone: ZoneId, deleteEveningStoryAfterNextMorning: Boolean): HistoryMaintenance {
        val now = nowEpochMs()
        val current = store.loadAll()
        val result = RecordRetention.apply(current, now, HistoryPolicy(zone, deleteEveningStoryAfterNextMorning))
        if (result.records != current) store.persistAll(result.records)
        return result
    }

    /** Yesterday's unconsumed reflection only; callers display native stored text. */
    fun morningReflection(zone: ZoneId): MorningReflection? {
        val today = Instant.ofEpochMilli(nowEpochMs()).atZone(zone).toLocalDate()
        return store.loadAll().sortedByDescending { it.knownTimeEpochMs() ?: 0 }
            .firstNotNullOfOrNull { record ->
                val facts = record.journeyFacts ?: return@firstNotNullOfOrNull null
                val reflection = facts.reflection ?: return@firstNotNullOfOrNull null
                val time = facts.finishedAtEpochMs ?: facts.startedAtEpochMs ?: return@firstNotNullOfOrNull null
                val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
                if (day != today.minusDays(1) || reflection.consumedAtEpochMs != null ||
                    (reflection.story == null && reflection.greeting == null)) null
                else MorningReflection(record.id, reflection.story, reflection.greeting)
            }
    }

    /**
     * Invoke after presenting the selected reflection. With deletion enabled,
     * source story and its derived greeting are removed in the same store write.
     */
    fun consumeMorningReflection(
        recordId: String,
        zone: ZoneId,
        deleteEveningStoryAfterNextMorning: Boolean
    ): MorningReflection? {
        val selected = morningReflection(zone)?.takeIf { it.recordId == recordId } ?: return null
        val now = nowEpochMs()
        val current = store.loadAll()
        val updated = current.map { record ->
            if (record.id != recordId) record else {
                val facts = requireNotNull(record.journeyFacts)
                val reflection = requireNotNull(facts.reflection)
                val consumed = if (deleteEveningStoryAfterNextMorning) {
                    EveningReflection(consumedAtEpochMs = now)
                } else reflection.copy(consumedAtEpochMs = now)
                record.copy(journeyFacts = facts.copy(reflection = consumed), updatedAtEpochMs = now)
            }
        }
        store.persistAll(updated)
        return selected
    }

    fun weeklyStats(weekContaining: java.time.LocalDate, zone: ZoneId): WeeklyJourneyStats =
        JourneyStatistics.week(listAll(), weekContaining, zone)
}
