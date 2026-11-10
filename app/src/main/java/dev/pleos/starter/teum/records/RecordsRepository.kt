package dev.pleos.starter.teum.records

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
}
