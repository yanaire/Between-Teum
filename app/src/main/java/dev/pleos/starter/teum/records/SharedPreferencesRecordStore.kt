package dev.pleos.starter.teum.records

import android.content.Context

class SharedPreferencesRecordStore(context: Context) : RecordStore {
    private val prefs = context.getApplicationContext().getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun loadAll(): List<ActivityRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        if (RecordJson.isMalformedArray(raw)) {
            stashQuarantine(raw)
            return emptyList()
        }
        return RecordJson.decodeAll(raw)
    }

    @Synchronized
    override fun persistAll(records: List<ActivityRecord>) {
        records.forEach { it.validate() }
        // Never silently delete a corrupt payload: preserve the original raw
        // value in the private quarantine before overwriting it.
        val current = prefs.getString(KEY_RECORDS, null)
        if (current != null && RecordJson.isMalformedArray(current)) {
            stashQuarantine(current)
        }
        prefs.edit().putString(KEY_RECORDS, RecordJson.encodeAll(records)).apply()
    }

    @Synchronized
    override fun loadHistoryConsent(): Boolean =
        prefs.getBoolean(KEY_HISTORY_CONSENT, false)

    @Synchronized
    override fun persistHistoryConsent(consented: Boolean) {
        prefs.edit().putBoolean(KEY_HISTORY_CONSENT, consented).apply()
    }

    /**
     * Original raw payload stashed when corruption was detected, or null when
     * storage was never corrupt. Survives subsequent saves so diagnosis is
     * possible; call [clearQuarantine] only after the user explicitly clears it.
     */
    @Synchronized
    fun quarantinedRaw(): String? = prefs.getString(KEY_QUARANTINE, null)

    @Synchronized
    fun clearQuarantine() {
        prefs.edit().remove(KEY_QUARANTINE).apply()
    }

    private fun stashQuarantine(raw: String) {
        if (!prefs.contains(KEY_QUARANTINE)) {
            prefs.edit().putString(KEY_QUARANTINE, raw).apply()
        }
    }

    companion object {
        const val PREFS_NAME = "teum_records_private"
        const val KEY_RECORDS = "records_json_v1"
        const val KEY_HISTORY_CONSENT = "history_consent_v1"
        const val KEY_QUARANTINE = "records_quarantine_v1"
    }
}
