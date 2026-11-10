package dev.pleos.starter.teum.records

interface RecordStore {
    fun loadAll(): List<ActivityRecord>
    fun persistAll(records: List<ActivityRecord>)
    fun loadHistoryConsent(): Boolean
    fun persistHistoryConsent(consented: Boolean)
}

class InMemoryRecordStore(
    initial: List<ActivityRecord> = emptyList(),
    initialConsent: Boolean = false
) : RecordStore {
    private var records: List<ActivityRecord> = initial.toList()
    private var consent: Boolean = initialConsent

    override fun loadAll(): List<ActivityRecord> = records.toList()

    override fun persistAll(records: List<ActivityRecord>) {
        this.records = records.toList()
    }

    override fun loadHistoryConsent(): Boolean = consent

    override fun persistHistoryConsent(consented: Boolean) {
        consent = consented
    }
}
