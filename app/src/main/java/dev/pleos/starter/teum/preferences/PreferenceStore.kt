package dev.pleos.starter.teum.preferences

interface PreferenceStore {
    fun load(): TeumPreferences
    fun persist(preferences: TeumPreferences)
}

class InMemoryPreferenceStore(initial: TeumPreferences = TeumPreferences()) : PreferenceStore {
    private var value = initial
    override fun load(): TeumPreferences = value
    override fun persist(preferences: TeumPreferences) { value = preferences }
}

/** One owner per namespace. Updates always read the latest persisted snapshot. */
class PreferencesRepository(private val store: PreferenceStore) {
    fun load(): TeumPreferences = store.load()

    @Synchronized
    fun update(change: (TeumPreferences) -> TeumPreferences): TeumPreferences {
        val updated = change(store.load())
        // Round-tripping also snapshots caller-owned collections at the storage boundary.
        requireNotNull(PreferenceJson.decode(PreferenceJson.encode(updated))).let {
            store.persist(it)
            return it
        }
    }

    fun setCompany(place: SavedPlace): TeumPreferences = update { it.copy(company = place) }
    fun setHome(place: SavedPlace): TeumPreferences = update { it.copy(home = place) }
    fun setSchedule(schedule: DailySchedule): TeumPreferences = update { it.copy(schedule = schedule) }
    fun setBackrest(backrest: BackrestPreferences): TeumPreferences = update { it.copy(backrest = backrest) }
    fun setRestSound(sound: RestSound): TeumPreferences = update { it.copy(restSound = sound) }
    fun setVoiceGuide(enabled: Boolean): TeumPreferences = update { it.copy(voiceGuide = enabled) }
    fun setNotifications(value: NotificationPreferences): TeumPreferences = update { it.copy(notifications = value) }
    fun setAtmosphere(value: Atmosphere?): TeumPreferences = update { it.copy(atmosphere = value) }
    fun setPrivacy(value: PrivacyPreferences): TeumPreferences = update { it.copy(privacy = value) }
}
