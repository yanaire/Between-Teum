package dev.pleos.starter.teum.location

import android.content.Context
import android.content.SharedPreferences
import dev.pleos.starter.teum.preferences.JourneyNotification

/** One policy/store owner per Android user. No coordinates or address in this namespace. */
class NativeArrivalLedgerStore(private val storage: SharedPreferences) : ArrivalLedgerStore {
    constructor(context: Context) : this(context.getSharedPreferences(
        "teum_arrival_ledger_v1", Context.MODE_PRIVATE))

    override fun load(): ArrivalLedger {
        val entries = JourneyNotification.entries.mapNotNull { slot ->
            val key = slot.name
            val date = storage.getString("$key.date", null) ?: return@mapNotNull null
            slot to VisitEntry(
                date = date,
                target = requireNotNull(storage.getString("$key.target", null)),
                visit = storage.getLong("$key.visit", 0),
                inside = storage.getBoolean("$key.inside", false),
                token = storage.getString("$key.token", "") ?: "",
                outcome = storage.getString("$key.outcome", null)?.let(SuggestionOutcome::valueOf)
            )
        }.toMap()
        return ArrivalLedger(entries, storage.getLong("cooldown", 0), storage.getLong("evaluated", 0))
    }

    override fun persist(ledger: ArrivalLedger) {
        val edit = storage.edit()
            .putLong("cooldown", ledger.cooldownUntilMillis)
            .putLong("evaluated", ledger.lastEvaluatedMillis)
        ledger.entries.forEach { (slot, value) ->
            val key = slot.name
            edit.putString("$key.date", value.date)
                .putString("$key.target", value.target)
                .putLong("$key.visit", value.visit)
                .putBoolean("$key.inside", value.inside)
                .putString("$key.token", value.token)
                .putString("$key.outcome", value.outcome?.name)
        }
        check(edit.commit()) { "Arrival suppression persistence failed" }
    }
}
