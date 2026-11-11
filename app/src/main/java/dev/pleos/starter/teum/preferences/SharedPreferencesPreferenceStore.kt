package dev.pleos.starter.teum.preferences

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesPreferenceStore(
    private val prefs: SharedPreferences
) : PreferenceStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    @Synchronized
    override fun load(): TeumPreferences {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return TeumPreferences()
        return PreferenceJson.decode(raw) ?: run {
            quarantine(raw)
            TeumPreferences()
        }
    }

    @Synchronized
    override fun persist(preferences: TeumPreferences) {
        val raw = PreferenceJson.encode(preferences)
        require(PreferenceJson.decode(raw) != null)
        prefs.getString(KEY_SETTINGS, null)?.let {
            if (PreferenceJson.decode(it) == null) quarantine(it)
        }
        check(prefs.edit().putString(KEY_SETTINGS, raw).commit()) { "Preference persistence failed" }
    }

    @Synchronized
    fun quarantinedRaw(): String? = prefs.getString(KEY_QUARANTINE, null)

    @Synchronized
    fun clearQuarantine() {
        check(prefs.edit().remove(KEY_QUARANTINE).commit())
    }

    private fun quarantine(raw: String) {
        if (!prefs.contains(KEY_QUARANTINE)) {
            check(prefs.edit().putString(KEY_QUARANTINE, raw).commit())
        }
    }

    companion object {
        const val PREFS_NAME = "teum_preferences_private_v1"
        const val KEY_SETTINGS = "settings_json_v1"
        const val KEY_QUARANTINE = "settings_quarantine_v1"
    }
}
