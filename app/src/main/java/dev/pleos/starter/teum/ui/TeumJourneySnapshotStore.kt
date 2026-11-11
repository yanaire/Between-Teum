package dev.pleos.starter.teum.ui

import android.content.Context
import android.content.SharedPreferences
import dev.pleos.starter.teum.journey.JourneySnapshotCodec
import dev.pleos.starter.teum.journey.JourneySnapshotStore

/** Owned by the serialized app worker. Loading never discards an invalid checkpoint. */
class TeumJourneySnapshotStore(private val preferences: SharedPreferences) : JourneySnapshotStore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(
        "teum_original_journey_private_v1", Context.MODE_PRIVATE
    ))

    override fun load(): String? = preferences.getString(KEY, null)

    override fun save(json: String) {
        JourneySnapshotCodec.decode(json)
        check(preferences.edit().putString(KEY, json).commit()) { "Journey checkpoint was not saved" }
    }

    override fun clear() {
        check(preferences.edit().remove(KEY).commit()) { "Journey checkpoint was not cleared" }
    }

    companion object {
        private const val KEY = "checkpoint_json_v1"
    }
}
